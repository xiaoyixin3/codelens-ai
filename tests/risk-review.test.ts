import { describe, expect, it } from 'vitest';
import type { FindingCandidate, PullRequestContext } from '@codelens/contracts';
import {
  DeterministicRiskReviewer,
  DiffMap,
  EvidenceVerifier,
  InMemoryFindingStore,
  RiskReviewPipeline,
  parsePatch
} from '@codelens/risk-review';

const context: PullRequestContext = {
  owner: 'acme',
  repo: 'checkout',
  number: 7,
  title: 'Update payment work',
  body: '',
  baseSha: 'aaaaaaa1111111',
  headSha: 'bbbbbbb2222222',
  files: [
    {
      path: 'src/payment.ts',
      status: 'modified',
      additions: 3,
      deletions: 1,
      patch: [
        '@@ -8,3 +8,5 @@ function pay() {',
        ' const id = input.id;',
        '-legacy(id);',
        '+items.forEach(async (item) => save(item));',
        '+const value = eval(input.expression);',
        '+return value;',
        ' }'
      ].join('\n')
    }
  ]
};

function finding(overrides: Partial<FindingCandidate> = {}): FindingCandidate {
  return {
    source: 'deterministic',
    ruleId: 'test/rule',
    category: 'correctness',
    severity: 'high',
    confidence: 0.95,
    title: 'Test finding',
    claim: 'A concrete failure is present.',
    suggestion: 'Change the implementation.',
    verification: 'Run a focused regression test.',
    path: 'src/payment.ts',
    line: 9,
    excerpt: 'items.forEach(async (item) => save(item));',
    ...overrides
  };
}

describe('DiffMap', () => {
  it('maps unified diff lines to exact left and right positions', () => {
    const lines = parsePatch(context.files[0]!.path, context.files[0]!.patch);

    expect(lines).toEqual(
      expect.arrayContaining([
        expect.objectContaining({ kind: 'context', leftLine: 8, rightLine: 8 }),
        expect.objectContaining({ kind: 'deleted', leftLine: 9, content: 'legacy(id);' }),
        expect.objectContaining({ kind: 'added', rightLine: 9, content: 'items.forEach(async (item) => save(item));' }),
        expect.objectContaining({ kind: 'added', rightLine: 10, content: 'const value = eval(input.expression);' }),
        expect.objectContaining({ kind: 'context', leftLine: 10, rightLine: 12 })
      ])
    );
  });
});

describe('DeterministicRiskReviewer', () => {
  it('emits high-confidence candidates on added lines only', async () => {
    const diff = new DiffMap(context.files);
    const candidates = await new DeterministicRiskReviewer().review(context, diff);

    expect(candidates).toEqual(
      expect.arrayContaining([
        expect.objectContaining({ ruleId: 'concurrency/no-async-foreach', line: 9 }),
        expect.objectContaining({ ruleId: 'security/no-eval', line: 10 })
      ])
    );
  });

  it('does not report credential-shaped test fixtures as production secrets', async () => {
    const testContext: PullRequestContext = {
      ...context,
      files: [{
        path: 'src/__tests__/credentials.test.ts',
        status: 'added', additions: 1, deletions: 0,
        patch: '@@ -0,0 +1 @@\n+const password = "fixture-password";'
      }]
    };
    const candidates = await new DeterministicRiskReviewer().review(
      testContext,
      new DiffMap(testContext.files)
    );
    expect(candidates.some((item) => item.ruleId === 'security/no-hardcoded-secret')).toBe(false);
  });

  it('detects high-value Go security, lifecycle, timeout, and nil-response risks', async () => {
    const goContext: PullRequestContext = {
      ...context,
      files: [{
        path: 'internal/client/client.go', status: 'modified', additions: 12, deletions: 0,
        patch: [
          '@@ -10,0 +10,12 @@',
          '+tlsConfig := &tls.Config{InsecureSkipVerify: true}',
          '+ctx, _ := context.WithCancel(parent)',
          '+resp, err := http.Get(endpoint)',
          '+defer resp.Body.Close()',
          '+_ = os.WriteFile(path, payload, 0777)',
          '+rows, err := db.Query(fmt.Sprintf("SELECT * FROM users WHERE name = \'%s\'", name))',
          '+cmd := exec.Command("sh", "-c", "echo "+input)',
          '+json.Unmarshal(payload, &target)',
          '+http.ListenAndServe(":8080", handler)',
          '+file, err := os.Open(path)',
          '+defer file.Close()',
          '+return nil'
        ].join('\n')
      }]
    };
    const candidates = await new DeterministicRiskReviewer().review(goContext, new DiffMap(goContext.files));

    expect(candidates.map((item) => item.ruleId)).toEqual(expect.arrayContaining([
      'go/security/insecure-tls',
      'go/security/world-writable-permission',
      'go/correctness/discarded-context-cancel',
      'go/performance/default-http-client-no-timeout',
      'go/correctness/response-close-before-error-check',
      'go/security/formatted-sql',
      'go/security/dynamic-shell-command',
      'go/correctness/ignored-decode-error',
      'go/correctness/ignored-server-error',
      'go/correctness/resource-close-before-error-check'
    ]));
  });

  it('does not apply Go production rules to _test.go or a guarded response close', async () => {
    const safeGoContext: PullRequestContext = {
      ...context,
      files: [
        {
          path: 'internal/client/client_test.go', status: 'modified', additions: 1, deletions: 0,
          patch: '@@ -2,0 +2 @@\n+tlsConfig := &tls.Config{InsecureSkipVerify: true}'
        },
        {
          path: 'internal/client/client.go', status: 'modified', additions: 4, deletions: 0,
          patch: '@@ -20,0 +20,4 @@\n+resp, err := client.Do(req)\n+if err != nil { return err }\n+defer resp.Body.Close()\n+return nil'
        }
      ]
    };
    const candidates = await new DeterministicRiskReviewer().review(safeGoContext, new DiffMap(safeGoContext.files));

    expect(candidates.filter((item) => item.ruleId?.startsWith('go/'))).toEqual([]);
  });
});

describe('EvidenceVerifier', () => {
  it('accepts exact added-line evidence and rejects context, mismatch, low confidence, and duplicates', () => {
    const diff = new DiffMap(context.files);
    const results = new EvidenceVerifier({ maxPublished: 8 }).verify([
      finding(),
      finding(),
      finding({ line: 8, excerpt: 'const id = input.id;' }),
      finding({ line: 10, excerpt: 'not the diff line', ruleId: 'test/mismatch' }),
      finding({ line: 11, excerpt: 'return value;', ruleId: 'test/low', confidence: 0.4 })
    ], diff);

    expect(results.filter((item) => item.publishable)).toHaveLength(1);
    expect(results.map((item) => item.rejectionReason)).toEqual(
      expect.arrayContaining(['duplicate', 'evidence_not_added_line', 'excerpt_mismatch', 'below_publish_threshold'])
    );
    expect(results.find((item) => item.publishable)?.evidence).toMatchObject({
      path: 'src/payment.ts', startLine: 9, side: 'RIGHT', evidenceType: 'diff'
    });
  });

  it('keeps verified audit records when the publication cap is reached', () => {
    const results = new EvidenceVerifier({ maxPublished: 1 }).verify([
      finding(),
      finding({ ruleId: 'test/second', line: 10, excerpt: 'const value = eval(input.expression);' })
    ], new DiffMap(context.files));

    expect(results.filter((item) => item.status === 'verified')).toHaveLength(2);
    expect(results.filter((item) => item.publishable)).toHaveLength(1);
    expect(results).toEqual(expect.arrayContaining([expect.objectContaining({ rejectionReason: 'publication_limit' })]));
  });
});

describe('RiskReviewPipeline', () => {
  it('persists the full evidence audit and returns bounded findings', async () => {
    const store = new InMemoryFindingStore();
    const pipeline = new RiskReviewPipeline(
      new DeterministicRiskReviewer(),
      new EvidenceVerifier({ maxPublished: 1 }),
      store
    );
    const result = await pipeline.review('00000000-0000-4000-8000-000000000001', context);

    expect(result.candidates).toBe(2);
    expect(result.verified).toBe(2);
    expect(result.published).toBe(1);
    expect(store.findings.get('00000000-0000-4000-8000-000000000001')).toHaveLength(2);
  });

  it('applies repository thresholds and annotation limits', async () => {
    const store = new InMemoryFindingStore();
    const pipeline = new RiskReviewPipeline(
      new DeterministicRiskReviewer(),
      new EvidenceVerifier({ maxPublished: 8 }),
      store
    );
    const result = await pipeline.review(
      '00000000-0000-4000-8000-000000000002',
      context,
      {
        maxInlineComments: 1,
        minimumConfidence: { high: 0.99 },
        guidance: '',
        rules: []
      }
    );

    expect(result.published).toBe(0);
    expect(result.findings.every((item) => item.rejectionReason === 'below_publish_threshold')).toBe(true);
  });
});
