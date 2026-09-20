import { spawn, spawnSync, type ChildProcess } from 'node:child_process';
import { existsSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import type { AppAuthentication } from '@octokit/auth-app';
import { App } from 'octokit';
import { loadConfig } from '@codelens/config';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const children = new Map<ChildProcess, string>();
let shuttingDown = false;

export function extractQuickTunnelUrl(source: string): string | undefined {
  return source.match(/https:\/\/[a-z0-9-]+\.trycloudflare\.com/i)?.[0];
}

export function buildWebhookUrl(tunnelUrl: string): string {
  const url = new URL(tunnelUrl);
  url.pathname = '/webhooks/github';
  url.search = '';
  url.hash = '';
  return url.toString().replace(/\/$/, '');
}

export function normalizePublicBaseUrl(value: string): string {
  const url = new URL(value);
  if (url.protocol !== 'https:') throw new Error('CODELENS_PUBLIC_BASE_URL must use HTTPS.');
  if (url.username || url.password || url.search || url.hash || url.pathname !== '/') {
    throw new Error('CODELENS_PUBLIC_BASE_URL must be an HTTPS origin without credentials, path, query, or fragment.');
  }
  return url.origin;
}

function runChecked(command: string, args: string[], label: string): void {
  const result = spawnSync(command, args, {
    cwd: root,
    env: process.env,
    stdio: 'inherit'
  });
  if (result.error) throw new Error(`${label} could not start: ${result.error.message}`);
  if (result.status !== 0) throw new Error(`${label} failed with exit code ${result.status ?? 'unknown'}.`);
}

function resolveCloudflared(): string {
  const candidates = [
    process.env.CLOUDFLARED_BIN?.trim(),
    'cloudflared',
    process.platform === 'win32'
      ? 'C:\\Program Files (x86)\\cloudflared\\cloudflared.exe'
      : undefined,
    process.platform === 'win32'
      ? 'C:\\Program Files\\cloudflared\\cloudflared.exe'
      : undefined
  ].filter((candidate): candidate is string => Boolean(candidate));

  for (const candidate of candidates) {
    if (path.isAbsolute(candidate) && !existsSync(candidate)) continue;
    const probe = spawnSync(candidate, ['--version'], { stdio: 'ignore' });
    if (!probe.error && probe.status === 0) return candidate;
  }
  throw new Error('cloudflared was not found. Install it or set CLOUDFLARED_BIN.');
}

function resolveNgrok(): string {
  const localAppData = process.env.LOCALAPPDATA?.trim();
  const candidates = [
    process.env.NGROK_BIN?.trim(),
    'ngrok',
    localAppData
      ? path.join(localAppData, 'Microsoft', 'WinGet', 'Links', 'ngrok.exe')
      : undefined,
    localAppData
      ? path.join(
          localAppData,
          'Microsoft',
          'WinGet',
          'Packages',
          'Ngrok.Ngrok_Microsoft.Winget.Source_8wekyb3d8bbwe',
          'ngrok.exe'
        )
      : undefined
  ].filter((candidate): candidate is string => Boolean(candidate));

  for (const candidate of candidates) {
    if (path.isAbsolute(candidate) && !existsSync(candidate)) continue;
    const probe = spawnSync(candidate, ['version'], { stdio: 'ignore' });
    if (!probe.error && probe.status === 0) return candidate;
  }
  throw new Error('ngrok was not found. Install it or set NGROK_BIN.');
}

export function withoutProxyEnvironment(environment: NodeJS.ProcessEnv): NodeJS.ProcessEnv {
  const sanitized = { ...environment };
  for (const key of ['HTTP_PROXY', 'HTTPS_PROXY', 'ALL_PROXY']) delete sanitized[key];
  return sanitized;
}

function startChild(
  name: string,
  command: string,
  args: string[],
  pipeOutput = false,
  environment: NodeJS.ProcessEnv = process.env
): ChildProcess {
  const child = spawn(command, args, {
    cwd: root,
    env: environment,
    stdio: pipeOutput ? ['ignore', 'pipe', 'pipe'] : ['ignore', 'inherit', 'inherit']
  });
  children.set(child, name);
  child.once('exit', (code, signal) => {
    children.delete(child);
    if (!shuttingDown) {
      console.error(`${name} stopped unexpectedly (${signal ?? code ?? 'unknown'}).`);
      void shutdown(1);
    }
  });
  return child;
}

async function waitForReady(url: string, timeoutMs = 45_000): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  let lastError = 'not ready';
  while (Date.now() < deadline) {
    try {
      const response = await fetch(url, { signal: AbortSignal.timeout(5_000) });
      if (response.ok) return;
      lastError = `HTTP ${response.status}`;
    } catch (error) {
      lastError = error instanceof Error ? error.message : String(error);
    }
    await new Promise((resolve) => setTimeout(resolve, 500));
  }
  throw new Error(`Readiness check timed out for ${url}: ${lastError}`);
}

function waitForTunnelUrl(child: ChildProcess, timeoutMs = 45_000): Promise<string> {
  return new Promise((resolve, reject) => {
    let buffer = '';
    const timeout = setTimeout(() => reject(new Error('Timed out waiting for the quick Tunnel URL.')), timeoutMs);
    const consume = (chunk: Buffer) => {
      const text = chunk.toString('utf8');
      process.stdout.write(`[tunnel] ${text}`);
      buffer = `${buffer}${text}`.slice(-16_384);
      const url = extractQuickTunnelUrl(buffer);
      if (url) {
        clearTimeout(timeout);
        resolve(url);
      }
    };
    child.stdout?.on('data', consume);
    child.stderr?.on('data', consume);
    child.once('error', (error) => {
      clearTimeout(timeout);
      reject(error);
    });
  });
}

async function shutdown(exitCode: number): Promise<void> {
  if (shuttingDown) return;
  shuttingDown = true;
  for (const [child, name] of children) {
    console.log(`Stopping ${name}...`);
    child.kill();
  }
  await new Promise((resolve) => setTimeout(resolve, 250));
  process.exitCode = exitCode;
}

async function main(): Promise<void> {
  const config = loadConfig();
  if (!config.GITHUB_APP_ID || !config.GITHUB_PRIVATE_KEY) {
    throw new Error('GITHUB_APP_ID and GITHUB_PRIVATE_KEY are required for local beta startup.');
  }

  if (process.env.CODELENS_SKIP_DOCKER_DEPENDENCIES !== '1') {
    runChecked(
      'docker',
      ['compose', '--env-file', '.env', '-f', 'infra/compose.yml', 'up', '-d'],
      'Docker dependencies'
    );
  }
  runChecked(process.execPath, ['--import', 'tsx', 'scripts/migrate.ts'], 'Database migration');

  const api = startChild('API', process.execPath, ['--import', 'tsx', 'apps/api/src/main.ts']);
  const localReadyUrl = `http://127.0.0.1:${config.PORT}/readyz`;
  await waitForReady(localReadyUrl);
  startChild('worker', process.execPath, ['--import', 'tsx', 'apps/worker/src/main.ts']);

  const tunnelProvider = process.env.CODELENS_TUNNEL_PROVIDER?.trim().toLowerCase() || 'cloudflare';
  if (!['cloudflare', 'ngrok'].includes(tunnelProvider)) {
    throw new Error('CODELENS_TUNNEL_PROVIDER must be cloudflare or ngrok.');
  }

  const configuredPublicBaseUrl = process.env.CODELENS_PUBLIC_BASE_URL?.trim();
  const configuredTunnelToken = process.env.CLOUDFLARE_TUNNEL_TOKEN?.trim();
  if (tunnelProvider === 'cloudflare' && Boolean(configuredPublicBaseUrl) !== Boolean(configuredTunnelToken)) {
    throw new Error(
      'CODELENS_PUBLIC_BASE_URL and CLOUDFLARE_TUNNEL_TOKEN must be configured together.'
    );
  }
  if (tunnelProvider === 'ngrok' && !configuredPublicBaseUrl) {
    throw new Error('CODELENS_PUBLIC_BASE_URL is required when CODELENS_TUNNEL_PROVIDER=ngrok.');
  }
  if (tunnelProvider === 'ngrok' && configuredTunnelToken) {
    throw new Error('CLOUDFLARE_TUNNEL_TOKEN cannot be used with CODELENS_TUNNEL_PROVIDER=ngrok.');
  }

  let tunnelUrl: string;
  let mode: 'local-beta-fixed-cloudflare' | 'local-beta-fixed-ngrok' | 'local-beta-quick';
  if (tunnelProvider === 'ngrok') {
    tunnelUrl = normalizePublicBaseUrl(configuredPublicBaseUrl!);
    mode = 'local-beta-fixed-ngrok';
    const ngrok = resolveNgrok();
    startChild(
      'ngrok Fixed Tunnel',
      ngrok,
      ['http', String(config.PORT), `--url=${tunnelUrl}`, '--log', 'stdout'],
      true,
      withoutProxyEnvironment(process.env)
    );
  } else if (configuredPublicBaseUrl && configuredTunnelToken) {
    tunnelUrl = normalizePublicBaseUrl(configuredPublicBaseUrl);
    mode = 'local-beta-fixed-cloudflare';
    const cloudflared = resolveCloudflared();
    startChild(
      'Cloudflare Named Tunnel',
      cloudflared,
      ['tunnel', '--no-autoupdate', '--protocol', 'http2', 'run'],
      true,
      { ...process.env, TUNNEL_TOKEN: configuredTunnelToken }
    );
  } else {
    mode = 'local-beta-quick';
    const cloudflared = resolveCloudflared();
    const tunnel = startChild(
      'Cloudflare Quick Tunnel',
      cloudflared,
      [
        'tunnel', '--no-autoupdate', '--protocol', 'http2',
        '--url', `http://127.0.0.1:${config.PORT}`
      ],
      true
    );
    tunnelUrl = await waitForTunnelUrl(tunnel);
  }
  await waitForReady(`${tunnelUrl}/readyz`, 90_000);

  const webhookUrl = buildWebhookUrl(tunnelUrl);
  const github = new App({ appId: config.GITHUB_APP_ID, privateKey: config.GITHUB_PRIVATE_KEY });
  const appAuthentication = await github.octokit.auth({ type: 'app' }) as AppAuthentication;
  const githubHeaders = {
    accept: 'application/vnd.github+json',
    authorization: `Bearer ${appAuthentication.token}`,
    'content-type': 'application/json',
    'user-agent': 'codelens-ai-local-beta',
    'x-github-api-version': '2022-11-28'
  };
  const updateResponse = await fetch('https://api.github.com/app/hook/config', {
    method: 'PATCH',
    headers: githubHeaders,
    body: JSON.stringify({
      url: webhookUrl,
      content_type: 'json',
      secret: config.GITHUB_WEBHOOK_SECRET,
      insecure_ssl: '0'
    })
  });
  if (!updateResponse.ok) {
    throw new Error(`GitHub webhook update failed with HTTP ${updateResponse.status}.`);
  }
  const verifyResponse = await fetch('https://api.github.com/app/hook/config', {
    headers: githubHeaders
  });
  if (!verifyResponse.ok) {
    throw new Error(`GitHub webhook verification failed with HTTP ${verifyResponse.status}.`);
  }
  const verified = await verifyResponse.json() as { url?: string };
  if (verified.url !== webhookUrl) {
    throw new Error('GitHub App returned a different webhook URL after configuration.');
  }

  console.log(JSON.stringify({
    status: 'ready',
    mode,
    appId: config.GITHUB_APP_ID,
    webhookUrl,
    publicBaseUrl: tunnelUrl,
    localReadyUrl
  }, null, 2));

  // Keep the supervisor alive. A child exit or process signal shuts down the group.
  await new Promise(() => undefined);
  void api;
}

const isEntryPoint = process.argv[1]
  ? path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)
  : false;

if (isEntryPoint) {
  process.once('SIGINT', () => { void shutdown(0); });
  process.once('SIGTERM', () => { void shutdown(0); });
  main().catch(async (error) => {
    console.error(error instanceof Error ? error.message : error);
    await shutdown(1);
  });
}
