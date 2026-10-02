import { execFile } from 'node:child_process';
import { mkdir, readFile, readdir, rename, stat, writeFile } from 'node:fs/promises';
import { promisify } from 'node:util';
import path from 'node:path';
import {
  computeReviewContextBundleDigest,
  ReplayCaseSchema,
  ReviewContextBundleSchema,
  type ReviewContextBundle
} from '@codelens/evaluation';

const execFileAsync = promisify(execFile);
const args = process.argv.slice(2);
const readArg = (name: string, fallback?: string): string => {
  const value = args.find((arg) => arg.startsWith(`${name}=`))?.slice(name.length + 1) ?? fallback;
  if (!value) throw new Error(`Missing ${name}=...`);
  return value;
};

const root = process.cwd();
const inputPath = path.resolve(root, readArg('--input', 'benchmarks/candidates/public-prs.jsonl'));
const caseId = readArg('--case');
const baseRoot = path.resolve(readArg('--base-root'));
const headRoot = path.resolve(readArg('--head-root'));
const outputPath = path.resolve(root, readArg('--output', `benchmarks/candidates/review-context/${caseId.replace(/[^A-Za-z0-9_.-]/g, '_')}.json`));
const baseSemanticPath = args.find((arg) => arg.startsWith('--base-semantic='))?.slice('--base-semantic='.length);
const headSemanticPath = args.find((arg) => arg.startsWith('--head-semantic='))?.slice('--head-semantic='.length);
const maxFiles = Number(readArg('--max-files', '2000'));
const maxBytes = Number(readArg('--max-bytes', String(25 * 1024 * 1024)));
const maxFileBytes = Number(readArg('--max-file-bytes', String(1_000_000)));

if (![maxFiles, maxBytes, maxFileBytes].every((value) => Number.isInteger(value) && value > 0)) {
  throw new Error('Context limits must be positive integers.');
}

const excluded = new Set(['.git', '.gradle', '.idea', 'target', 'build', 'node_modules', 'dist', 'out', 'coverage']);
const binaryExtensions = new Set(['.class', '.jar', '.zip', '.png', '.jpg', '.jpeg', '.gif', '.pdf', '.woff', '.woff2', '.ttf', '.ico', '.exe', '.dll']);

function normalize(value: string): string { return value.replaceAll('\\', '/'); }

async function git(rootPath: string, ...gitArgs: string[]): Promise<string> {
  const result = await execFileAsync('git', ['-C', rootPath, ...gitArgs], { timeout: 15_000, maxBuffer: 2 * 1024 * 1024 });
  return result.stdout.trim();
}

async function verifyWorktree(rootPath: string, expectedSha: string, label: string): Promise<void> {
  const info = await stat(rootPath);
  if (!info.isDirectory()) throw new Error(`${label} root is not a directory.`);
  const actual = await git(rootPath, 'rev-parse', 'HEAD');
  if (actual.toLowerCase() !== expectedSha.toLowerCase()) throw new Error(`${label} root HEAD does not match the replay SHA.`);
  if (await git(rootPath, 'status', '--porcelain')) throw new Error(`${label} root must be clean before freezing context.`);
}

async function walk(directory: string, relative = '', result: string[] = []): Promise<string[]> {
  const entries = await readdir(directory, { withFileTypes: true });
  for (const entry of entries.sort((left, right) => left.name.localeCompare(right.name))) {
    if (excluded.has(entry.name)) continue;
    const childRelative = relative ? `${relative}/${entry.name}` : entry.name;
    const absolute = path.resolve(directory, entry.name);
    if (entry.isSymbolicLink()) continue;
    if (entry.isDirectory()) await walk(absolute, childRelative, result);
    else if (entry.isFile() && !binaryExtensions.has(path.extname(entry.name).toLowerCase())) {
      if (result.length >= maxFiles) throw new Error(`Repository context exceeds max files ${maxFiles}.`);
      result.push(normalize(childRelative));
    }
  }
  return result;
}

function role(filePath: string): 'source' | 'test' | 'build' | 'documentation' | 'configuration' | 'other' {
  const lower = filePath.toLowerCase();
  if (/(^|\/)(test|tests|__tests__|src\/test)(\/|$)/.test(lower) || /(?:test|spec)\.[^.]+$/.test(lower)) return 'test';
  if (/(^|\/)(pom\.xml|build\.gradle(?:\.kts)?|settings\.gradle(?:\.kts)?|package\.json|go\.mod|cargo\.toml)$/.test(lower)) return 'build';
  if (/\.(md|adoc|rst|txt)$/.test(lower)) return 'documentation';
  if (/\.(ya?ml|toml|json|properties|conf|ini|xml)$/.test(lower)) return 'configuration';
  if (/\.(java|kt|kts|ts|tsx|js|jsx|go|rs|py|cs|cpp|c|h|hpp)$/.test(lower)) return 'source';
  return 'other';
}

function language(filePath: string): string | undefined {
  return ({ '.java': 'Java', '.kt': 'Kotlin', '.ts': 'TypeScript', '.tsx': 'TypeScript', '.js': 'JavaScript', '.jsx': 'JavaScript', '.go': 'Go', '.py': 'Python', '.rs': 'Rust', '.cs': 'C#' } as Record<string, string>)[path.extname(filePath).toLowerCase()];
}

async function collectFiles(rootPath: string, revision: 'base' | 'head') {
  const paths = await walk(rootPath);
  const files: Array<{ path: string; revision: 'base' | 'head'; role: ReturnType<typeof role>; language?: string; content: string }> = [];
  let bytes = 0;
  for (const filePath of paths) {
    const absolute = path.resolve(rootPath, ...filePath.split('/'));
    if (!absolute.startsWith(`${rootPath}${path.sep}`)) throw new Error(`Unsafe repository path: ${filePath}`);
    const info = await stat(absolute);
    if (info.size > maxFileBytes) continue;
    bytes += info.size;
    if (bytes > maxBytes) throw new Error(`${revision} context exceeds max bytes ${maxBytes}.`);
    const content = await readFile(absolute, 'utf8');
    const detected = language(filePath);
    files.push({ path: filePath, revision, role: role(filePath), ...(detected ? { language: detected } : {}), content });
  }
  return files;
}

interface RawSemanticIndex {
  symbols?: Array<{ stableKey: string; kind: string; qualifiedName?: string; signature?: string; path: string; startLine: number; endLine: number }>;
  relationships?: Array<{ fromStableKey: string; toStableKey: string; type: string; sourcePath: string; sourceLine: number }>;
}

async function collectSemantic(filePath: string | undefined, revision: 'base' | 'head') {
  if (!filePath) return { symbols: [], relationships: [], limitation: `${revision} semantic index was not supplied.` };
  const raw = JSON.parse(await readFile(path.resolve(filePath), 'utf8')) as RawSemanticIndex;
  const symbols = (raw.symbols ?? []).map((symbol) => ({
    id: `${revision}:${symbol.stableKey}`,
    name: symbol.qualifiedName ?? symbol.signature ?? symbol.stableKey,
    kind: symbol.kind.toLowerCase(),
    path: normalize(symbol.path), revision, startLine: symbol.startLine, endLine: symbol.endLine
  }));
  const known = new Set(symbols.map((symbol) => symbol.id));
  const relationshipType = (value: string): 'calls' | 'implements' | 'extends' | 'tests' | 'references' => {
    const normalized = value.toLowerCase();
    if (normalized === 'calls' || normalized === 'implements' || normalized === 'extends' || normalized === 'tests') return normalized;
    return 'references';
  };
  const relationships = (raw.relationships ?? []).map((edge) => ({
    fromSymbolId: `${revision}:${edge.fromStableKey}`, toSymbolId: `${revision}:${edge.toStableKey}`,
    type: relationshipType(edge.type), evidencePath: normalize(edge.sourcePath), evidenceLine: edge.sourceLine
  })).filter((edge) => known.has(edge.fromSymbolId) && known.has(edge.toSymbolId));
  return { symbols, relationships, limitation: undefined };
}

async function main(): Promise<void> {
  const rawCases = (await readFile(inputPath, 'utf8')).split(/\r?\n/).filter(Boolean).map((line) => ReplayCaseSchema.parse(JSON.parse(line)));
  const item = rawCases.find((candidate) => candidate.id === caseId);
  if (!item) throw new Error(`Replay case not found: ${caseId}`);
  await Promise.all([
    verifyWorktree(baseRoot, item.context.baseSha, 'base'),
    verifyWorktree(headRoot, item.context.headSha, 'head')
  ]);
  const [baseFiles, headFiles, baseSemantic, headSemantic] = await Promise.all([
    collectFiles(baseRoot, 'base'), collectFiles(headRoot, 'head'),
    collectSemantic(baseSemanticPath, 'base'), collectSemantic(headSemanticPath, 'head')
  ]);
  const unsigned = {
    version: 1 as const,
    caseId: item.id,
    packetId: `pending:${item.id}`,
    generatedAt: new Date().toISOString(),
    baseSha: item.context.baseSha,
    headSha: item.context.headSha,
    files: [...baseFiles, ...headFiles],
    symbols: [...baseSemantic.symbols, ...headSemantic.symbols],
    relationships: [...baseSemantic.relationships, ...headSemantic.relationships],
    limitations: [baseSemantic.limitation, headSemantic.limitation].filter((value): value is string => Boolean(value))
  };
  const provisionalDigest = computeReviewContextBundleDigest(unsigned);
  const packetId = `review-context-${provisionalDigest.slice(0, 24)}`;
  const finalUnsigned = { ...unsigned, packetId };
  const bundle: ReviewContextBundle = ReviewContextBundleSchema.parse({
    ...finalUnsigned,
    digest: computeReviewContextBundleDigest(finalUnsigned)
  });
  await mkdir(path.dirname(outputPath), { recursive: true });
  const temporary = `${outputPath}.${process.pid}.tmp`;
  await writeFile(temporary, `${JSON.stringify(bundle, null, 2)}\n`, 'utf8');
  await rename(temporary, outputPath);
  console.log(JSON.stringify({ status: 'created', output: path.relative(root, outputPath).replaceAll('\\', '/'), packetId: bundle.packetId, files: bundle.files.length, symbols: bundle.symbols.length, relationships: bundle.relationships.length, limitations: bundle.limitations }, null, 2));
}

await main();
