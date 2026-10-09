import { describe, expect, it } from 'vitest';
import { latencySummary, summarizeLoad } from '../scripts/performance-metrics.js';

describe('performance evidence accounting', () => {
  it('never mixes fast overload rejections with completed review latency or throughput', () => {
    const result = summarizeLoad([{ status: 200, ms: 2000 }, { status: 409, ms: 2 }, { status: 500, ms: 5 }], 2500);
    expect(result.completed).toBe(1); expect(result.rejected).toBe(1); expect(result.failures).toBe(1);
    expect(result.acceptedLatency.p50Ms).toBe(2000);
    expect(result.rejectedLatency.p50Ms).toBe(2);
    expect(result.successfulReviewsPerSecond).toBe(0.4);
  });
  it('uses nearest-rank percentiles and exposes sample size', () => {
    expect(latencySummary(Array.from({ length: 100 }, (_, i) => i + 1))).toEqual({ samples: 100, p50Ms: 50, p95Ms: 95, maxMs: 100 });
    expect(latencySummary([])).toEqual({ samples: 0, p50Ms: null, p95Ms: null, maxMs: null });
    expect(() => latencySummary([NaN])).toThrow();
    expect(() => summarizeLoad([], 0)).toThrow();
  });
});
