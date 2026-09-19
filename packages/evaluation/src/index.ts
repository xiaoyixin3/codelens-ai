import { z } from 'zod';
import { PullRequestContextSchema, type PullRequestContext } from '@codelens/contracts';
import { DeterministicRiskReviewer, DiffMap, EvidenceVerifier } from '@codelens/risk-review';

export interface ExpectedFinding {
  ruleId: string;
  path: string;
  line: number;
}

export interface ReplayCase {
  id: string;
  context: PullRequestContext;
  expectedFindings: ExpectedFinding[];
}

export const ReplayCaseSchema = z.object({
  id: z.string().trim().min(1),
  context: PullRequestContextSchema,
  expectedFindings: z.array(z.object({
    ruleId: z.string().trim().min(1),
    path: z.string().trim().min(1),
    line: z.number().int().positive()
  }))
});

export interface BenchmarkReport {
  cases: number;
  expected: number;
  predicted: number;
  truePositive: number;
  falsePositive: number;
  falseNegative: number;
  precision: number;
  recall: number;
  latencyMs: { p50: number; p95: number; max: number };
  insufficientSampleWarning?: string;
}

export interface BenchmarkThresholds {
  minCases: number;
  minPrecision: number;
  minRecall: number;
  maxP95LatencyMs: number;
}

export interface BenchmarkGateResult {
  passed: boolean;
  failures: string[];
  thresholds: BenchmarkThresholds;
}

export interface BetaRolloutCounts {
  completed: number;
  failed: number;
  stale: number;
  skipped: number;
  queued: number;
  inProgress: number;
}

export interface BetaReadinessThresholds {
  windowDays: number;
  minEligibleReviews: number;
  minSuccessRate: number;
}

export interface BetaReadinessReport {
  ready: boolean;
  eligibleReviews: number;
  successRate: number;
  counts: BetaRolloutCounts;
  thresholds: BetaReadinessThresholds;
  failures: string[];
}

export function evaluateBetaReadiness(
  counts: BetaRolloutCounts,
  thresholds: BetaReadinessThresholds
): BetaReadinessReport {
  if (!Number.isInteger(thresholds.windowDays) || thresholds.windowDays <= 0) {
    throw new Error('Beta observation window must be a positive integer.');
  }
  if (!Number.isInteger(thresholds.minEligibleReviews) || thresholds.minEligibleReviews <= 0) {
    throw new Error('Beta minimum eligible reviews must be a positive integer.');
  }
  if (!Number.isFinite(thresholds.minSuccessRate) || thresholds.minSuccessRate < 0 || thresholds.minSuccessRate > 1) {
    throw new Error('Beta minimum success rate must be between 0 and 1.');
  }
  for (const [status, count] of Object.entries(counts)) {
    if (!Number.isInteger(count) || count < 0) throw new Error(`Beta ${status} count must be a nonnegative integer.`);
  }

  const eligibleReviews = counts.completed + counts.failed;
  const successRate = eligibleReviews ? counts.completed / eligibleReviews : 0;
  const failures: string[] = [];
  if (eligibleReviews < thresholds.minEligibleReviews) {
    failures.push(`eligible reviews ${eligibleReviews} < ${thresholds.minEligibleReviews}`);
  }
  if (successRate < thresholds.minSuccessRate) {
    failures.push(`success rate ${successRate.toFixed(4)} < ${thresholds.minSuccessRate.toFixed(4)}`);
  }
  return {
    ready: failures.length === 0,
    eligibleReviews,
    successRate,
    counts,
    thresholds,
    failures
  };
}

export function evaluateBenchmarkGate(
  report: BenchmarkReport,
  thresholds: BenchmarkThresholds
): BenchmarkGateResult {
  if (!Number.isInteger(thresholds.minCases) || thresholds.minCases <= 0) {
    throw new Error('Benchmark minimum case count must be a positive integer.');
  }
  for (const [name, value] of [
    ['minimum precision', thresholds.minPrecision],
    ['minimum recall', thresholds.minRecall]
  ] as const) {
    if (!Number.isFinite(value) || value < 0 || value > 1) {
      throw new Error(`Benchmark ${name} must be between 0 and 1.`);
    }
  }
  if (!Number.isFinite(thresholds.maxP95LatencyMs) || thresholds.maxP95LatencyMs <= 0) {
    throw new Error('Benchmark maximum p95 latency must be positive.');
  }

  const failures: string[] = [];
  if (report.cases < thresholds.minCases) {
    failures.push(`cases ${report.cases} < ${thresholds.minCases}`);
  }
  if (report.precision < thresholds.minPrecision) {
    failures.push(`precision ${report.precision.toFixed(4)} < ${thresholds.minPrecision.toFixed(4)}`);
  }
  if (report.recall < thresholds.minRecall) {
    failures.push(`recall ${report.recall.toFixed(4)} < ${thresholds.minRecall.toFixed(4)}`);
  }
  if (report.latencyMs.p95 > thresholds.maxP95LatencyMs) {
    failures.push(`p95 latency ${report.latencyMs.p95}ms > ${thresholds.maxP95LatencyMs}ms`);
  }
  return { passed: failures.length === 0, failures, thresholds };
}

function key(value: { ruleId?: string; path: string; line: number }): string {
  return `${value.ruleId ?? 'unknown'}|${value.path}|${value.line}`;
}

function percentile(sorted: number[], percent: number): number {
  if (!sorted.length) return 0;
  return sorted[Math.min(sorted.length - 1, Math.ceil(sorted.length * percent) - 1)] ?? 0;
}

export async function runBenchmark(cases: ReplayCase[]): Promise<BenchmarkReport> {
  const reviewer = new DeterministicRiskReviewer();
  const verifier = new EvidenceVerifier({ maxPublished: 100 });
  let expected = 0;
  let predicted = 0;
  let truePositive = 0;
  const latency: number[] = [];

  for (const item of cases) {
    const started = performance.now();
    const diff = new DiffMap(item.context.files);
    const findings = verifier.verify(await reviewer.review(item.context, diff), diff)
      .filter((finding) => finding.status === 'verified');
    latency.push(performance.now() - started);
    const expectedKeys = new Set(item.expectedFindings.map(key));
    const predictedKeys = new Set(findings.map(key));
    expected += expectedKeys.size;
    predicted += predictedKeys.size;
    truePositive += [...predictedKeys].filter((value) => expectedKeys.has(value)).length;
  }

  const falsePositive = predicted - truePositive;
  const falseNegative = expected - truePositive;
  const sorted = latency.sort((left, right) => left - right);
  return {
    cases: cases.length,
    expected,
    predicted,
    truePositive,
    falsePositive,
    falseNegative,
    precision: predicted ? truePositive / predicted : expected ? 0 : 1,
    recall: expected ? truePositive / expected : 1,
    latencyMs: {
      p50: Number(percentile(sorted, 0.5).toFixed(2)),
      p95: Number(percentile(sorted, 0.95).toFixed(2)),
      max: Number((sorted.at(-1) ?? 0).toFixed(2))
    },
    ...(cases.length < 100
      ? { insufficientSampleWarning: `Only ${cases.length}/100 historical PR cases are loaded.` }
      : {})
  };
}
