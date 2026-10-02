import { describe, expect, it } from 'vitest';
import {
  buildNeutralChangeBrief,
  ChangeBriefSchema,
  evaluatePhase0Evidence,
  Phase0CaseSchema,
  ReviewContextBundleSchema,
  ReviewerSessionSchema,
  ReviewWorkbenchDecisionSchema
} from '@codelens/evaluation';

function rootCause(id: string) {
  return {
    rootCauseId: id,
    category: 'correctness',
    severity: 'high' as const,
    claim: 'The changed behavior returns the wrong result.',
    trigger: 'A caller supplies an empty account identifier.',
    impact: 'The caller receives data belonging to the wrong account.',
    evidence: [{
      kind: 'diff' as const, path: 'src/Service.java', revision: 'head' as const,
      startLine: 10, endLine: 10, side: 'RIGHT' as const, fact: 'The guard was removed.'
    }],
    affectedSymbols: ['Service.load'],
    acceptableFix: 'Restore the guard through the existing account validator.',
    verification: 'Exercise the production entry point with an empty account identifier.',
    confidence: 'certain' as const
  };
}

function phase0Case(id: string, positive: boolean) {
  const labels = positive ? [rootCause(`root-${id}`)] : [];
  return {
    id,
    repository: 'example/repository',
    pullNumber: Number(id.replace(/\D/g, '')) + 1,
    baseSha: 'a'.repeat(40),
    headSha: 'b'.repeat(40),
    provenance: {
      sourceUrl: `https://github.com/example/repository/pull/${Number(id.replace(/\D/g, '')) + 1}`,
      repositoryLicense: 'Apache-2.0',
      permissionBasis: 'public_license' as const,
      collectedAt: '2026-09-25T10:00:00+08:00'
    },
    contextPacket: {
      id: `context-${id}`,
      digest: 'c'.repeat(64),
      frozenAt: '2026-09-25T10:30:00+08:00',
      materials: ['full_repository', 'pull_request', 'build_descriptors', 'project_documentation', 'tests', 'subsystem_map'] as const,
      scopeBriefing: 'Review the changed service behavior with its callers and tests.'
    },
    blindReviews: [
      {
        reviewerId: 'reviewer-a', submittedAt: '2026-09-25T11:00:00+08:00', contextPacketId: `context-${id}`,
        independent: true as const, predictionVisible: false as const,
        qualification: {
          primaryLanguages: ['Java'], yearsExperience: 3, repositoryFamiliarity: 'calibrated_external' as const,
          calibrationSetId: 'phase0-java-review-v1', calibrationScore: 0.9,
          calibrationCompletedAt: '2026-09-24T11:00:00+08:00'
        },
        rootCauses: labels
      },
      {
        reviewerId: 'reviewer-b', submittedAt: '2026-09-25T12:00:00+08:00', contextPacketId: `context-${id}`,
        independent: true as const, predictionVisible: false as const,
        qualification: {
          primaryLanguages: ['Java'], yearsExperience: 4, repositoryFamiliarity: 'contributor' as const,
          calibrationSetId: 'phase0-java-review-v1', calibrationScore: 0.95,
          calibrationCompletedAt: '2026-09-24T12:00:00+08:00'
        },
        rootCauses: labels
      }
    ],
    adjudication: {
      adjudicatedBy: 'reviewer-c',
      adjudicatedAt: '2026-09-25T13:00:00+08:00',
      conflictCount: 0,
      rootCauses: labels
    }
  };
}

function session(caseId: string, arm: 'codelens' | 'control') {
  return {
    caseId,
    reviewerId: arm === 'codelens' ? 'reviewer-a' : 'reviewer-b',
    arm,
    startedAt: '2026-09-25T14:00:00+08:00',
    completedAt: '2026-09-25T14:10:00+08:00',
    activeSeconds: 480,
    decision: 'request_changes' as const
  };
}

describe('Phase 0 evidence contract', () => {
  it('accepts root-cause labels only after independent review, adjudication, and both timing arms', () => {
    const cases = [phase0Case('case-1', true), phase0Case('case-2', false)];
    const sessions = [session('case-1', 'codelens'), session('case-2', 'control')];
    const report = evaluatePhase0Evidence(cases, sessions, {
      minCases: 2,
      minPositiveRootCauses: 1,
      minTimedSessions: 2
    });

    expect(report.passed).toBe(true);
    expect(report.positiveRootCauses).toBe(1);
    expect(cases.every((item) => Phase0CaseSchema.safeParse(item).success)).toBe(true);
    expect(sessions.every((item) => ReviewerSessionSchema.safeParse(item).success)).toBe(true);
  });

  it('fails closed when reviewers are not independent or the real sample is too small', () => {
    const item = phase0Case('case-1', true);
    item.blindReviews[1]!.reviewerId = 'reviewer-a';
    const report = evaluatePhase0Evidence([item], [], {
      minCases: 50,
      minPositiveRootCauses: 20,
      minTimedSessions: 20
    });

    expect(report.passed).toBe(false);
    expect(report.failures.join('\n')).toContain('Two distinct blind reviewers are required');
    expect(report.failures.join('\n')).toContain('cases 0 < 50');
    expect(report.failures.join('\n')).toContain('both codelens and control timing arms are required');
  });

  it('requires a third reviewer when the two blind labels conflict', () => {
    const item = phase0Case('case-1', true);
    item.adjudication.conflictCount = 1;
    item.adjudication.adjudicatedBy = 'reviewer-a';
    const parsed = Phase0CaseSchema.safeParse(item);
    expect(parsed.success).toBe(false);
    if (!parsed.success) expect(parsed.error.issues[0]?.message).toContain('third reviewer');
  });

  it('rejects context-free, prediction-exposed, or uncalibrated labels', () => {
    const missingContext: any = phase0Case('case-1', true);
    missingContext.contextPacket.materials = ['pull_request'];
    expect(Phase0CaseSchema.safeParse(missingContext).success).toBe(false);

    const exposed: any = phase0Case('case-2', true);
    exposed.blindReviews[0].predictionVisible = true;
    expect(Phase0CaseSchema.safeParse(exposed).success).toBe(false);

    const uncalibrated: any = phase0Case('case-3', true);
    uncalibrated.blindReviews[0].qualification.calibrationScore = 0.5;
    expect(Phase0CaseSchema.safeParse(uncalibrated).success).toBe(false);
  });

  it('requires actionable root-cause fields and allows evidence outside added diff lines', () => {
    const item: any = phase0Case('case-4', true);
    item.blindReviews[0].rootCauses[0].evidence = [{
      kind: 'caller',
      path: 'src/Caller.java',
      revision: 'base',
      startLine: 42,
      endLine: 45,
      symbol: 'Caller.invoke',
      fact: 'The unchanged caller still passes an empty identifier.'
    }];
    expect(Phase0CaseSchema.safeParse(item).success).toBe(true);

    delete item.blindReviews[0].rootCauses[0].verification;
    expect(Phase0CaseSchema.safeParse(item).success).toBe(false);
  });

  it('keeps diff sides and source evidence aligned with their revisions', () => {
    const label = rootCause('revision-alignment');
    expect(ReviewWorkbenchDecisionSchema.safeParse({
      status: 'frozen', mode: 'gold', reviewer: 'reviewer-a', contextPacketId: 'packet-1',
      predictionVisible: false, rootCauses: [{
        ...label,
        evidence: [{ ...label.evidence[0], side: 'LEFT', revision: 'head' }]
      }],
      updatedAt: '2026-09-25T11:00:00+08:00', protocol: 'reasoning-v1'
    }).success).toBe(false);
    expect(ReviewWorkbenchDecisionSchema.safeParse({
      status: 'frozen', mode: 'gold', reviewer: 'reviewer-a', contextPacketId: 'packet-1',
      predictionVisible: false, rootCauses: [{
        ...label,
        evidence: [{ ...label.evidence[0], kind: 'base_source', side: undefined, revision: 'head' }]
      }],
      updatedAt: '2026-09-25T11:00:00+08:00', protocol: 'reasoning-v1'
    }).success).toBe(false);
  });

  it('separates prediction-blind gold decisions from assisted decisions', () => {
    const label = rootCause('root-workbench');
    const common = {
      status: 'frozen' as const,
      reviewer: 'reviewer-a',
      contextPacketId: 'packet-1',
      rootCauses: [label],
      updatedAt: '2026-09-25T11:00:00+08:00',
      protocol: 'reasoning-v1' as const
    };
    expect(ReviewWorkbenchDecisionSchema.safeParse({
      ...common, mode: 'gold', predictionVisible: false
    }).success).toBe(true);
    expect(ReviewWorkbenchDecisionSchema.safeParse({
      ...common, mode: 'gold', predictionVisible: true
    }).success).toBe(false);
    expect(ReviewWorkbenchDecisionSchema.safeParse({
      ...common, mode: 'assisted', predictionVisible: true
    }).success).toBe(true);
  });

  it('validates frozen neutral context paths and relationships', () => {
    const bundle = {
      version: 1,
      caseId: 'case-1',
      packetId: 'packet-1',
      digest: 'd'.repeat(64),
      generatedAt: '2026-09-25T10:30:00+08:00',
      baseSha: 'a'.repeat(40),
      headSha: 'b'.repeat(40),
      files: [
        { path: 'src/Service.java', revision: 'head', role: 'source', language: 'Java', content: 'class Service {}' },
        { path: 'src/ServiceTest.java', revision: 'head', role: 'test', language: 'Java', content: 'class ServiceTest {}' }
      ],
      symbols: [
        { id: 'service', name: 'Service', kind: 'class', path: 'src/Service.java', revision: 'head', startLine: 1, endLine: 1 },
        { id: 'test', name: 'ServiceTest', kind: 'class', path: 'src/ServiceTest.java', revision: 'head', startLine: 1, endLine: 1 }
      ],
      relationships: [{ fromSymbolId: 'test', toSymbolId: 'service', type: 'tests', evidencePath: 'src/ServiceTest.java', evidenceLine: 1 }],
      limitations: []
    };
    expect(ReviewContextBundleSchema.safeParse(bundle).success).toBe(true);
    bundle.files[0]!.path = '../secret.txt';
    expect(ReviewContextBundleSchema.safeParse(bundle).success).toBe(false);
  });

  it('builds evidence-traceable behavior cards and neutral review questions', () => {
    const replayCase = {
      id: 'brief-case',
      context: {
        owner: 'example', repo: 'service', number: 9, title: 'Route requests through the shared service',
        body: 'Preserve the existing caller contract while consolidating dispatch.',
        baseSha: 'a'.repeat(40), headSha: 'b'.repeat(40),
        files: [
          { path: 'src/A.java', status: 'modified' as const, additions: 2, deletions: 1, patch: '@@ -1 +1 @@\n-old\n+new' },
          { path: 'src/B.java', status: 'modified' as const, additions: 1, deletions: 1, patch: '@@ -1 +1 @@\n-old\n+new' }
        ]
      },
      expectedFindings: [], approval: { status: 'candidate' as const },
      provenance: { kind: 'historical_pr' as const, sourceUrl: 'https://github.com/example/service/pull/9', repositoryLicense: 'Apache-2.0', collectedAt: '2026-10-02T10:00:00+08:00' }
    };
    const context = ReviewContextBundleSchema.parse({
      version: 1, caseId: 'brief-case', packetId: 'packet-brief', digest: 'e'.repeat(64),
      generatedAt: '2026-10-02T10:05:00+08:00', baseSha: replayCase.context.baseSha, headSha: replayCase.context.headSha,
      files: [
        { path: 'src/A.java', revision: 'base', role: 'source', content: 'class A {}' },
        { path: 'src/A.java', revision: 'head', role: 'source', content: 'class A {}' },
        { path: 'src/B.java', revision: 'base', role: 'source', content: 'class B {}' },
        { path: 'src/B.java', revision: 'head', role: 'source', content: 'class B {}' },
        { path: 'src/Caller.java', revision: 'head', role: 'source', content: 'class Caller {}' },
        { path: 'src/FlowTest.java', revision: 'head', role: 'test', content: 'class FlowTest {}' }
      ],
      symbols: [
        { id: 'base:A', name: 'A.dispatch', kind: 'method', path: 'src/A.java', revision: 'base', startLine: 1, endLine: 1 },
        { id: 'head:A', name: 'A.dispatch', kind: 'method', path: 'src/A.java', revision: 'head', startLine: 1, endLine: 1 },
        { id: 'base:B', name: 'B.send', kind: 'method', path: 'src/B.java', revision: 'base', startLine: 1, endLine: 1 },
        { id: 'head:B', name: 'B.send', kind: 'method', path: 'src/B.java', revision: 'head', startLine: 1, endLine: 1 },
        { id: 'head:Caller', name: 'Caller.run', kind: 'method', path: 'src/Caller.java', revision: 'head', startLine: 1, endLine: 1 },
        { id: 'head:Test', name: 'FlowTest.dispatches', kind: 'method', path: 'src/FlowTest.java', revision: 'head', startLine: 1, endLine: 1 }
      ],
      relationships: [
        { fromSymbolId: 'head:A', toSymbolId: 'head:B', type: 'calls', evidencePath: 'src/A.java', evidenceLine: 1 },
        { fromSymbolId: 'head:Caller', toSymbolId: 'head:A', type: 'calls', evidencePath: 'src/Caller.java', evidenceLine: 1 },
        { fromSymbolId: 'head:Test', toSymbolId: 'head:B', type: 'tests', evidencePath: 'src/FlowTest.java', evidenceLine: 1 }
      ],
      limitations: []
    });
    const brief = buildNeutralChangeBrief(replayCase, context);
    expect(ChangeBriefSchema.safeParse(brief).success).toBe(true);
    expect(brief.behaviorCards).toHaveLength(1);
    expect(brief.behaviorCards[0]?.changedFiles).toEqual(['src/A.java', 'src/B.java']);
    expect(brief.behaviorCards[0]?.unchangedCallers).toContain('Caller.run');
    expect(brief.behaviorCards[0]?.relatedTests).toContain('src/FlowTest.java');
    expect(brief.questions.some((question) => question.id.endsWith(':reuse'))).toBe(true);
    expect(brief.questions.find((question) => question.id.endsWith(':callers'))?.evidenceIds).toEqual(['relationship:1']);
    expect(brief.questions.find((question) => question.id.endsWith(':tests'))?.evidenceIds).toEqual([
      'test:head:src/FlowTest.java', 'relationship:2'
    ]);
    expect(brief.intent.evidenceIds).toEqual(['pr:title', 'pr:body']);
  });
});
