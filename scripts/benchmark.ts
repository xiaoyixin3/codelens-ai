import { readFile } from 'node:fs/promises';
import path from 'node:path';
import {
  ReplayCaseSchema,
  evaluateBenchmarkGate,
  isApprovedHistoricalReplayCase,
  runBenchmark,
  type BenchmarkThresholds,
  type ReplayCase
} from '@codelens/evaluation';

const args = process.argv.slice(2);
const gateEnabled = args.includes('--gate');
const datasetPath = path.resolve(args.find((arg) => !arg.startsWith('--')) ?? 'benchmarks/sample-replay.jsonl');
const source = await readFile(datasetPath, 'utf8');
const cases = source
  .split(/\r?\n/)
  .filter((line) => line.trim())
  .map((line, index) => {
    try { return ReplayCaseSchema.parse(JSON.parse(line)) as ReplayCase; }
    catch (error) {
      throw new Error(`Invalid JSONL at line ${index + 1}: ${error instanceof Error ? error.message : String(error)}`);
    }
  });
const duplicateIds = cases
  .map((item) => item.id)
  .filter((id, index, all) => all.indexOf(id) !== index);
if (duplicateIds.length) {
  throw new Error(`Duplicate benchmark case IDs: ${[...new Set(duplicateIds)].join(', ')}`);
}

function readNumber(name: string, fallback: number): number {
  const raw = process.env[name];
  if (!raw) return fallback;
  const value = Number(raw);
  if (!Number.isFinite(value)) throw new Error(`${name} must be numeric.`);
  return value;
}

const thresholds: BenchmarkThresholds = {
  minCases: readNumber('BENCHMARK_MIN_CASES', 100),
  minPositiveCases: readNumber('BENCHMARK_MIN_POSITIVE_CASES', 20),
  minNegativeCases: readNumber('BENCHMARK_MIN_NEGATIVE_CASES', 20),
  minPrecision: readNumber('BENCHMARK_MIN_PRECISION', 0.8),
  minRecall: readNumber('BENCHMARK_MIN_RECALL', 0.7),
  maxP95LatencyMs: readNumber('BENCHMARK_MAX_P95_MS', 1_000)
};
const approvedCases = cases.filter(isApprovedHistoricalReplayCase);
const evaluatedCases = gateEnabled ? approvedCases : cases;
const report = await runBenchmark(evaluatedCases);
const gate = evaluateBenchmarkGate(report, thresholds);
console.log(JSON.stringify({
  dataset: datasetPath,
  loadedCases: cases.length,
  approvedHistoricalCases: approvedCases.length,
  excludedFromGate: gateEnabled ? cases.length - approvedCases.length : 0,
  ...report,
  gate
}, null, 2));
if (gateEnabled && !gate.passed) process.exitCode = 1;
