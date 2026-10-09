import { access, readdir, stat } from 'node:fs/promises';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { windowsMavenArgs } from './maven-launcher.js';

// Native Node launcher: no PowerShell policy change, GitHub configuration, .env or tunnel.
process.chdir(path.resolve(import.meta.dirname, '..'));
const port = Number(process.env.BENCHMARK_LABELER_PORT ?? '4310');
if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error('Invalid local port.');
const localUrl = `http://127.0.0.1:${port}`;
function openRequestedBrowser() {
  if (process.platform === 'win32' && process.argv.includes('--open')) {
    const browser = spawn('explorer.exe', [localUrl], { windowsHide: true, detached: true, stdio: 'ignore' });
    browser.on('error', () => console.log(`请在浏览器打开 ${localUrl}`)); browser.unref();
  }
}
try {
  const response = await fetch(`${localUrl}/api/state`, { signal: AbortSignal.timeout(1000) });
  const state = await response.json() as { localMode?: boolean; protocol?: string };
  if (response.ok && state.localMode && state.protocol === 'reasoning-v1') {
    console.log(`CodeLens 本地版已经运行：${localUrl}`); openRequestedBrowser(); process.exit(0);
  }
} catch { /* No existing local review listener; start normally. */ }
const jar = 'target/codelens-ai.jar';
async function newer(directory: string, timestamp: number): Promise<boolean> {
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    const file = path.join(directory, entry.name);
    if (entry.isDirectory() ? await newer(file, timestamp) : entry.isFile() && (await stat(file)).mtimeMs > timestamp) return true;
  }
  return false;
}
await access('node_modules/tsx');
const builtAt = await stat(jar).then(info => info.mtimeMs).catch(() => 0);
if (process.argv.includes('--rebuild') || !builtAt || (await stat('pom.xml')).mtimeMs > builtAt || await newer('src/main/java', builtAt)) {
  console.log('正在构建本地 Java 分析器…');
  const child = process.platform === 'win32'
    ? spawn('java', await windowsMavenArgs(process.cwd()), { stdio: 'inherit', windowsHide: true })
    : spawn(process.env.CODELENS_MAVEN ?? 'mvn', ['-q', '-DskipTests', 'package'], { stdio: 'inherit' });
  await new Promise<void>((resolve, reject) => { child.on('error', reject); child.on('exit', code => code === 0 ? resolve() : reject(new Error('Java build failed; check Maven / Java 17+.'))); });
}
process.argv.push('--local', '--mode=assisted');
await import('./benchmark-labeler.js');
openRequestedBrowser();
