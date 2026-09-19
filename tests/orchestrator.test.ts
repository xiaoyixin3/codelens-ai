import { describe, expect, it } from 'vitest';
import type { PullRequestContext } from '@codelens/contracts';
import type {
  CheckInput,
  CompleteCheckInput,
  GitHubGateway
} from '@codelens/github';
import { InMemoryReviewStore } from '@codelens/persistence';
import { DeterministicSummaryGenerator, ReviewOrchestrator } from '@codelens/review-core';

class FakeGitHubGateway implements GitHubGateway {
  currentHead = 'bbbbbbb2222222';
  headSequence: string[] = [];
  startCalls: CheckInput[] = [];
  existingCheckRunIds: Array<number | undefined> = [];
  completeCalls: CompleteCheckInput[] = [];
  commentBodies: string[] = [];

  context: PullRequestContext = {
    owner: 'acme',
    repo: 'checkout',
    number: 7,
    title: 'Add payment retries',
    body: 'Retry transient provider failures.',
    baseSha: 'aaaaaaa1111111',
    headSha: 'bbbbbbb2222222',
    files: [
      {
        path: 'src/payment/retry.ts',
        status: 'added',
        additions: 80,
        deletions: 0,
        patch: '@@ -0,0 +1,2 @@\n+export function retry() {}'
      }
    ]
  };

  async getPullRequest(): Promise<PullRequestContext> {
    return this.context;
  }

  async getCurrentHeadSha(): Promise<string> {
    return this.headSequence.shift() ?? this.currentHead;
  }

  async getFileContent(): Promise<string> {
    return 'export const value = 1;';
  }

  async startCheck(input: CheckInput, existingCheckRunId?: number): Promise<number> {
    this.startCalls.push(input);
    this.existingCheckRunIds.push(existingCheckRunId);
    return existingCheckRunId ?? 101;
  }

  async completeCheck(input: CompleteCheckInput): Promise<void> {
    this.completeCalls.push(input);
  }

  async upsertSummaryComment(input: { body: string }): Promise<number> {
    this.commentBodies.push(input.body);
    return 202;
  }
}

async function setup() {
  const store = new InMemoryReviewStore();
  const github = new FakeGitHubGateway();
  const { run } = await store.createOrGetReviewRun({
    repositoryId: 99,
    pullNumber: 7,
    baseSha: 'aaaaaaa1111111',
    headSha: 'bbbbbbb2222222',
    pipelineVersion: 'v0.1.0',
    configHash: 'default-v1'
  });
  const job = {
    reviewRunId: run.id,
    installationId: 42,
    owner: 'acme',
    repo: 'checkout',
    pullNumber: 7,
    baseSha: run.baseSha,
    headSha: run.headSha
  };
  const summaries = new DeterministicSummaryGenerator({
    maxChangedFiles: 100,
    maxPatchChars: 120_000
  });
  return { store, github, run, job, orchestrator: new ReviewOrchestrator(store, github, summaries) };
}

describe('ReviewOrchestrator', () => {
  it('completes and publishes a deterministic review', async () => {
    const { store, github, run, job, orchestrator } = await setup();

    await orchestrator.execute(job);

    expect((await store.getReviewRun(run.id))?.status).toBe('completed');
    expect((await store.getReviewRun(run.id))?.summary?.riskLevel).toBe('high');
    expect(github.startCalls).toHaveLength(1);
    expect(github.completeCalls).toHaveLength(1);
    expect(github.completeCalls[0]?.conclusion).toBe('neutral');
    expect(github.commentBodies[0]).toContain('CodeLens AI Review');
    expect((await store.getPublication(run.id))?.checkRunId).toBe(101);
    expect((await store.getPublication(run.id))?.summaryCommentId).toBe(202);
  });

  it('marks an outdated head SHA stale without publishing', async () => {
    const { store, github, run, job, orchestrator } = await setup();
    github.currentHead = 'ccccccc3333333';

    await orchestrator.execute(job);

    expect((await store.getReviewRun(run.id))?.status).toBe('stale');
    expect(github.startCalls).toHaveLength(0);
    expect(github.completeCalls).toHaveLength(0);
  });

  it('marks a run stale when a new SHA arrives during analysis', async () => {
    const { store, github, run, job, orchestrator } = await setup();
    github.headSequence = [job.headSha, 'ccccccc3333333'];

    await orchestrator.execute(job);

    expect((await store.getReviewRun(run.id))?.status).toBe('stale');
    expect(github.completeCalls).toHaveLength(1);
    expect(github.completeCalls[0]).toMatchObject({ conclusion: 'stale' });
    expect(github.commentBodies).toHaveLength(0);
  });

  it('is idempotent after completion', async () => {
    const { github, job, orchestrator } = await setup();

    await orchestrator.execute(job);
    await orchestrator.execute(job);

    expect(github.startCalls).toHaveLength(1);
    expect(github.completeCalls).toHaveLength(1);
    expect(github.commentBodies).toHaveLength(1);
  });

  it('reuses publication state after a transient failure and redacts the stored error', async () => {
    const { store, github, run, job } = await setup();
    const deterministic = new DeterministicSummaryGenerator({
      maxChangedFiles: 100,
      maxPatchChars: 120_000
    });
    let attempts = 0;
    const orchestrator = new ReviewOrchestrator(store, github, {
      async generate(context, policy, reviewRunId) {
        attempts += 1;
        if (attempts === 1) throw new Error('provider failed api_key="sk-live-super-secret-value"');
        void reviewRunId;
        return deterministic.generate(context, policy);
      }
    });

    await expect(orchestrator.execute(job)).rejects.toThrow('provider failed');
    expect((await store.getReviewRun(run.id))?.errorDetail).not.toContain('sk-live-super-secret-value');
    await orchestrator.execute(job);

    expect(github.existingCheckRunIds).toEqual([undefined, 101]);
    expect(github.commentBodies).toHaveLength(1);
    expect((await store.getReviewRun(run.id))?.status).toBe('completed');
  });

  it('publishes bounded impact intelligence in the Check summary', async () => {
    const { store, github, run, job } = await setup();
    const summaries = new DeterministicSummaryGenerator({
      maxChangedFiles: 100,
      maxPatchChars: 120_000
    });
    const orchestrator = new ReviewOrchestrator(store, github, summaries, {
      async index() {
        return {
          impact: {
            changes: [
              {
                qualifiedName: 'retryPayment',
                after: { stableKey: 'typescript:src/payment.ts:function:retryPayment' }
              }
            ],
            paths: [
              {
                changedStableKey: 'typescript:src/payment.ts:function:retryPayment',
                impactedName: 'PaymentController.pay',
                depth: 1,
                score: 0.9
              }
            ],
            blastRadius: {
              score: 42,
              level: 'medium',
              changedSymbols: 1,
              impactedSymbols: 1
            },
            coverage: { warning: 'Only changed files were indexed.' }
          }
        };
      }
    });

    await orchestrator.execute(job);

    const completed = await store.getReviewRun(run.id);
    expect(completed?.summary?.impact).toMatchObject({ score: 42, impactedSymbols: 1 });
    expect(github.completeCalls[0]?.summary).toContain('### Impact analysis');
    expect(github.completeCalls[0]?.summary).toContain('PaymentController.pay');
  });

  it('publishes only verified findings as exact Check annotations', async () => {
    const { store, github, run, job } = await setup();
    const summaries = new DeterministicSummaryGenerator({
      maxChangedFiles: 100,
      maxPatchChars: 120_000
    });
    const orchestrator = new ReviewOrchestrator(store, github, summaries, undefined, {
      async review() {
        return {
          candidates: 2,
          verified: 1,
          published: 1,
          rejected: 1,
          findings: [
            {
              source: 'deterministic' as const,
              ruleId: 'security/no-eval',
              category: 'security' as const,
              severity: 'high' as const,
              confidence: 0.98,
              title: 'Dynamic code execution',
              claim: 'Untrusted input may be executed.',
              suggestion: 'Use a constrained parser.',
              verification: 'Trace the expression input.',
              path: 'src/payment/retry.ts',
              line: 1,
              excerpt: 'export function retry() {}',
              fingerprint: 'fingerprint-1',
              status: 'verified' as const,
              publishable: true,
              evidence: {
                path: 'src/payment/retry.ts',
                startLine: 1,
                endLine: 1,
                side: 'RIGHT' as const,
                excerptHash: 'abc',
                evidenceType: 'diff' as const
              }
            },
            {
              source: 'llm' as const,
              category: 'correctness' as const,
              severity: 'medium' as const,
              confidence: 0.5,
              title: 'Speculative issue',
              claim: 'This was not verified.',
              suggestion: 'Do nothing yet.',
              verification: 'Find direct evidence.',
              path: 'src/payment/retry.ts',
              line: 2,
              fingerprint: 'fingerprint-2',
              status: 'rejected' as const,
              rejectionReason: 'below_publish_threshold',
              publishable: false
            }
          ]
        };
      }
    });

    await orchestrator.execute(job);

    expect(github.completeCalls[0]?.annotations).toHaveLength(1);
    expect(github.completeCalls[0]?.annotations?.[0]).toMatchObject({
      path: 'src/payment/retry.ts', startLine: 1, level: 'failure'
    });
    expect(github.completeCalls[0]?.summary).toContain('### Verified findings');
    expect(github.commentBodies[0]).toContain('src/payment/retry.ts:1');
    expect((await store.getReviewRun(run.id))?.summary?.findings?.published).toBe(1);
  });

  it('applies immutable repository policy and makes high risk blocking when configured', async () => {
    const { store, github, run, job } = await setup();
    const summaries = new DeterministicSummaryGenerator({
      maxChangedFiles: 100,
      maxPatchChars: 120_000
    });
    const policyHash = 'a'.repeat(64);
    const orchestrator = new ReviewOrchestrator(
      store,
      github,
      summaries,
      undefined,
      undefined,
      {
        async load(input) {
          expect(input.headSha).toBe(job.headSha);
          return {
            hash: policyHash,
            sourceCommitSha: job.headSha,
            language: 'en' as const,
            blocking: true,
            maxInlineComments: 2,
            minimumConfidence: { high: 0.9 },
            include: ['src/**'],
            exclude: ['**/*.generated.ts'],
            guidance: '',
            rules: [],
            warnings: []
          };
        }
      }
    );

    await orchestrator.execute(job);

    expect(github.completeCalls[0]?.conclusion).toBe('failure');
    expect(github.completeCalls[0]?.summary).toContain('### Repository policy');
    expect((await store.getReviewRun(run.id))?.configHash).toBe(policyHash);
    expect((await store.getReviewRun(run.id))?.summary?.policy).toMatchObject({ blocking: true });
  });
});
