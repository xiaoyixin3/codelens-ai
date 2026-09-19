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

function startChild(name: string, command: string, args: string[], pipeOutput = false): ChildProcess {
  const child = spawn(command, args, {
    cwd: root,
    env: process.env,
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

  const cloudflared = resolveCloudflared();
  const tunnel = startChild(
    'Cloudflare Tunnel',
    cloudflared,
    ['tunnel', '--no-autoupdate', '--url', `http://127.0.0.1:${config.PORT}`],
    true
  );
  const tunnelUrl = await waitForTunnelUrl(tunnel);
  await waitForReady(`${tunnelUrl}/readyz`);

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
    mode: 'local-beta',
    appId: config.GITHUB_APP_ID,
    webhookUrl,
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
