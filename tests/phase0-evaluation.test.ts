import { describe, expect, it } from 'vitest';
import { evaluatePhase0Evidence, Phase0CaseSchema, ReviewerSessionSchema } from '@codelens/evaluation';

function rootCause(id: string) {
  return {
    rootCauseId: id,
    category: 'correctness',
    severity: 'high' as const,
    claim: 'The changed behavior returns the wrong result.',
    trigger: 'A caller supplies an empty account identifier.',
    evidence: [{ path: 'src/Service.java', startLine: 10, endLine: 10, side: 'RIGHT' as const, fact: 'The guard was removed.' }]
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
});
