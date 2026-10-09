export function latencySummary(values: number[]) {
  if (!values.length) return { samples: 0, p50Ms: null, p95Ms: null, maxMs: null };
  if (values.some(value => !Number.isFinite(value) || value < 0)) throw new Error('Invalid latency');
  const sorted = [...values].sort((a, b) => a - b);
  const percentile = (p: number) => sorted[Math.max(0, Math.ceil(sorted.length * p) - 1)]!;
  return { samples: sorted.length, p50Ms: percentile(0.5), p95Ms: percentile(0.95), maxMs: sorted.at(-1)! };
}

export interface LoadSample { status: number; ms: number }
export function summarizeLoad(samples: LoadSample[], wallMs: number) {
  if (!Number.isFinite(wallMs) || wallMs <= 0) throw new Error('Invalid wall time');
  const accepted = samples.filter(row => row.status === 200);
  const rejected = samples.filter(row => row.status === 409);
  const failures = samples.filter(row => row.status !== 200 && row.status !== 409);
  return { requests: samples.length, wallMs, completed: accepted.length, rejected: rejected.length, failures: failures.length,
    successfulReviewsPerSecond: accepted.length * 1000 / wallMs,
    acceptedLatency: latencySummary(accepted.map(row => row.ms)),
    rejectedLatency: latencySummary(rejected.map(row => row.ms)),
    failureLatency: latencySummary(failures.map(row => row.ms)) };
}
