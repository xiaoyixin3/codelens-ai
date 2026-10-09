import { afterEach, describe, expect, it } from 'vitest';
import { spawn, type ChildProcess } from 'node:child_process';
import { createServer } from 'node:net';
import { request } from 'node:http';
import { setTimeout as delay } from 'node:timers/promises';

const children: ChildProcess[] = [];
afterEach(() => { children.splice(0).forEach(child => child.kill()); });

describe('local workbench isolation', () => {
  it('starts without benchmark files or secrets and prevents label writes and cross-origin use', async () => {
    const port = await new Promise<number>((resolve, reject) => {
      const server = createServer(); server.on('error', reject); server.listen(0, '127.0.0.1', () => {
        const address = server.address(); if (!address || typeof address === 'string') return reject(new Error('No TCP port'));
        server.close(() => resolve(address.port));
      });
    });
    const safeEnv: NodeJS.ProcessEnv = {};
    for (const name of ['PATH', 'Path', 'SystemRoot', 'WINDIR', 'TEMP', 'TMP', 'USERPROFILE', 'PATHEXT', 'COMSPEC']) {
      if (process.env[name]) safeEnv[name] = process.env[name];
    }
    const child = spawn(process.execPath, ['--import', 'tsx', 'scripts/benchmark-labeler.ts', '--local', '--mode=assisted',
      '--input=missing-local-benchmark.jsonl', '--context-dir=missing-local-context', '--decisions=missing-local-decisions.json'],
    { cwd: process.cwd(), env: { ...safeEnv, BENCHMARK_LABELER_PORT: String(port) }, stdio: ['ignore', 'pipe', 'pipe'], windowsHide: true });
    children.push(child); let diagnostics = ''; let startupError: Error | undefined;
    child.stdout?.on('data', chunk => { diagnostics += String(chunk); });
    child.stderr?.on('data', chunk => { diagnostics += String(chunk); });
    child.on('error', error => { startupError = error; });
    const url = `http://127.0.0.1:${port}`;
    const deadline = performance.now() + 10_000;
    let state: any;
    let lastProbe = 'not attempted';
    while (performance.now() < deadline) {
      if (startupError || child.exitCode !== null || child.signalCode !== null) {
        throw new Error(`Workbench exited before readiness: ${startupError?.message ?? child.exitCode ?? child.signalCode}; ${diagnostics.slice(-2000)}`);
      }
      try {
        const response = await fetch(`${url}/api/state`, { signal: AbortSignal.timeout(500) });
        const candidate = await response.json() as any;
        if (response.ok && candidate.localMode === true && candidate.protocol === 'reasoning-v1') { state = candidate; break; }
        lastProbe = `unexpected response ${response.status}`;
      } catch (error) { lastProbe = error instanceof Error ? error.message : String(error); }
      await delay(50);
    }
    if (!state) throw new Error(`Workbench readiness deadline exceeded: ${lastProbe}; ${diagnostics.slice(-2000)}`);
    expect(state).toMatchObject({ localMode: true, cases: [], summary: { total: 0 } });
    for (const route of ['/api/export', '/api/decisions/local-test']) {
      const response = await fetch(`${url}${route}`, { method: route.includes('decisions') ? 'PUT' : 'POST', headers: { 'content-type': 'application/json' }, body: '{}' });
      expect(response.status).toBe(403);
    }
    expect((await fetch(`${url}/api/local/review`, { method: 'POST', headers: { 'content-type': 'application/json' }, body: '{}' })).status).toBe(400);
    expect((await fetch(`${url}/api/state`, { headers: { origin: 'https://untrusted.example' } })).status).toBe(403);
    // Node fetch rewrites Host; use a raw HTTP request to actually send the hostile header.
    const hostileStatus = await new Promise<number | undefined>((resolve, reject) => {
      const call = request(`${url}/api/state`, { headers: { host: `untrusted.example:${port}` } }, response => {
        response.resume(); resolve(response.statusCode);
      }); call.on('error', reject); call.end();
    });
    expect(hostileStatus).toBe(403);
    const page = await fetch(url); expect(page.headers.get('content-security-policy')).toContain("frame-ancestors 'none'");
    expect(await page.text()).toContain('CodeLens');
  }, 20000);
});
