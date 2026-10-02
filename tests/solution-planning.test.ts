import { describe, expect, it } from 'vitest';
import {
  buildNeutralChangeBrief,
  ReviewContextBundleSchema,
  type ReplayCase
} from '@codelens/evaluation';
import {
  buildSolutionOptions,
  retrieveReuseCandidates,
  ReuseDecisionSchema,
  verifyPatchRelease
} from '@codelens/solution-planning';

function fixture(limitations: string[] = []) {
  const item: ReplayCase = {
    id: 'reuse-case',
    context: {
      owner: 'example', repo: 'payments', number: 12, title: 'Route dispatch through the service', body: 'Preserve cancellation behavior.',
      baseSha: 'a'.repeat(40), headSha: 'b'.repeat(40),
      files: [{ path: 'src/PaymentService.java', status: 'modified', additions: 2, deletions: 1, patch: '@@ -1 +1 @@\n-old\n+new' }]
    },
    expectedFindings: [], approval: { status: 'candidate' },
    provenance: { kind: 'historical_pr', sourceUrl: 'https://github.com/example/payments/pull/12', repositoryLicense: 'Apache-2.0', collectedAt: '2026-10-02T12:00:00+08:00' }
  };
  const bundle = ReviewContextBundleSchema.parse({
    version: 1, caseId: item.id, packetId: 'reuse-packet', digest: 'f'.repeat(64), generatedAt: '2026-10-02T12:05:00+08:00',
    baseSha: item.context.baseSha, headSha: item.context.headSha,
    files: [
      { path: 'src/PaymentService.java', revision: 'base', role: 'source', content: 'class PaymentService {}' },
      { path: 'src/PaymentService.java', revision: 'head', role: 'source', content: 'class PaymentService {}' },
      { path: 'src/LegacyDispatcher.java', revision: 'head', role: 'source', content: 'class LegacyDispatcher {}' },
      { path: 'src/test/PaymentFixtures.java', revision: 'head', role: 'test', content: 'class PaymentFixtures {}' }
    ],
    symbols: [
      { id: 'base:service', name: 'PaymentService.dispatch', kind: 'method', path: 'src/PaymentService.java', revision: 'base', startLine: 1, endLine: 1 },
      { id: 'head:service', name: 'PaymentService.dispatch', kind: 'method', path: 'src/PaymentService.java', revision: 'head', startLine: 1, endLine: 1 },
      { id: 'head:legacy', name: 'LegacyDispatcher.dispatch', kind: 'method', path: 'src/LegacyDispatcher.java', revision: 'head', startLine: 1, endLine: 1 },
      { id: 'head:fixture', name: 'PaymentFixtures.request', kind: 'method', path: 'src/test/PaymentFixtures.java', revision: 'head', startLine: 1, endLine: 1 }
    ],
    relationships: [{ fromSymbolId: 'head:fixture', toSymbolId: 'head:service', type: 'tests', evidencePath: 'src/test/PaymentFixtures.java', evidenceLine: 1 }],
    limitations
  });
  return { item, bundle, brief: buildNeutralChangeBrief(item, bundle) };
}

describe('reuse-first solution planning', () => {
  it('retrieves evidence-bound candidates and keeps the patch gate closed before a decision', () => {
    const { brief, bundle } = fixture();
    const [investigation] = retrieveReuseCandidates(brief, bundle);
    expect(investigation?.searchScope.semanticCoverageComplete).toBe(true);
    expect(investigation?.candidates.map((candidate) => candidate.relationship)).toEqual(['same_contract', 'test_fixture']);
    expect(investigation?.candidates[0]).toMatchObject({ symbolName: 'LegacyDispatcher.dispatch', fit: 'direct', score: 0.95 });
    expect(investigation?.patchGate).toMatchObject({ allowed: false });
  });

  it('requires an auditable decision and enforces its change budget before release', () => {
    const { brief, bundle } = fixture();
    const investigation = retrieveReuseCandidates(brief, bundle)[0]!;
    const selected = investigation.candidates[0]!;
    const decision = ReuseDecisionSchema.parse({
      behaviorId: investigation.behaviorId,
      goal: investigation.goal,
      searchScope: investigation.searchScope,
      candidates: investigation.candidates,
      decision: 'reuse',
      selectedCandidateId: selected.id,
      justification: '直接复用已存在的 dispatch 契约，避免建立第二套发送逻辑。',
      changeBudget: { maxFiles: 2, maxChangedSymbols: 2, publicContractChangeAllowed: false }
    });
    const option = buildSolutionOptions(decision)[0]!;
    const proposal = {
      behaviorId: decision.behaviorId,
      solutionOptionId: option.id,
      touchedFiles: ['src/PaymentService.java'],
      changedSymbols: ['PaymentService.dispatch'],
      changesPublicContract: false,
      verification: ['Run PaymentService cancellation tests.'],
      unifiedDiff: '@@ -1 +1 @@\n-old\n+reuseLegacyDispatcher'
    };
    expect(verifyPatchRelease(decision, option, proposal)).toEqual({ allowed: true, reasons: [] });
    expect(verifyPatchRelease(undefined, option, proposal)).toMatchObject({ allowed: false, reasons: ['缺少有效的 ReuseDecision。'] });
    expect(verifyPatchRelease(decision, option, { ...proposal, touchedFiles: ['a', 'b', 'c'] })).toMatchObject({
      allowed: false, reasons: ['补丁文件数超过 change budget。']
    });
  });

  it('rejects a new implementation claim when semantic coverage is incomplete', () => {
    const { brief, bundle } = fixture(['head semantic index was not supplied.']);
    const investigation = retrieveReuseCandidates(brief, bundle)[0]!;
    expect(investigation.searchScope.semanticCoverageComplete).toBe(false);
    const decision = ReuseDecisionSchema.safeParse({
      behaviorId: investigation.behaviorId, goal: investigation.goal, searchScope: investigation.searchScope,
      candidates: investigation.candidates.map((candidate) => ({ ...candidate, fit: 'rejected', rejectionReason: 'Does not preserve the required transaction boundary.' })),
      decision: 'new', justification: 'A separate implementation would own a distinct transaction boundary.',
      changeBudget: { maxFiles: 2, maxChangedSymbols: 2, publicContractChangeAllowed: false }
    });
    expect(decision.success).toBe(false);
  });

  it('allows a bounded new option only after every retrieved candidate is rejected with evidence', () => {
    const { brief, bundle } = fixture();
    const investigation = retrieveReuseCandidates(brief, bundle)[0]!;
    const decision = ReuseDecisionSchema.parse({
      behaviorId: investigation.behaviorId, goal: investigation.goal, searchScope: investigation.searchScope,
      candidates: investigation.candidates.map((candidate) => ({
        ...candidate, fit: 'rejected', rejectionReason: 'The existing symbol owns a different failure and transaction contract.'
      })),
      decision: 'new', justification: 'Create one bounded adapter because every existing contract has an incompatible ownership boundary.',
      changeBudget: { maxFiles: 2, maxChangedSymbols: 2, publicContractChangeAllowed: false }
    });
    const option = buildSolutionOptions(decision)[0];
    expect(option).toMatchObject({ strategy: 'new' });
    expect(option).not.toHaveProperty('candidateId');
  });
});
