import { execFile, spawn, type ChildProcess } from 'node:child_process';
import { promisify } from 'node:util';
import { mkdir, readFile, readdir, writeFile, stat } from 'node:fs/promises';
import path from 'node:path';
import os from 'node:os';
import { createServer } from 'node:net';
import { createHash } from 'node:crypto';
import { setTimeout as delay } from 'node:timers/promises';
import { latencySummary, summarizeLoad, type LoadSample } from './performance-metrics.js';

const run = promisify(execFile);
const overallAbort = new AbortController();
const root = process.cwd();
const requested = process.argv.find(arg => arg.startsWith('--output='))?.slice('--output='.length);
if (!requested) throw new Error('Specify --output=<new independent output directory outside the input repository>');
const output = path.resolve(requested);
if (output === root || output.startsWith(root + path.sep)) throw new Error('Output must be outside input repository');
await mkdir(output, { recursive: true });
if ((await readdir(output)).length) throw new Error('Output directory must be empty; preserve prior results');
const safeEnv: NodeJS.ProcessEnv = {};
for (const name of ['PATH', 'Path', 'SystemRoot', 'WINDIR', 'TEMP', 'TMP', 'USERPROFILE', 'JAVA_HOME', 'PATHEXT', 'COMSPEC']) {
  if (process.env[name]) safeEnv[name] = process.env[name];
}
const git = async (repository: string, ...args: string[]) => (await run('git', ['-c', 'core.hooksPath=', '-c', 'maintenance.auto=false',
  '-c', 'gc.auto=0', '-C', repository, ...args], { timeout: 30_000, maxBuffer: 8 * 1024 * 1024, encoding: 'utf8', env: safeEnv, signal: overallAbort.signal })).stdout.trim();
const sha256 = (value: Buffer | string) => createHash('sha256').update(value).digest('hex');
const started = performance.now();
let child: ChildProcess | undefined;
let serverDiagnostics = '';
const overallTimer = setTimeout(() => { overallAbort.abort(); child?.kill(); console.error('Performance run exceeded 12-minute budget.'); process.exitCode = 1; }, 12 * 60_000);

async function fixture(name: string, totalJavaFiles: number) {
  const repository = path.join(output, name);
  const source = path.join(repository, 'src/main/java/perf');
  const tests = path.join(repository, 'src/test/java/perf');
  await mkdir(source, { recursive: true }); await mkdir(tests, { recursive: true });
  await writeFile(path.join(repository, 'pom.xml'), '<project><modelVersion>4.0.0</modelVersion><groupId>fixture</groupId><artifactId>performance</artifactId><version>1</version></project>\n');
  const rule = path.join(source, 'Rule.java');
  await writeFile(rule, 'package perf; public final class Rule { public int apply(int value) { return value; } }\n');
  for (let i = 0; i < 8; i++) await writeFile(path.join(source, `Caller${i}.java`),
    `package perf; public final class Caller${i} { public int invoke(int value) { return new Rule().apply(value); } }\n`);
  for (let i = 0; i < totalJavaFiles - 10; i++) await writeFile(path.join(source, `Independent${i}.java`),
    `package perf; public final class Independent${i} { public int first(int value) { return value + ${i}; } public int second(int value) { return first(value); } }\n`);
  await writeFile(path.join(tests, 'RuleTest.java'), 'package perf; public final class RuleTest { public void negativeBoundary() { new Rule().apply(-1); } }\n');
  await git(repository, 'init', '--quiet');
  await git(repository, 'config', 'user.name', 'CodeLens Performance Fixture');
  await git(repository, 'config', 'user.email', 'fixture@invalid.example');
  // The identity and dates belong to generated fixtures, never to the user's actual history.
  const commit = async (message: string, date: string) => run('git', ['-c', 'core.hooksPath=', '-c', 'commit.gpgsign=false', '-C', repository,
    'commit', '--quiet', '-m', message], { timeout: 30_000, env: { ...safeEnv, GIT_AUTHOR_DATE: date, GIT_COMMITTER_DATE: date } });
  await git(repository, 'add', '.'); await commit('generated controlled Java baseline', '2000-01-01T00:00:00Z');
  const base = await git(repository, 'rev-parse', 'HEAD');
  await writeFile(rule, 'package perf; public final class Rule { public int apply(int value) { return value < 0 ? 0 : value; } }\n');
  await git(repository, 'add', '.'); await commit('controlled boundary change, unchanged callers', '2000-01-02T00:00:00Z');
  return { repository, base, head: await git(repository, 'rev-parse', 'HEAD'), totalJavaFiles, kind: 'synthetic-engineering-fixture' };
}

const emptyPort = () => new Promise<number>((resolve, reject) => {
  const server = createServer(); server.on('error', reject); server.listen(0, '127.0.0.1', () => {
    const address = server.address(); if (!address || typeof address === 'string') return reject(new Error('No port'));
    server.close(() => resolve(address.port));
  });
});

try {
  const jar = path.join(root, 'target/codelens-ai.jar');
  const sourcePaths = ['src/main/java/ai/codelens/semantic/JavaSemanticAdapter.java', 'src/main/java/ai/codelens/semantic/SemanticIndexService.java',
    'scripts/local-review.ts', 'scripts/benchmark-labeler.ts', 'scripts/performance-evidence.ts',
    'src/test/java/ai/codelens/semantic/SemanticPerformanceProbe.java'];
  const sourceHashes = Object.fromEntries(await Promise.all(sourcePaths.map(async file => [file, sha256(await readFile(path.join(root, file)))])));
  const metadata = { protocol: 'java-performance-evidence-v1', clientDate: '2026-10-07', hostTimestampUtc: new Date().toISOString(),
    cpu: os.cpus()[0]?.model, logicalProcessors: os.cpus().length, totalMemoryBytes: os.totalmem(), freeMemoryAtStartBytes: os.freemem(),
    os: { platform: os.platform(), release: os.release(), architecture: os.arch() }, node: process.version,
    java: (await run('java', ['-version'], { env: safeEnv, timeout: 15_000 })).stderr.trim(),
    jarSha256: sha256(await readFile(jar)), jarModified: (await stat(jar)).mtime.toISOString(),
    gitHead: await git(root, 'rev-parse', 'HEAD'), dirty: Boolean(await git(root, 'status', '--porcelain')), sourceHashes,
    exclusions: ['no LLM', 'no public endpoint load', 'no production database', 'no repository code/build/test execution', 'no sealed holdout access'] };
  await writeFile(path.join(output, 'environment.json'), JSON.stringify(metadata, null, 2));
  const synthetic = await fixture('controlled-java-250', 250);
  const httpFixture = await fixture('controlled-java-64', 64);
  const real = { repository: root, base: await git(root, 'rev-parse', 'HEAD^'), head: metadata.gitHead, kind: 'real-codelens-committed-source' };
  await writeFile(path.join(output, 'manifest.json'), JSON.stringify({ metadata, cases: [real, synthetic, httpFixture] }, null, 2));
  const semanticReports = [];
  for (const [name, subject] of [['codelens', real], ['controlled-250', synthetic]] as const) {
    const file = path.join(output, `semantic-${name}.json`);
    console.log(`Measuring production adapter: ${name}`);
    let measured;
    try {
      measured = await run('java', ['-Xmx512m', `-Dloader.path=${path.join(root, 'target/test-classes')}`,
        '-Dloader.main=ai.codelens.semantic.SemanticPerformanceProbe', '-cp', jar, 'org.springframework.boot.loader.launch.PropertiesLauncher',
        subject.repository, subject.base, subject.head, '5', file], { cwd: root, env: safeEnv, timeout: 300_000, maxBuffer: 2 * 1024 * 1024, encoding: 'utf8', signal: overallAbort.signal });
    } catch (error) {
      const failed = error as Error & { stdout?: string; stderr?: string; code?: string | number; killed?: boolean; signal?: string };
      await writeFile(path.join(output, `semantic-${name}.log`), String(failed.stdout ?? '') + String(failed.stderr ?? ''));
      await writeFile(path.join(output, 'failure.json'), JSON.stringify({ passed: false, complete: false, stage: `semantic-${name}`,
        error: failed.message, code: failed.code, killed: failed.killed, signal: failed.signal, totalWallMs: performance.now() - started }, null, 2));
      throw error;
    }
    await writeFile(path.join(output, `semantic-${name}.log`), measured.stdout + measured.stderr);
    const report = JSON.parse(await readFile(file, 'utf8'));
    semanticReports.push({ name, ...report });
    console.log(measured.stdout.trim());
  }
  const port = await emptyPort();
  const url = `http://127.0.0.1:${port}`;
  child = spawn(process.execPath, ['--import', 'tsx', 'scripts/benchmark-labeler.ts', '--local', '--mode=assisted'],
    { cwd: root, env: { ...safeEnv, BENCHMARK_LABELER_PORT: String(port) }, stdio: ['ignore', 'pipe', 'pipe'], windowsHide: true });
  child.stdout?.on('data', data => { serverDiagnostics += String(data); });
  child.stderr?.on('data', data => { serverDiagnostics += String(data); });
  child.on('error', error => { serverDiagnostics += String(error); });
  const startupDeadline = performance.now() + 10_000;
  let ready = false;
  while (performance.now() < startupDeadline) {
    if (child.exitCode !== null) throw new Error('Independent workbench exited');
    try {
      const response = await fetch(url + '/api/state', { signal: AbortSignal.timeout(500) });
      const state = await response.json() as { localMode?: boolean; protocol?: string };
      if (response.ok && state.localMode === true && state.protocol === 'reasoning-v1') { ready = true; break; }
    } catch { /* startup */ }
    await delay(50);
  }
  if (!ready) throw new Error('Independent workbench readiness deadline exceeded; see archived workbench.log');
  const requestReview = async (): Promise<LoadSample> => {
    const before = performance.now();
    try {
      const response = await fetch(url + '/api/local/review', { method: 'POST', headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ repository: httpFixture.repository, base: httpFixture.base, head: httpFixture.head }), signal: AbortSignal.any([AbortSignal.timeout(130_000), overallAbort.signal]) });
      const payload = await response.json() as { id?: string; error?: string };
      if (response.status === 200 && !payload.id?.startsWith('local-')) throw new Error('Success without local review ID');
      return { status: response.status, ms: performance.now() - before };
    } catch { return { status: 0, ms: performance.now() - before }; }
  };
  const warmup = await requestReview();
  if (warmup.status !== 200) throw new Error('HTTP warmup failed');
  await writeFile(path.join(output, 'http-warmup.json'), JSON.stringify(warmup, null, 2));
  const groups = [];
  for (const concurrency of [1, 2, 4, 8]) {
    const rows: Array<LoadSample & { wave: number; position: number }> = [];
    const healthRows: LoadSample[] = [];
    const start = performance.now();
    for (let wave = 0; wave < 10; wave++) {
      overallAbort.signal.throwIfAborted();
      const requests = Array.from({ length: concurrency }, async (_, position) => ({ ...await requestReview(), wave: wave + 1, position }));
      const health = (async () => {
        await delay(20); const before = performance.now();
        try { const response = await fetch(url + '/api/state', { signal: AbortSignal.timeout(5000) }); await response.arrayBuffer();
          return { status: response.status, ms: performance.now() - before }; }
        catch { return { status: 0, ms: performance.now() - before }; }
      })();
      rows.push(...await Promise.all(requests)); healthRows.push(await health);
    }
    const group = { concurrency, waves: 10, ...summarizeLoad(rows, performance.now() - start), stateReadLatency: latencySummary(healthRows.map(row => row.ms)), healthFailures: healthRows.filter(row => row.status !== 200).length, rows, healthRows };
    groups.push(group);
    await writeFile(path.join(output, `http-concurrency-${concurrency}.json`), JSON.stringify(group, null, 2));
    console.log(`HTTP concurrency=${concurrency}: ${group.completed} completed, ${group.rejected} protected, ${group.failures} failed; accepted P50=${group.acceptedLatency.p50Ms?.toFixed(1)}ms`);
  }
  const protections = [];
  for (const route of ['/api/export', '/api/decisions/performance-fixture']) {
    const response = await fetch(url + route, { method: route.includes('decisions') ? 'PUT' : 'POST', headers: { 'content-type': 'application/json' }, body: '{}' });
    protections.push({ route, expected: 403, actual: response.status });
  }
  const hostile = await fetch(url + '/api/state', { headers: { origin: 'https://untrusted.invalid' } });
  protections.push({ route: 'hostile-origin', expected: 403, actual: hostile.status });
  const invalid = await fetch(url + '/api/local/review', { method: 'POST', headers: { 'content-type': 'application/json' }, body: '{}' });
  protections.push({ route: 'invalid-input', expected: 400, actual: invalid.status });
  const passed = semanticReports.every(report => report.complete === true && report.samples.length === 5
    && report.samples.every((row: { equal: boolean }) => row.equal))
    && groups.every(group => group.failures === 0 && group.healthFailures === 0 && group.completed === group.waves)
    && protections.every(check => check.actual === check.expected);
  await writeFile(path.join(output, 'summary.json'), JSON.stringify({ passed, metadata, semanticReports, http: { fixture: httpFixture, warmup, groups, protections },
    totalWallMs: performance.now() - started, databaseLoad: { executed: false, reason: 'Docker engine unavailable; existing services not restarted' },
    productQuality: { measured: false, reason: 'No independent labels or human Review-time experiment' } }, null, 2));
  if (!passed) process.exitCode = 1;
  console.log(`Evidence archived: ${output}; passed=${passed}`);
} finally {
  clearTimeout(overallTimer);
  child?.kill();
  if (child && child.exitCode === null) await Promise.race([new Promise<void>(resolve => child!.once('exit', () => resolve())), delay(5000)]);
  await writeFile(path.join(output, 'workbench.log'), serverDiagnostics);
}
