import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { evaluatePhase0Evidence } from '@codelens/evaluation';

const root = process.cwd();
const args = process.argv.slice(2);
const value = (name: string, fallback: string): string =>
  args.find((argument) => argument.startsWith(`${name}=`))?.slice(name.length + 1) ?? fallback;
const casesPath = path.resolve(root, value('--cases', 'benchmarks/candidates/phase0-root-cause-cases.jsonl'));
const sessionsPath = path.resolve(root, value('--sessions', 'benchmarks/candidates/phase0-reviewer-sessions.jsonl'));

async function jsonLines(file: string): Promise<unknown[]> {
  const input = await readFile(file, 'utf8');
  return input.split(/\r?\n/).filter((line) => line.trim()).map((line, index) => {
    try { return JSON.parse(line) as unknown; }
    catch (error) { throw new Error(`${path.relative(root, file)}:${index + 1}: invalid JSON`, { cause: error }); }
  });
}

const report = evaluatePhase0Evidence(await jsonLines(casesPath), await jsonLines(sessionsPath));
console.log(JSON.stringify({
  status: report.passed ? 'phase0-evidence-sufficient' : 'phase0-evidence-insufficient',
  casesPath: path.relative(root, casesPath).replaceAll('\\', '/'),
  sessionsPath: path.relative(root, sessionsPath).replaceAll('\\', '/'),
  ...report
}, null, 2));
if (!report.passed) process.exitCode = 1;

