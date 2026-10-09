import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import path from 'node:path';
import { realpath, mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { createHash } from 'node:crypto';
import { z } from 'zod';
import { ReplayCaseSchema, ReviewContextBundleSchema, computeReviewContextBundleDigest, type ReplayCase } from '@codelens/evaluation';

const run = promisify(execFile);
export const LocalReviewInputSchema = z.object({
  repository: z.string().trim().min(1).max(2000),
  base: z.string().trim().min(1).max(200).default('HEAD^'),
  head: z.string().trim().min(1).max(200).default('HEAD')
}).strict();

interface Index {
  symbols: Array<{ stableKey: string; qualifiedName: string; kind: string; path: string; startLine: number; endLine: number }>;
  relationships: Array<{ fromStableKey: string; toStableKey: string; type: string; sourcePath: string; sourceLine: number; typeResolved: boolean }>;
  coverage: { indexedFiles: number; failedFiles: number; skippedFiles: number; unresolvedRelationships: number };
}

export async function analyzeLocalReview(input: unknown) {
  const parsed = LocalReviewInputSchema.parse(input);
  const repository = await realpath(parsed.repository);
  const git = async (...args: string[]) => {
    try {
      return (await run('git', ['-c', 'core.hooksPath=', '-C', repository, ...args],
        { timeout: 15_000, maxBuffer: 4 * 1024 * 1024, encoding: 'utf8' })).stdout;
    } catch { throw new Error('无法读取本地 Git 仓库或提交：请检查仓库路径与 Base / Head。HEAD^ 需要至少两次提交；大型变更请缩小范围。'); }
  };
  const resolve = async (ref: string) => {
    const sha = (await git('rev-parse', '--verify', '--end-of-options', `${ref}^{commit}`)).trim();
    if (!/^[a-f0-9]{40}$/i.test(sha)) throw new Error('仅支持完整 SHA-1 Git 提交。');
    return sha;
  };
  const [baseSha, headSha] = await Promise.all([resolve(parsed.base), resolve(parsed.head)]);
  const changed = (await git('diff', '--no-ext-diff', '--no-textconv', '--name-status', '--no-renames', '-z', baseSha, headSha, '--')).split('\0');
  const files = [];
  for (let index = 0; index + 1 < changed.length; index += 2) {
    const file = changed[index + 1]!;
    // Java/build review only; configuration secrets and binary files never enter the UI.
    if (!/\.java$|(?:^|\/)(?:pom\.xml|(?:build|settings)\.gradle(?:\.kts)?)$/.test(file)) continue;
    if (files.length >= 100) throw new Error('本地审查最多支持 100 个 Java/构建变更文件，请缩小提交范围。');
    const patch = await git('--literal-pathspecs', 'diff', '--no-ext-diff', '--no-textconv', '--no-renames', '--unified=5', baseSha, headSha, '--', file);
    const lines = patch.split(/\r?\n/);
    files.push({ path: file, status: changed[index] === 'A' ? 'added' : changed[index] === 'D' ? 'removed' : 'modified',
      additions: lines.filter(line => line.startsWith('+') && !line.startsWith('+++')).length,
      deletions: lines.filter(line => line.startsWith('-') && !line.startsWith('---')).length, patch });
  }
  if (!files.length) throw new Error('所选提交之间没有 Java 或构建变更。请选择其他 Base / Head；未提交文件不包含在内。');
  const jar = path.resolve('target/codelens-ai.jar');
  const temporaryRoot = await mkdtemp(path.join(tmpdir(), 'codelens-local-task-'));
  let output;
  try {
    output = await run('java', ['-Xmx512m', `-Djava.io.tmpdir=${temporaryRoot}`, '-Dloader.main=ai.codelens.semantic.LocalReviewCli', '-cp', jar,
      'org.springframework.boot.loader.launch.PropertiesLauncher', repository, baseSha, headSha],
    { timeout: 120_000, maxBuffer: 40 * 1024 * 1024, encoding: 'utf8' });
  } catch {
    throw new Error('Java 索引失败或超过 2 分钟上限。请通过桌面启动入口重建并确认 Java 17+；上下文仅支持合计 2000 文件 / 25 MiB，大型仓库请缩小范围。');
  } finally {
    // Exact random directory created above; never the repository, workspace root or shared temp directory.
    await rm(temporaryRoot, { recursive: true, force: true, maxRetries: 3, retryDelay: 200 });
  }
  const raw = JSON.parse(output.stdout) as { base: Index; head: Index; files: Array<Record<string, unknown>> };
  const id = `local-${createHash('sha256').update(`${repository}\n${baseSha}\n${headSha}`).digest('hex').slice(0, 24)}`;
  const generatedAt = new Date().toISOString();
  const item = ReplayCaseSchema.parse({ id, context: { owner: 'local', repo: path.basename(repository), number: 1,
    title: (await git('log', '-1', '--format=%s', headSha)).trim(),
    body: `本地 Java 审查：${baseSha.slice(0, 12)} → ${headSha.slice(0, 12)}。只读取已提交内容，不执行仓库构建、测试或代码。`,
    baseSha, headSha, files }, expectedFindings: [], approval: { status: 'candidate' }, provenance: { kind: 'fixture' } }) as ReplayCase;
  const symbols = []; const relationships = []; const limitations = [
    '本地辅助结果，不是正式评测标签，也不证明不存在缺陷。未运行构建、测试或 LLM；仅展示可追溯上下文和结构化方案。',
    '上下文仅包含 Java 与构建描述文件；未提交修改、资源、反射及动态行为未覆盖。'
  ];
  for (const revision of ['base', 'head'] as const) {
    const index = raw[revision];
    const knownFiles = new Set(raw.files.filter(file => file.revision === revision).map(file => file.path));
    const current = index.symbols.filter(symbol => knownFiles.has(symbol.path) && `${revision}:${symbol.stableKey}`.length <= 500)
      .map(symbol => ({ id: `${revision}:${symbol.stableKey}`, name: symbol.qualifiedName, kind: symbol.kind.toLowerCase(),
        path: symbol.path, revision, startLine: symbol.startLine, endLine: symbol.endLine }));
    symbols.push(...current);
    const known = new Set(current.map(symbol => symbol.id));
    for (const edge of index.relationships) {
      if (!edge.typeResolved || !known.has(`${revision}:${edge.fromStableKey}`) || !known.has(`${revision}:${edge.toStableKey}`)) continue;
      const type = edge.type.toLowerCase();
      relationships.push({ fromSymbolId: `${revision}:${edge.fromStableKey}`, toSymbolId: `${revision}:${edge.toStableKey}`,
        type: ['calls', 'implements', 'extends', 'tests'].includes(type) ? type : 'references', evidencePath: edge.sourcePath, evidenceLine: edge.sourceLine });
    }
    limitations.push(`${revision}: Java indexed ${index.coverage.indexedFiles}; failed ${index.coverage.failedFiles}; skipped ${index.coverage.skippedFiles}; unresolved ${index.coverage.unresolvedRelationships}. 未解析的关系不作为已证实调用展示。`);
    if (current.length !== index.symbols.length) limitations.push(`${revision}: 部分符号超出上下文或 ID 长度限制，未显示。`);
  }
  const unsigned = { version: 1, caseId: id, packetId: id, generatedAt, baseSha, headSha, files: raw.files, symbols, relationships, limitations };
  const bundle = ReviewContextBundleSchema.parse({ ...unsigned, digest: computeReviewContextBundleDigest(unsigned as Parameters<typeof computeReviewContextBundleDigest>[0]) });
  const dirty = Boolean((await git('status', '--porcelain', '--untracked-files=no')).trim());
  return { item, bundle, repository, dirty };
}
