import { execFile } from 'node:child_process';
import { mkdtemp, mkdir, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { promisify } from 'node:util';
import { afterEach, describe, expect, it } from 'vitest';
import { computeReviewContextBundleDigest, ReviewContextBundleSchema } from '@codelens/evaluation';

const execute = promisify(execFile);
const temporaryDirectories: string[] = [];

afterEach(async () => {
  await Promise.all(temporaryDirectories.splice(0).map((directory) => rm(directory, { recursive: true, force: true })));
});

async function git(repository: string, ...args: string[]): Promise<string> {
  const result = await execute('git', ['-C', repository, ...args]);
  return result.stdout.trim();
}

describe('neutral review context builder', () => {
  it('freezes clean base/head worktrees and records missing semantic coverage', async () => {
    const directory = await mkdtemp(path.join(tmpdir(), 'codelens-context-builder-'));
    temporaryDirectories.push(directory);
    const repository = path.join(directory, 'repository');
    const baseWorktree = path.join(directory, 'base');
    await mkdir(path.join(repository, 'src'), { recursive: true });
    await git(repository, 'init');
    await git(repository, 'config', 'user.email', 'test@example.invalid');
    await git(repository, 'config', 'user.name', 'CodeLens Test');
    await writeFile(path.join(repository, 'src', 'Service.java'), 'class Service { void oldPath() {} }\n', 'utf8');
    await git(repository, 'add', '.');
    await git(repository, 'commit', '-m', 'base');
    const baseSha = await git(repository, 'rev-parse', 'HEAD');
    await writeFile(path.join(repository, 'src', 'Service.java'), 'class Service { void newPath() {} }\n', 'utf8');
    await writeFile(path.join(repository, 'src', 'ServiceTest.java'), 'class ServiceTest { void verifiesNewPath() {} }\n', 'utf8');
    await git(repository, 'add', '.');
    await git(repository, 'commit', '-m', 'head');
    const headSha = await git(repository, 'rev-parse', 'HEAD');
    await git(repository, 'worktree', 'add', '--detach', baseWorktree, baseSha);

    const inputPath = path.join(directory, 'cases.jsonl');
    const outputPath = path.join(directory, 'context.json');
    const replayCase = {
      id: 'builder-case',
      context: {
        owner: 'example', repo: 'service', number: 1, title: 'Change path', body: '', baseSha, headSha,
        files: [{ path: 'src/Service.java', status: 'modified', additions: 1, deletions: 1, patch: '@@ -1 +1 @@\n-class Service { void oldPath() {} }\n+class Service { void newPath() {} }' }]
      },
      expectedFindings: [], approval: { status: 'candidate' },
      provenance: { kind: 'historical_pr', sourceUrl: 'https://github.com/example/service/pull/1', repositoryLicense: 'Apache-2.0', collectedAt: '2026-10-02T10:00:00+08:00' }
    };
    await writeFile(inputPath, `${JSON.stringify(replayCase)}\n`, 'utf8');

    await execute(process.execPath, [
      '--import', 'tsx', 'scripts/build-review-context.ts', `--input=${inputPath}`, '--case=builder-case',
      `--base-root=${baseWorktree}`, `--head-root=${repository}`, `--output=${outputPath}`
    ], { cwd: path.resolve(import.meta.dirname, '..') });

    const bundle = ReviewContextBundleSchema.parse(JSON.parse(await readFile(outputPath, 'utf8')));
    const { digest, ...unsigned } = bundle;
    expect(computeReviewContextBundleDigest(unsigned)).toBe(digest);
    expect(bundle.baseSha).toBe(baseSha);
    expect(bundle.headSha).toBe(headSha);
    expect(bundle.files.some((file) => file.revision === 'base' && file.content.includes('oldPath'))).toBe(true);
    expect(bundle.files.some((file) => file.revision === 'head' && file.content.includes('newPath'))).toBe(true);
    expect(bundle.files.some((file) => file.role === 'test')).toBe(true);
    expect(bundle.limitations).toEqual(expect.arrayContaining([
      'base semantic index was not supplied.',
      'head semantic index was not supplied.'
    ]));
  }, 20_000);
});
