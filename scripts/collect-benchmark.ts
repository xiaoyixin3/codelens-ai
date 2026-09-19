import { mkdir, readFile, rename, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { ReplayCaseSchema, type ReplayCase } from '@codelens/evaluation';
import { redactSecrets } from '@codelens/security';

interface Source {
  owner: string;
  repo: string;
  license: string;
  quota: number;
}

interface GitHubPull {
  number: number;
  title: string;
  body: string | null;
  html_url: string;
  merged_at: string | null;
  base: { sha: string };
  head: { sha: string };
  user: { type: string } | null;
}

interface GitHubFile {
  filename: string;
  previous_filename?: string;
  status: 'added' | 'modified' | 'removed' | 'renamed' | 'copied' | 'changed' | 'unchanged';
  additions: number;
  deletions: number;
  patch?: string;
}

const root = process.cwd();
const args = process.argv.slice(2);
const target = Number(args.find((arg) => arg.startsWith('--target='))?.split('=')[1] ?? 100);
const outputPath = path.resolve(
  root,
  args.find((arg) => arg.startsWith('--output='))?.split('=')[1]
    ?? 'benchmarks/candidates/public-prs.jsonl'
);
const sourcePath = path.resolve(root, 'benchmarks/sources.json');
const token = process.env.GITHUB_TOKEN?.trim();

if (!token) throw new Error('GITHUB_TOKEN is required to collect benchmark candidates.');
if (!Number.isInteger(target) || target <= 0) throw new Error('--target must be a positive integer.');

const sources = JSON.parse(await readFile(sourcePath, 'utf8')) as Source[];
const allowedLicenses = new Set(['MIT', 'Apache-2.0', 'BSD-2-Clause', 'BSD-3-Clause', 'ISC']);
const sourceQuota = new Map(
  sources.map((source) => [`${source.owner}/${source.repo}`, Math.min(source.quota, target)])
);
const headers = {
  accept: 'application/vnd.github+json',
  authorization: `Bearer ${token}`,
  'user-agent': 'codelens-ai-benchmark-collector',
  'x-github-api-version': '2022-11-28'
};

async function githubGet<T>(endpoint: string): Promise<T> {
  let lastError = 'unknown error';
  for (let attempt = 1; attempt <= 4; attempt += 1) {
    try {
      const response = await fetch(`https://api.github.com${endpoint}`, {
        headers,
        signal: AbortSignal.timeout(30_000)
      });
      if (response.ok) return await response.json() as T;
      const remaining = response.headers.get('x-ratelimit-remaining');
      lastError = `HTTP ${response.status} (remaining=${remaining ?? 'unknown'})`;
      if (response.status < 500) break;
    } catch (error) {
      lastError = error instanceof Error ? error.message : String(error);
    }
    await new Promise((resolve) => setTimeout(resolve, attempt * 500));
  }
  throw new Error(`GitHub ${endpoint} failed after retries: ${lastError}.`);
}

function isReviewablePath(value: string): boolean {
  return /\.[cm]?[jt]sx?$/i.test(value) && !/(?:^|\/)(?:dist|vendor|fixtures?)(?:\/|$)/i.test(value);
}

function containsRedactedContent(value: string): boolean {
  return redactSecrets(value) !== value;
}

const cases: ReplayCase[] = [];
const collectedAt = new Date().toISOString();
const sourceResults: Array<{
  repository: string;
  requested: number;
  collected: number;
  license: string;
}> = [];
let carriedShortfall = 0;

for (const source of sources) {
  if (cases.length >= target) break;
  const repository = `${source.owner}/${source.repo}`;
  if (!allowedLicenses.has(source.license)) throw new Error(`${repository} uses a disallowed configured license.`);
  const license = await githubGet<{ license: { spdx_id: string } }>(`/repos/${repository}/license`);
  if (license.license.spdx_id !== source.license) {
    throw new Error(`${repository} license changed from ${source.license} to ${license.license.spdx_id}.`);
  }

  const quota = Math.min(
    (sourceQuota.get(repository) ?? 0) + carriedShortfall,
    target - cases.length
  );
  let collected = 0;
  for (let page = 1; collected < quota && page <= 10; page += 1) {
    const pulls = await githubGet<GitHubPull[]>(
      `/repos/${repository}/pulls?state=closed&sort=updated&direction=desc&per_page=100&page=${page}`
    );
    if (!pulls.length) break;

    for (const pull of pulls) {
      if (collected >= quota || cases.length >= target) break;
      if (!pull.merged_at || pull.user?.type === 'Bot') continue;
      if (containsRedactedContent(pull.title) || containsRedactedContent(pull.body ?? '')) continue;

      const files = await githubGet<GitHubFile[]>(
        `/repos/${repository}/pulls/${pull.number}/files?per_page=100`
      );
      if (files.length < 1 || files.length > 25) continue;
      const reviewable = files.filter((file) =>
        isReviewablePath(file.filename) && typeof file.patch === 'string' && file.patch.length > 0
      );
      if (!reviewable.length) continue;
      const patchChars = reviewable.reduce((sum, file) => sum + (file.patch?.length ?? 0), 0);
      if (patchChars > 120_000 || reviewable.some((file) => containsRedactedContent(file.patch ?? ''))) continue;

      const item = ReplayCaseSchema.parse({
        id: `${source.owner}-${source.repo}-pr-${pull.number}`.toLowerCase(),
        context: {
          owner: source.owner,
          repo: source.repo,
          number: pull.number,
          title: pull.title,
          body: (pull.body ?? '').slice(0, 10_000),
          baseSha: pull.base.sha,
          headSha: pull.head.sha,
          files: reviewable.map((file) => ({
            path: file.filename,
            ...(file.previous_filename ? { previousPath: file.previous_filename } : {}),
            status: file.status,
            additions: file.additions,
            deletions: file.deletions,
            patch: file.patch
          }))
        },
        expectedFindings: [],
        approval: { status: 'candidate' },
        provenance: {
          kind: 'historical_pr',
          sourceUrl: pull.html_url,
          repositoryLicense: source.license,
          collectedAt
        }
      }) as ReplayCase;
      cases.push(item);
      collected += 1;
      console.log(JSON.stringify({
        status: 'progress',
        repository,
        repositoryCases: collected,
        totalCases: cases.length,
        target
      }));
    }
  }
  carriedShortfall = Math.max(0, quota - collected);
  sourceResults.push({ repository, requested: quota, collected, license: source.license });
}

if (cases.length < target) {
  throw new Error(`Collected only ${cases.length}/${target} candidates; no output was written.`);
}

await mkdir(path.dirname(outputPath), { recursive: true });
const temporaryPath = `${outputPath}.${process.pid}.${Date.now()}.tmp`;
await writeFile(temporaryPath, `${cases.slice(0, target).map((item) => JSON.stringify(item)).join('\n')}\n`, {
  encoding: 'utf8',
  flag: 'wx'
});
await rename(temporaryPath, outputPath);
await writeFile(`${outputPath}.summary.json`, `${JSON.stringify({
  status: 'candidates_only',
  outputPath: path.relative(root, outputPath).replaceAll('\\', '/'),
  cases: target,
  collectedAt,
  sources: sourceResults,
  approvalWarning: 'Candidates do not count toward the release gate until each case is human-labelled and approved.'
}, null, 2)}\n`, 'utf8');

console.log(JSON.stringify({ status: 'collected', cases: target, sources: sourceResults }, null, 2));
