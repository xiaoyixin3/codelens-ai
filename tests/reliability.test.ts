import { afterEach, describe, expect, it, vi } from 'vitest';
import { runBenchmark } from '@codelens/evaluation';
import { retentionCutoffs } from '@codelens/lifecycle';
import {
  callCompatibleChat,
  InMemoryLlmBudget,
  InMemoryLlmTelemetryStore
} from '@codelens/llm-runtime';
import { withRetry } from '@codelens/resilience';

afterEach(() => vi.unstubAllGlobals());

describe('retry controls', () => {
  it('retries transient failures with bounded backoff', async () => {
    let attempts = 0;
    const sleeps: number[] = [];
    const value = await withRetry(async () => {
      attempts += 1;
      if (attempts < 3) throw Object.assign(new Error('busy'), { status: 503 });
      return 'ok';
    }, {
      attempts: 3,
      baseDelayMs: 100,
      maxDelayMs: 1_000,
      random: () => 0.5,
      sleep: async (milliseconds) => { sleeps.push(milliseconds); }
    });

    expect(value).toBe('ok');
    expect(attempts).toBe(3);
    expect(sleeps).toEqual([100, 200]);
  });

  it('does not retry a permanent client error', async () => {
    let attempts = 0;
    await expect(withRetry(async () => {
      attempts += 1;
      throw Object.assign(new Error('bad request'), { status: 400 });
    }, { sleep: async () => {} })).rejects.toThrow('bad request');
    expect(attempts).toBe(1);
  });
});

describe('LLM runtime', () => {
  it('redacts prompts, retries providers, and records metadata without raw prompt content', async () => {
    const telemetry = new InMemoryLlmTelemetryStore();
    const bodies: string[] = [];
    let attempts = 0;
    vi.stubGlobal('fetch', vi.fn(async (_input: string | URL | Request, init?: RequestInit) => {
      attempts += 1;
      bodies.push(String(init?.body));
      if (attempts === 1) return new Response('busy', { status: 503 });
      return new Response(JSON.stringify({
        choices: [{ message: { content: '{"ok":true}' } }],
        usage: { prompt_tokens: 12, completion_tokens: 4 }
      }), { status: 200, headers: { 'content-type': 'application/json' } });
    }));

    const result = await callCompatibleChat({
      baseUrl: 'https://model.example/v1',
      apiKey: 'provider-key-not-in-prompt',
      model: 'review-model',
      provider: 'primary',
      task: 'summary',
      reviewRunId: '00000000-0000-4000-8000-000000000001',
      body: { messages: [{ role: 'user', content: 'api_key="sk-live-super-secret-value"' }] },
      telemetry,
      timeoutMs: 1_000
    });

    expect(result.choices?.[0]?.message?.content).toContain('ok');
    expect(attempts).toBe(2);
    expect(bodies.every((body) => !body.includes('sk-live-super-secret-value'))).toBe(true);
    expect(telemetry.calls[0]).toMatchObject({
      provider: 'primary', status: 'succeeded', inputTokens: 12, outputTokens: 4
    });
    expect(JSON.stringify(telemetry.calls[0])).not.toContain('sk-live-super-secret-value');
  });

  it('enforces per-review call budgets before a provider request', async () => {
    const telemetry = new InMemoryLlmTelemetryStore();
    const budget = new InMemoryLlmBudget({ maxCallsPerRun: 1, maxInputCharsPerRun: 10_000 });
    const fetchMock = vi.fn(async () => new Response(JSON.stringify({
      choices: [{ message: { content: '{}' } }]
    }), { status: 200, headers: { 'content-type': 'application/json' } }));
    vi.stubGlobal('fetch', fetchMock);
    const options = {
      baseUrl: 'https://model.example/v1', apiKey: 'key', model: 'review-model',
      task: 'summary' as const, reviewRunId: 'run-budget', body: { messages: [] }, telemetry, budget
    };

    await callCompatibleChat(options);
    await expect(callCompatibleChat(options)).rejects.toThrow('budget exceeded');

    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(telemetry.calls.at(-1)).toMatchObject({ status: 'failed', errorCode: 'LLM_BUDGET_EXCEEDED' });
  });
});

describe('data lifecycle', () => {
  it('computes independent retention cutoffs and rejects unsafe policies', () => {
    const now = new Date('2026-09-19T00:00:00.000Z');
    const cutoffs = retentionCutoffs({
      reviewDays: 90, snapshotDays: 30, webhookDays: 7, llmTelemetryDays: 60
    }, now);

    expect(cutoffs.reviews.toISOString()).toBe('2026-06-21T00:00:00.000Z');
    expect(cutoffs.snapshots.toISOString()).toBe('2026-08-20T00:00:00.000Z');
    expect(() => retentionCutoffs({
      reviewDays: 0, snapshotDays: 30, webhookDays: 7, llmTelemetryDays: 60
    }, now)).toThrow('positive integers');
  });
});

describe('historical replay', () => {
  it('reports precision, recall, latency, and insufficient sample size', async () => {
    const report = await runBenchmark([{
      id: 'async-foreach',
      context: {
        owner: 'sample', repo: 'replay', number: 1, title: 'Jobs', body: '',
        baseSha: 'aaaaaaa', headSha: 'bbbbbbb',
        files: [{
          path: 'src/jobs.ts', status: 'modified', additions: 1, deletions: 1,
          patch: '@@ -1,1 +1,1 @@\n-old();\n+items.forEach(async (item) => save(item));'
        }]
      },
      expectedFindings: [{ ruleId: 'concurrency/no-async-foreach', path: 'src/jobs.ts', line: 1 }],
      approval: { status: 'candidate' },
      provenance: { kind: 'fixture' }
    }]);

    expect(report).toMatchObject({ cases: 1, precision: 1, recall: 1, truePositive: 1 });
    expect(report).toMatchObject({ positiveCases: 1, negativeCases: 0 });
    expect(report.insufficientSampleWarning).toContain('1/100');
  });

  it('enforces sample, quality, and latency release thresholds', async () => {
    const { evaluateBenchmarkGate } = await import('@codelens/evaluation');
    const result = evaluateBenchmarkGate({
      cases: 99,
      positiveCases: 20,
      negativeCases: 79,
      expected: 10,
      predicted: 10,
      truePositive: 7,
      falsePositive: 3,
      falseNegative: 3,
      precision: 0.7,
      recall: 0.7,
      latencyMs: { p50: 10, p95: 1_500, max: 1_800 }
    }, {
      minCases: 100,
      minPositiveCases: 20,
      minNegativeCases: 20,
      minPrecision: 0.8,
      minRecall: 0.7,
      maxP95LatencyMs: 1_000
    });

    expect(result.passed).toBe(false);
    expect(result.failures).toEqual([
      'cases 99 < 100',
      'precision 0.7000 < 0.8000',
      'p95 latency 1500ms > 1000ms'
    ]);
  });

  it('rejects an unbalanced all-negative benchmark', async () => {
    const { evaluateBenchmarkGate } = await import('@codelens/evaluation');
    const result = evaluateBenchmarkGate({
      cases: 100,
      positiveCases: 0,
      negativeCases: 100,
      expected: 0,
      predicted: 0,
      truePositive: 0,
      falsePositive: 0,
      falseNegative: 0,
      precision: 1,
      recall: 1,
      latencyMs: { p50: 1, p95: 2, max: 3 }
    }, {
      minCases: 100,
      minPositiveCases: 20,
      minNegativeCases: 20,
      minPrecision: 0.8,
      minRecall: 0.7,
      maxP95LatencyMs: 1_000
    });

    expect(result.passed).toBe(false);
    expect(result.failures).toEqual(['positive cases 0 < 20']);
  });

  it('counts only approved historical cases as release evidence', async () => {
    const { isApprovedHistoricalReplayCase } = await import('@codelens/evaluation');
    const base = {
      id: 'candidate',
      context: {
        owner: 'sample', repo: 'replay', number: 1, title: 'Example', body: '',
        baseSha: 'aaaaaaa', headSha: 'bbbbbbb', files: []
      },
      expectedFindings: []
    };

    expect(isApprovedHistoricalReplayCase({
      ...base,
      approval: { status: 'candidate' },
      provenance: { kind: 'historical_pr', sourceUrl: 'https://github.com/a/b/pull/1', repositoryLicense: 'MIT', collectedAt: '2026-09-19T00:00:00.000Z' }
    })).toBe(false);
    expect(isApprovedHistoricalReplayCase({
      ...base,
      approval: { status: 'approved', approvedBy: 'reviewer', approvedAt: '2026-09-19T01:00:00.000Z' },
      provenance: { kind: 'fixture' }
    })).toBe(false);
    expect(isApprovedHistoricalReplayCase({
      ...base,
      approval: { status: 'approved', approvedBy: 'reviewer', approvedAt: '2026-09-19T01:00:00.000Z' },
      provenance: { kind: 'historical_pr', sourceUrl: 'https://github.com/a/b/pull/1', repositoryLicense: 'MIT', collectedAt: '2026-09-19T00:00:00.000Z' }
    })).toBe(true);
  });

  it('requires enough eligible reviews and a 95 percent beta success rate', async () => {
    const { evaluateBetaReadiness } = await import('@codelens/evaluation');
    const ready = evaluateBetaReadiness({
      completed: 19, failed: 1, stale: 2, skipped: 1, queued: 3, inProgress: 2
    }, {
      windowDays: 7, minEligibleReviews: 20, minSuccessRate: 0.95
    });
    expect(ready).toMatchObject({
      ready: true,
      eligibleReviews: 20,
      successRate: 0.95
    });

    const blocked = evaluateBetaReadiness({
      completed: 18, failed: 2, stale: 0, skipped: 0, queued: 0, inProgress: 0
    }, {
      windowDays: 7, minEligibleReviews: 20, minSuccessRate: 0.95
    });
    expect(blocked.ready).toBe(false);
    expect(blocked.failures).toEqual(['success rate 0.9000 < 0.9500']);
  });
});
