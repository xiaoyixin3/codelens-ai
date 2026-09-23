import { mkdir, rename, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { App } from 'octokit';
import { loadConfig } from '@codelens/config';
import { ReplayCaseSchema, type ReplayCase } from '@codelens/evaluation';
import { redactSecrets } from '@codelens/security';

interface Source {
  owner: string;
  repo: string;
  license: string;
  language: 'typescript' | 'go' | 'java' | 'python';
  extensions: string[];
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
const target = Number(args.find((arg) => arg.startsWith('--target='))?.split('=')[1] ?? 120);
const requestedLanguages = new Set(
  (args.find((arg) => arg.startsWith('--languages='))?.split('=')[1] ?? 'typescript,go,java,python')
    .split(',')
    .map((value) => value.trim().toLowerCase())
    .filter(Boolean)
);
const outputPath = path.resolve(
  root,
  args.find((arg) => arg.startsWith('--output='))?.split('=')[1]
    ?? 'benchmarks/candidates/public-prs.jsonl'
);
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

if (!token) throw new Error('GITHUB_TOKEN or GitHub App credentials are required to collect benchmark candidates.');
if (!Number.isInteger(target) || target <= 0) throw new Error('--target must be a positive integer.');
const supportedLanguages = new Set<Source['language']>(['typescript', 'go', 'java', 'python']);
for (const language of requestedLanguages) {
  if (!supportedLanguages.has(language as Source['language'])) {
    throw new Error(`Unsupported --languages value: ${language}.`);
  }
}

const configuredSources: Source[] = [
  { owner: 'fastify', repo: 'fastify', license: 'MIT', language: 'typescript', extensions: ['.js', '.mjs', '.cjs', '.ts', '.tsx'] },
  { owner: 'axios', repo: 'axios', license: 'MIT', language: 'typescript', extensions: ['.js', '.mjs', '.cjs', '.ts', '.tsx'] },
  { owner: 'gin-gonic', repo: 'gin', license: 'MIT', language: 'go', extensions: ['.go'] },
  { owner: 'prometheus', repo: 'client_golang', license: 'Apache-2.0', language: 'go', extensions: ['.go'] },
  { owner: 'go-chi', repo: 'chi', license: 'MIT', language: 'go', extensions: ['.go'] },
  { owner: 'uber-go', repo: 'zap', license: 'MIT', language: 'go', extensions: ['.go'] },
  { owner: 'stretchr', repo: 'testify', license: 'MIT', language: 'go', extensions: ['.go'] },
  { owner: 'spf13', repo: 'cobra', license: 'Apache-2.0', language: 'go', extensions: ['.go'] },
  { owner: 'go-gorm', repo: 'gorm', license: 'MIT', language: 'go', extensions: ['.go'] },
  { owner: 'spring-projects', repo: 'spring-boot', license: 'Apache-2.0', language: 'java', extensions: ['.java'] },
  { owner: 'google', repo: 'guava', license: 'Apache-2.0', language: 'java', extensions: ['.java'] },
  { owner: 'pallets', repo: 'flask', license: 'BSD-3-Clause', language: 'python', extensions: ['.py'] },
  { owner: 'psf', repo: 'requests', license: 'Apache-2.0', language: 'python', extensions: ['.py'] }
];
const sources = configuredSources.filter((source) => requestedLanguages.has(source.language));
if (!sources.length) throw new Error('No benchmark sources match --languages.');
const allowedLicenses = new Set(['MIT', 'Apache-2.0', 'BSD-2-Clause', 'BSD-3-Clause', 'ISC']);
const sourceQuota = new Map(
  sources.map((source) => [`${source.owner}/${source.repo}`, Math.min(Math.ceil(target / sources.length), target)])
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

function isReviewablePath(value: string, source: Source): boolean {
  return source.extensions.some((extension) => value.toLowerCase().endsWith(extension))
    && !/(?:^|\/)(?:dist|vendor|fixtures?|generated|third_party)(?:\/|$)/i.test(value);
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
  language: Source['language'];
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
        isReviewablePath(file.filename, source) && typeof file.patch === 'string' && file.patch.length > 0
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
  sourceResults.push({ repository, requested: quota, collected, license: source.license, language: source.language });
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
  authSource,
  requestedLanguages: [...requestedLanguages].sort(),
  sources: sourceResults,
  languages: Object.fromEntries([...requestedLanguages].sort().map((language) => [
    language,
    sourceResults.filter((source) => source.language === language).reduce((sum, source) => sum + source.collected, 0)
  ])),
  samplingProtocol: 'Repository-stratified historical PR sampling. Detector output is never used to select candidates.',
  approvalWarning: 'Candidates do not count toward the release gate until each case is human-labelled and approved.'
}, null, 2)}\n`, 'utf8');

console.log(JSON.stringify({ status: 'collected', cases: target, sources: sourceResults }, null, 2));
