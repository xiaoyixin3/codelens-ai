import { mkdir, readFile, rename, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { App } from 'octokit';
import { loadConfig } from '@codelens/config';
import { ReplayCaseSchema, reverseUnifiedPatch, type ReplayCase } from '@codelens/evaluation';
import { DiffMap } from '@codelens/risk-review';
import { redactSecrets } from '@codelens/security';

interface Source {
  owner: string;
  repo: string;
  license: string;
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
  labels: Array<{ name: string }>;
}

interface GitHubFile {
  filename: string;
  status: 'added' | 'modified' | 'removed' | 'renamed' | 'copied' | 'changed' | 'unchanged';
  additions: number;
  deletions: number;
  patch?: string;
}

const root = process.cwd();
const args = process.argv.slice(2);
const readArg = (name: string, fallback: string): string =>
  args.find((arg) => arg.startsWith(`${name}=`))?.slice(name.length + 1) ?? fallback;
const target = Number(readArg('--target', '30'));
const outputPath = path.resolve(root, readArg('--output', 'benchmarks/candidates/go-fix-reverse-curated.jsonl'));
const negativeInput = path.resolve(root, readArg('--negative-input', 'benchmarks/candidates/go-blind-approved-replay.jsonl'));

if (!Number.isInteger(target) || target < 1) throw new Error('--target must be a positive integer.');

const configuredToken = process.env.GITHUB_TOKEN?.trim();
const config = loadConfig();
const app = !configuredToken && config.GITHUB_APP_ID && config.GITHUB_PRIVATE_KEY
  ? new App({ appId: config.GITHUB_APP_ID, privateKey: config.GITHUB_PRIVATE_KEY })
  : undefined;
const installations = app
  ? await app.octokit.paginate(app.octokit.rest.apps.listInstallations, { per_page: 100 })
  : [];
const installationClient = app && installations[0]
  ? await app.getInstallationOctokit(installations[0].id)
  : undefined;
const installationAuthentication = installationClient
  ? await installationClient.auth({ type: 'installation' }) as { token: string }
  : undefined;
const token = configuredToken ?? installationAuthentication?.token;
const authSource = configuredToken ? 'github_token' : installationAuthentication ? 'github_app_installation' : 'none';
if (!token) throw new Error('GITHUB_TOKEN or GitHub App credentials are required.');

const sources: Source[] = [
  { owner: 'gin-gonic', repo: 'gin', license: 'MIT' },
  { owner: 'prometheus', repo: 'client_golang', license: 'Apache-2.0' },
  { owner: 'go-chi', repo: 'chi', license: 'MIT' },
  { owner: 'uber-go', repo: 'zap', license: 'MIT' },
  { owner: 'stretchr', repo: 'testify', license: 'MIT' },
  { owner: 'spf13', repo: 'cobra', license: 'Apache-2.0' },
  { owner: 'go-gorm', repo: 'gorm', license: 'MIT' },
  { owner: 'grpc', repo: 'grpc-go', license: 'Apache-2.0' },
  { owner: 'etcd-io', repo: 'etcd', license: 'Apache-2.0' }
];
const allowedLicenses = new Set(['MIT', 'Apache-2.0', 'BSD-2-Clause', 'BSD-3-Clause', 'ISC']);
const fixTitleSignal = /\b(fix(?:es|ed)?|bug|panic|race|deadlock|leak|security|vulnerab\w*|timeout|crash|nil pointer|incorrect|corrupt\w*|data loss|hang|regression)\b/i;
const bugLabelSignal = /\b(bug|security|vulnerability|regression|kind\/bug|type\/bug)\b/i;
const lowValueTitle = /\b(docs?|documentation|typos?|comments?|readme|release|chore|lint|format(?:ting)?)\b/i;
const excludedPath = /(?:^|\/)(?:vendor|fixtures?|generated|third_party)(?:\/|$)/i;
const testPath = /(?:^|\/)(?:test|tests)(?:\/|$)|_test\.go$/i;
const headers = {
  accept: 'application/vnd.github+json',
  authorization: `Bearer ${token}`,
  'user-agent': 'codelens-ai-go-fix-benchmark-collector',
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
      lastError = `HTTP ${response.status} (remaining=${response.headers.get('x-ratelimit-remaining') ?? 'unknown'})`;
      if (response.status < 500 && response.status !== 403) break;
    } catch (error) {
      lastError = error instanceof Error ? error.message : String(error);
    }
    await new Promise((resolve) => setTimeout(resolve, attempt * 750));
  }
  throw new Error(`GitHub ${endpoint} failed after retries: ${lastError}.`);
}

function containsRedactedContent(value: string): boolean {
  return redactSecrets(value) !== value;
}

function hasFixMetadata(pull: GitHubPull): boolean {
  if (lowValueTitle.test(pull.title)) return false;
  return fixTitleSignal.test(pull.title)
    || bugLabelSignal.test(pull.labels.map((label) => label.name).join(' '));
}

function isMeaningfulCodeLine(value: string): boolean {
  const line = value.trim();
  return Boolean(line)
    && !line.startsWith('//')
    && !line.startsWith('/*')
    && !line.startsWith('*')
    && !/^[{}(),;]+$/.test(line);
}

async function readExistingIds(): Promise<Set<string>> {
  try {
    const source = await readFile(negativeInput, 'utf8');
    return new Set(source.split(/\r?\n/).filter(Boolean).map((line) => ReplayCaseSchema.parse(JSON.parse(line)).id));
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code === 'ENOENT') return new Set();
    throw error;
  }
}

const existingIds = await readExistingIds();
const cases: ReplayCase[] = [];
const seenUrls = new Set<string>();
const collectedAt = new Date().toISOString();
const perSource = Math.ceil(target / sources.length);
const sourceResults: Array<{ repository: string; collected: number; scannedPages: number }> = [];
let shortfall = 0;

for (const source of sources) {
  if (cases.length >= target) break;
  const repository = `${source.owner}/${source.repo}`;
  if (!allowedLicenses.has(source.license)) throw new Error(`${repository} has a disallowed configured license.`);
  const license = await githubGet<{ license: { spdx_id: string } }>(`/repos/${repository}/license`);
  if (license.license.spdx_id !== source.license) {
    throw new Error(`${repository} license changed from ${source.license} to ${license.license.spdx_id}.`);
  }

  const quota = Math.min(perSource + shortfall, target - cases.length);
  let collected = 0;
  let scannedPages = 0;
  for (let page = 1; collected < quota && page <= 15; page += 1) {
    const pulls = await githubGet<GitHubPull[]>(
      `/repos/${repository}/pulls?state=closed&sort=updated&direction=desc&per_page=100&page=${page}`
    );
    if (!pulls.length) break;
    scannedPages = page;

    for (const pull of pulls) {
      if (collected >= quota || cases.length >= target) break;
      const originalId = `${source.owner}-${source.repo}-pr-${pull.number}`.toLowerCase();
      if (!pull.merged_at || pull.user?.type === 'Bot' || !hasFixMetadata(pull)
        || existingIds.has(originalId) || seenUrls.has(pull.html_url)) continue;
      if (containsRedactedContent(pull.title) || containsRedactedContent(pull.body ?? '')) continue;

      const files = await githubGet<GitHubFile[]>(`/repos/${repository}/pulls/${pull.number}/files?per_page=100`);
      if (files.length < 1 || files.length > 25) continue;
      const reversedFiles = files
        .filter((file) => file.status === 'modified'
          && file.filename.toLowerCase().endsWith('.go')
          && !excludedPath.test(file.filename)
          && typeof file.patch === 'string'
          && file.patch.length > 0
          && !containsRedactedContent(file.patch))
        .map((file) => ({
          path: file.filename,
          status: 'modified' as const,
          additions: file.deletions,
          deletions: file.additions,
          patch: reverseUnifiedPatch(file.patch!)
        }));
      if (!reversedFiles.length) continue;
      const patchChars = reversedFiles.reduce((sum, file) => sum + file.patch.length, 0);
      if (patchChars > 120_000) continue;
      const diff = new DiffMap(reversedFiles);
      if (!diff.addedLines().some((line) => !testPath.test(line.path) && isMeaningfulCodeLine(line.content))) continue;

      const item = ReplayCaseSchema.parse({
        id: `${originalId}-reverse-fix`,
        context: {
          owner: source.owner,
          repo: source.repo,
          number: pull.number,
          title: pull.title,
          body: [
            'Reverse replay: the diff direction is fixed code -> pre-fix code.',
            'Review the added right-side lines as potential defect-introducing changes.',
            '',
            (pull.body ?? '').slice(0, 9_000)
          ].join('\n'),
          baseSha: pull.head.sha,
          headSha: pull.base.sha,
          files: reversedFiles
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
      seenUrls.add(pull.html_url);
      collected += 1;
      console.log(JSON.stringify({ status: 'progress', repository, repositoryCases: collected, totalCases: cases.length, target }));
    }
  }
  shortfall = Math.max(0, quota - collected);
  sourceResults.push({ repository, collected, scannedPages });
}

if (cases.length < target) throw new Error(`Collected only ${cases.length}/${target} reverse-fix candidates; no output was written.`);

await mkdir(path.dirname(outputPath), { recursive: true });
const temporaryPath = `${outputPath}.${process.pid}.${Date.now()}.tmp`;
await writeFile(temporaryPath, `${cases.slice(0, target).map((item) => JSON.stringify(item)).join('\n')}\n`, { encoding: 'utf8', flag: 'wx' });
await rename(temporaryPath, outputPath);
await writeFile(`${outputPath}.summary.json`, `${JSON.stringify({
  status: 'candidates_only',
  cases: target,
  collectedAt,
  authSource,
  outputPath: path.relative(root, outputPath).replaceAll('\\', '/'),
  negativeInput: path.relative(root, negativeInput).replaceAll('\\', '/'),
  sourceResults,
  samplingProtocol: 'Metadata-selected merged Go fix PRs, replayed in reverse. Detector output is never used for selection.',
  approvalWarning: 'Candidates require blind human review. A reverse-fix candidate is not automatically a true positive.'
}, null, 2)}\n`, 'utf8');

console.log(JSON.stringify({ status: 'collected', cases: target, output: path.relative(root, outputPath).replaceAll('\\', '/'), sourceResults }, null, 2));
