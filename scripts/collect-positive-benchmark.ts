import { mkdir, readFile, rename, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { ReplayCaseSchema, type ReplayCase } from '@codelens/evaluation';
import { DeterministicRiskReviewer, DiffMap, EvidenceVerifier } from '@codelens/risk-review';
import { redactSecrets } from '@codelens/security';

interface CodeSearchItem {
  path: string;
  repository: { full_name: string };
}

interface GitHubCommitListItem {
  sha: string;
}

interface GitHubCommit {
  sha: string;
  files?: GitHubFile[];
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
const readArg = (name: string, fallback: string): string =>
  args.find((arg) => arg.startsWith(`${name}=`))?.slice(name.length + 1) ?? fallback;
const targetPositive = Number(readArg('--target-positive', '20'));
const targetTotal = Number(readArg('--target-total', '100'));
const negativeInput = path.resolve(root, readArg('--negative-input', 'benchmarks/candidates/public-prs.jsonl'));
const positiveOutput = path.resolve(root, readArg('--positive-output', 'benchmarks/candidates/positive-public-prs.jsonl'));
const queueOutput = path.resolve(root, readArg('--queue-output', 'benchmarks/candidates/review-queue.jsonl'));
const token = process.env.GITHUB_TOKEN?.trim();

if (!token) throw new Error('GITHUB_TOKEN is required to collect positive benchmark candidates.');
if (!Number.isInteger(targetPositive) || targetPositive < 1) {
  throw new Error('--target-positive must be a positive integer.');
}
if (!Number.isInteger(targetTotal) || targetTotal < targetPositive) {
  throw new Error('--target-total must be an integer greater than or equal to --target-positive.');
}

const allowedLicenses = new Set(['MIT', 'Apache-2.0', 'BSD-2-Clause', 'BSD-3-Clause', 'ISC']);
const searchQueries = [
  'org:microsoft ".forEach(async" language:TypeScript',
  'org:vercel ".forEach(async" language:TypeScript',
  'org:elastic ".forEach(async" language:TypeScript',
  'org:Automattic ".forEach(async" language:TypeScript',
  'org:Shopify ".forEach(async" language:TypeScript',
  'org:apollographql ".forEach(async" language:TypeScript',
  'org:GoogleChromeLabs ".forEach(async" language:TypeScript'
];
const addedRiskPattern = /^\+.*\.forEach\s*\(\s*async\b/m;
const headers = {
  accept: 'application/vnd.github+json',
  authorization: `Bearer ${token}`,
  'user-agent': 'codelens-ai-positive-benchmark-collector',
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
      if (response.status < 500 && response.status !== 403) break;
    } catch (error) {
      lastError = error instanceof Error ? error.message : String(error);
    }
    await new Promise((resolve) => setTimeout(resolve, attempt * 750));
  }
  throw new Error(`GitHub ${endpoint} failed after retries: ${lastError}.`);
}

function isReviewablePath(value: string): boolean {
  return /\.[cm]?[jt]sx?$/i.test(value) && !/(?:^|\/)(?:dist|vendor|fixtures?)(?:\/|$)/i.test(value);
}

function containsRedactedContent(value: string): boolean {
  return redactSecrets(value) !== value;
}

function interleave(positive: ReplayCase[], negative: ReplayCase[]): ReplayCase[] {
  const queue: ReplayCase[] = [];
  const negativesPerPositive = Math.max(1, Math.floor(negative.length / positive.length));
  let negativeIndex = 0;
  for (const item of positive) {
    queue.push(item);
    for (let count = 0; count < negativesPerPositive && negativeIndex < negative.length; count += 1) {
      queue.push(negative[negativeIndex++]!);
    }
  }
  queue.push(...negative.slice(negativeIndex));
  return queue;
}

async function writeJsonLines(outputPath: string, cases: ReplayCase[]): Promise<void> {
  await mkdir(path.dirname(outputPath), { recursive: true });
  const temporaryPath = `${outputPath}.${process.pid}.tmp`;
  await writeFile(temporaryPath, `${cases.map((item) => JSON.stringify(item)).join('\n')}\n`, 'utf8');
  await rename(temporaryPath, outputPath);
}

const reviewer = new DeterministicRiskReviewer();
const verifier = new EvidenceVerifier({ maxPublished: 100 });
const licenseCache = new Map<string, string | null>();
const seenPaths = new Set<string>();
const seenPulls = new Set<string>();
const positiveCases: ReplayCase[] = [];
const collectedAt = new Date().toISOString();

for (const query of searchQueries) {
  if (positiveCases.length >= targetPositive) break;
  const result = await githubGet<{ items: CodeSearchItem[] }>(
    `/search/code?q=${encodeURIComponent(query)}&per_page=100`
  );

  for (const item of result.items) {
    if (positiveCases.length >= targetPositive) break;
    const repository = item.repository.full_name;
    const pathKey = `${repository}:${item.path}`;
    if (seenPaths.has(pathKey)) continue;
    seenPaths.add(pathKey);

    let license = licenseCache.get(repository);
    if (license === undefined) {
      try {
        const response = await githubGet<{ license: { spdx_id: string } }>(`/repos/${repository}/license`);
        license = allowedLicenses.has(response.license.spdx_id) ? response.license.spdx_id : null;
      } catch {
        license = null;
      }
      licenseCache.set(repository, license);
    }
    if (!license) continue;

    const commits = await githubGet<GitHubCommitListItem[]>(
      `/repos/${repository}/commits?path=${encodeURIComponent(item.path)}&per_page=100`
    );
    for (const commit of commits.slice(0, 40)) {
      const detail = await githubGet<GitHubCommit>(`/repos/${repository}/commits/${commit.sha}`);
      const changed = detail.files?.find((file) => file.filename === item.path);
      if (!changed?.patch || !addedRiskPattern.test(changed.patch)) continue;

      const pulls = await githubGet<GitHubPull[]>(`/repos/${repository}/commits/${commit.sha}/pulls`);
      const pull = pulls.find((candidate) => candidate.merged_at && candidate.user?.type !== 'Bot');
      if (!pull || seenPulls.has(pull.html_url)) continue;

      const files = await githubGet<GitHubFile[]>(
        `/repos/${repository}/pulls/${pull.number}/files?per_page=100`
      );
      if (files.length < 1 || files.length > 25) continue;
      const reviewable = files.filter((file) =>
        isReviewablePath(file.filename) && typeof file.patch === 'string' && file.patch.length > 0
      );
      if (!reviewable.length) continue;
      const patchChars = reviewable.reduce((sum, file) => sum + (file.patch?.length ?? 0), 0);
      if (patchChars > 120_000) continue;
      if ([pull.title, pull.body ?? '', ...reviewable.map((file) => file.patch ?? '')]
        .some(containsRedactedContent)) continue;

      const [owner, repo] = repository.split('/');
      if (!owner || !repo) continue;
      const replayCase = ReplayCaseSchema.parse({
        id: `${owner}-${repo}-pr-${pull.number}`.toLowerCase(),
        context: {
          owner,
          repo,
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
          repositoryLicense: license,
          collectedAt
        }
      }) as ReplayCase;
      const diff = new DiffMap(replayCase.context.files);
      const suggestions = verifier.verify(await reviewer.review(replayCase.context, diff), diff)
        .filter((finding) => finding.status === 'verified');
      if (!suggestions.some((finding) => finding.ruleId === 'concurrency/no-async-foreach')) continue;

      seenPulls.add(pull.html_url);
      positiveCases.push(replayCase);
      await writeJsonLines(`${positiveOutput}.partial`, positiveCases);
      console.log(JSON.stringify({
        status: 'progress',
        positiveCases: positiveCases.length,
        targetPositive,
        repository,
        pull: pull.number,
        sourceUrl: pull.html_url,
        suggestions: suggestions.map((finding) => ({
          ruleId: finding.ruleId,
          path: finding.path,
          line: finding.line
        }))
      }));
      break;
    }
  }
}

if (positiveCases.length < targetPositive) {
  throw new Error(
    `Collected only ${positiveCases.length}/${targetPositive} positive candidates. ` +
    `Partial output is preserved at ${path.relative(root, `${positiveOutput}.partial`)}`
  );
}

const negativeSource = await readFile(negativeInput, 'utf8');
const negativeCases = negativeSource
  .split(/\r?\n/)
  .filter((line) => line.trim())
  .map((line) => ReplayCaseSchema.parse(JSON.parse(line)) as ReplayCase)
  .filter((item) => item.provenance.kind === 'historical_pr' && !seenPulls.has(item.provenance.sourceUrl))
  .slice(0, targetTotal - targetPositive);
if (negativeCases.length < targetTotal - targetPositive) {
  throw new Error(`Only ${negativeCases.length}/${targetTotal - targetPositive} negative candidates are available.`);
}

const queue = interleave(positiveCases, negativeCases);
await writeJsonLines(positiveOutput, positiveCases);
await writeJsonLines(queueOutput, queue);
await writeFile(`${queueOutput}.summary.json`, `${JSON.stringify({
  status: 'review_required',
  cases: queue.length,
  suggestedPositiveCases: positiveCases.length,
  suggestedNegativeCases: negativeCases.length,
  positiveOutput: path.relative(root, positiveOutput).replaceAll('\\', '/'),
  queueOutput: path.relative(root, queueOutput).replaceAll('\\', '/'),
  warning: 'Suggestions are machine-generated candidate labels. A human must inspect and approve every case.'
}, null, 2)}\n`, 'utf8');

console.log(JSON.stringify({
  status: 'collected',
  positiveCases: positiveCases.length,
  negativeCases: negativeCases.length,
  reviewQueue: queue.length
}, null, 2));
