import { z } from 'zod';
import { createHash } from 'node:crypto';
import { FindingCategorySchema, PullRequestContextSchema, type PullRequestContext } from '@codelens/contracts';
import { DeterministicRiskReviewer, DiffMap, EvidenceVerifier } from '@codelens/risk-review';

export interface ExpectedFinding {
  ruleId: string;
  path: string;
  line: number;
  category?: string | undefined;
  severity?: 'critical' | 'high' | 'medium' | 'low' | undefined;
  title?: string | undefined;
  notes?: string | undefined;
}

export interface ReplayCase {
  id: string;
  context: PullRequestContext;
  expectedFindings: ExpectedFinding[];
  approval:
    | { status: 'candidate' }
    | { status: 'approved'; approvedBy: string; approvedAt: string; notes?: string };
  provenance:
    | { kind: 'fixture' }
    | {
        kind: 'historical_pr';
        sourceUrl: string;
        repositoryLicense: string;
        collectedAt: string;
      };
}

export const ReplayCaseSchema = z.object({
  id: z.string().trim().min(1),
  context: PullRequestContextSchema,
  expectedFindings: z.array(z.object({
    ruleId: z.string().trim().min(1),
    path: z.string().trim().min(1),
    line: z.number().int().positive(),
    category: z.string().trim().min(1).optional(),
    severity: z.enum(['critical', 'high', 'medium', 'low']).optional(),
    title: z.string().trim().min(1).max(160).optional(),
    notes: z.string().trim().min(1).max(2_000).optional()
  })),
  approval: z.discriminatedUnion('status', [
    z.object({ status: z.literal('candidate') }),
    z.object({
      status: z.literal('approved'),
      approvedBy: z.string().trim().min(2),
      approvedAt: z.string().datetime({ offset: true }),
      notes: z.string().trim().min(1).optional()
    })
  ]),
  provenance: z.discriminatedUnion('kind', [
    z.object({ kind: z.literal('fixture') }),
    z.object({
      kind: z.literal('historical_pr'),
      sourceUrl: z.string().url(),
      repositoryLicense: z.string().trim().min(1),
      collectedAt: z.string().datetime({ offset: true })
    })
  ])
});

export function isApprovedHistoricalReplayCase(item: ReplayCase): boolean {
  return item.approval.status === 'approved' && item.provenance.kind === 'historical_pr';
}

export const RootCauseEvidenceSchema = z.object({
  evidenceId: z.string().trim().min(1).max(160).optional(),
  kind: z.enum(['diff', 'base_source', 'head_source', 'symbol', 'caller', 'test', 'config', 'build', 'execution']).default('diff'),
  path: z.string().trim().min(1),
  startLine: z.number().int().positive(),
  endLine: z.number().int().positive(),
  revision: z.enum(['base', 'head', 'both']).default('head'),
  side: z.enum(['LEFT', 'RIGHT']).optional(),
  symbol: z.string().trim().min(1).max(500).optional(),
  fact: z.string().trim().min(1).max(2_000)
}).superRefine((value, context) => {
  if (value.endLine < value.startLine) {
    context.addIssue({ code: z.ZodIssueCode.custom, path: ['endLine'], message: 'endLine must be greater than or equal to startLine' });
  }
  if (value.kind === 'diff' && !value.side) {
    context.addIssue({ code: z.ZodIssueCode.custom, path: ['side'], message: 'Diff evidence requires LEFT or RIGHT side.' });
  }
  if (value.kind === 'diff' && value.side === 'LEFT' && value.revision !== 'base') {
    context.addIssue({ code: z.ZodIssueCode.custom, path: ['revision'], message: 'LEFT diff evidence must reference the base revision.' });
  }
  if (value.kind === 'diff' && value.side === 'RIGHT' && value.revision !== 'head') {
    context.addIssue({ code: z.ZodIssueCode.custom, path: ['revision'], message: 'RIGHT diff evidence must reference the head revision.' });
  }
  if (value.kind === 'base_source' && value.revision !== 'base') {
    context.addIssue({ code: z.ZodIssueCode.custom, path: ['revision'], message: 'base_source evidence must reference the base revision.' });
  }
  if (value.kind === 'head_source' && value.revision !== 'head') {
    context.addIssue({ code: z.ZodIssueCode.custom, path: ['revision'], message: 'head_source evidence must reference the head revision.' });
  }
});

export type RootCauseEvidence = z.infer<typeof RootCauseEvidenceSchema>;

export const RootCauseLabelSchema = z.object({
  rootCauseId: z.string().trim().min(1).max(160),
  category: FindingCategorySchema,
  severity: z.enum(['critical', 'high', 'medium', 'low']),
  claim: z.string().trim().min(1).max(2_000),
  trigger: z.string().trim().min(1).max(2_000),
  impact: z.string().trim().min(1).max(2_000),
  evidence: z.array(RootCauseEvidenceSchema).min(1),
  affectedSymbols: z.array(z.string().trim().min(1).max(500)).max(100).default([]),
  acceptableFix: z.string().trim().min(1).max(2_000).optional(),
  verification: z.string().trim().min(1).max(2_000),
  confidence: z.enum(['certain', 'likely', 'uncertain']),
  reviewerUncertainty: z.string().trim().min(1).max(2_000).optional(),
  notes: z.string().trim().min(1).max(2_000).optional()
});

export type RootCauseLabel = z.infer<typeof RootCauseLabelSchema>;

const SafeContextPathSchema = z.string().trim().min(1).max(1_000).refine((value) => {
  const normalized = value.replaceAll('\\', '/');
  return !normalized.startsWith('/') && !/^[A-Za-z]:\//.test(normalized) && !normalized.split('/').includes('..');
}, { message: 'Context path must be repository-relative and traversal-free.' });

export const NeutralContextFileSchema = z.object({
  path: SafeContextPathSchema,
  revision: z.enum(['base', 'head']),
  role: z.enum(['source', 'test', 'build', 'documentation', 'configuration', 'other']),
  language: z.string().trim().min(1).max(80).optional(),
  content: z.string().max(1_000_000)
});

export const NeutralContextSymbolSchema = z.object({
  id: z.string().trim().min(1).max(500),
  name: z.string().trim().min(1).max(500),
  kind: z.string().trim().min(1).max(80),
  path: SafeContextPathSchema,
  revision: z.enum(['base', 'head']),
  startLine: z.number().int().positive(),
  endLine: z.number().int().positive()
}).refine((value) => value.endLine >= value.startLine, {
  message: 'Symbol endLine must be greater than or equal to startLine.'
});

export const NeutralContextRelationshipSchema = z.object({
  fromSymbolId: z.string().trim().min(1).max(500),
  toSymbolId: z.string().trim().min(1).max(500),
  type: z.enum(['calls', 'implements', 'extends', 'tests', 'references']),
  evidencePath: SafeContextPathSchema,
  evidenceLine: z.number().int().positive()
});

export const ReviewContextBundleSchema = z.object({
  version: z.literal(1),
  caseId: z.string().trim().min(1).max(160),
  packetId: z.string().trim().min(1).max(160),
  digest: z.string().regex(/^[0-9a-f]{64}$/i),
  generatedAt: z.string().datetime({ offset: true }),
  baseSha: z.string().trim().min(7).max(64),
  headSha: z.string().trim().min(7).max(64),
  files: z.array(NeutralContextFileSchema).max(2_000),
  symbols: z.array(NeutralContextSymbolSchema).max(100_000).default([]),
  relationships: z.array(NeutralContextRelationshipSchema).max(250_000).default([]),
  limitations: z.array(z.string().trim().min(1).max(1_000)).max(100).default([])
}).superRefine((value, context) => {
  const fileKeys = new Set(value.files.map((file) => `${file.revision}:${file.path}`));
  if (fileKeys.size !== value.files.length) {
    context.addIssue({ code: z.ZodIssueCode.custom, path: ['files'], message: 'Context files must be unique by revision and path.' });
  }
  const symbolIds = new Set(value.symbols.map((symbol) => symbol.id));
  if (symbolIds.size !== value.symbols.length) {
    context.addIssue({ code: z.ZodIssueCode.custom, path: ['symbols'], message: 'Context symbol ids must be unique.' });
  }
  value.symbols.forEach((symbol, index) => {
    if (!fileKeys.has(`${symbol.revision}:${symbol.path}`)) {
      context.addIssue({ code: z.ZodIssueCode.custom, path: ['symbols', index, 'path'], message: 'Symbol must reference a bundled file.' });
    }
  });
  value.relationships.forEach((relationship, index) => {
    if (!symbolIds.has(relationship.fromSymbolId) || !symbolIds.has(relationship.toSymbolId)) {
      context.addIssue({ code: z.ZodIssueCode.custom, path: ['relationships', index], message: 'Relationship symbols must exist in the bundle.' });
    }
  });
});

export type ReviewContextBundle = z.infer<typeof ReviewContextBundleSchema>;

function canonicalJson(value: unknown): string {
  if (value === null || typeof value !== 'object') return JSON.stringify(value);
  if (Array.isArray(value)) return `[${value.map(canonicalJson).join(',')}]`;
  return `{${Object.entries(value as Record<string, unknown>)
    .filter(([, item]) => item !== undefined)
    .sort(([left], [right]) => left.localeCompare(right))
    .map(([key, item]) => `${JSON.stringify(key)}:${canonicalJson(item)}`)
    .join(',')}}`;
}

export function computeReviewContextBundleDigest(value: Omit<ReviewContextBundle, 'digest'>): string {
  return createHash('sha256').update(canonicalJson(value), 'utf8').digest('hex');
}

export const ReviewWorkbenchDecisionSchema = z.object({
  status: z.enum(['frozen', 'deferred']),
  mode: z.enum(['gold', 'assisted']),
  reviewer: z.string().trim().min(2).max(80),
  contextPacketId: z.string().trim().min(1).max(160),
  predictionVisible: z.boolean(),
  rootCauses: z.array(RootCauseLabelSchema).max(100),
  notes: z.string().trim().min(1).max(2_000).optional(),
  updatedAt: z.string().datetime({ offset: true }),
  protocol: z.literal('reasoning-v1')
}).superRefine((value, context) => {
  if (value.mode === 'gold' && value.predictionVisible) {
    context.addIssue({ code: z.ZodIssueCode.custom, path: ['predictionVisible'], message: 'Gold decisions must remain prediction-blind.' });
  }
  if (value.status === 'deferred' && value.rootCauses.length > 0) {
    context.addIssue({ code: z.ZodIssueCode.custom, path: ['rootCauses'], message: 'Deferred decisions cannot freeze root causes.' });
  }
  const ids = value.rootCauses.map((rootCause) => rootCause.rootCauseId);
  if (new Set(ids).size !== ids.length) {
    context.addIssue({ code: z.ZodIssueCode.custom, path: ['rootCauses'], message: 'rootCauseId values must be unique within a decision.' });
  }
});

export type ReviewWorkbenchDecision = z.infer<typeof ReviewWorkbenchDecisionSchema>;

export const ReviewWorkbenchStoreSchema = z.object({
  version: z.literal(3),
  protocol: z.literal('reasoning-v1'),
  mode: z.enum(['gold', 'assisted']),
  updatedAt: z.string().datetime({ offset: true }),
  decisions: z.record(z.string(), ReviewWorkbenchDecisionSchema)
});

export type ReviewWorkbenchStore = z.infer<typeof ReviewWorkbenchStoreSchema>;

export const LegacyFindingDraftSchema = z.object({
  sourceProtocol: z.literal('blind-v1'),
  rootCauseId: z.string().trim().min(1).max(160),
  category: z.string().trim().min(1).max(80),
  severity: z.enum(['critical', 'high', 'medium', 'low']),
  claim: z.string().trim().min(1).max(2_000),
  evidence: z.array(RootCauseEvidenceSchema).min(1),
  incompleteFields: z.array(z.enum(['trigger', 'impact', 'verification', 'confidence'])).min(1)
});

export type LegacyFindingDraft = z.infer<typeof LegacyFindingDraftSchema>;

export const ReviewContextMaterialSchema = z.enum([
  'full_repository',
  'pull_request',
  'linked_issue',
  'build_descriptors',
  'project_documentation',
  'tests',
  'subsystem_map'
]);

export const ReviewContextPacketSchema = z.object({
  id: z.string().trim().min(1).max(160),
  digest: z.string().regex(/^[0-9a-f]{64}$/i),
  frozenAt: z.string().datetime({ offset: true }),
  materials: z.array(ReviewContextMaterialSchema).min(1),
  scopeBriefing: z.string().trim().min(1).max(4_000)
});

export const ReviewerQualificationSchema = z.object({
  primaryLanguages: z.array(z.string().trim().min(1).max(80)).min(1),
  yearsExperience: z.number().int().nonnegative().max(60),
  repositoryFamiliarity: z.enum(['maintainer', 'contributor', 'calibrated_external']),
  calibrationSetId: z.string().trim().min(1).max(160),
  calibrationScore: z.number().min(0.8).max(1),
  calibrationCompletedAt: z.string().datetime({ offset: true })
});

export const BlindRootCauseReviewSchema = z.object({
  reviewerId: z.string().trim().min(2).max(120),
  submittedAt: z.string().datetime({ offset: true }),
  contextPacketId: z.string().trim().min(1).max(160),
  independent: z.literal(true),
  predictionVisible: z.literal(false),
  qualification: ReviewerQualificationSchema,
  rootCauses: z.array(RootCauseLabelSchema)
});

export const Phase0CaseSchema = z.object({
  id: z.string().trim().min(1),
  repository: z.string().regex(/^[^/\s]+\/[^/\s]+$/),
  pullNumber: z.number().int().positive(),
  baseSha: z.string().regex(/^[0-9a-f]{40}$/i),
  headSha: z.string().regex(/^[0-9a-f]{40}$/i),
  provenance: z.object({
    sourceUrl: z.string().url(),
    repositoryLicense: z.string().trim().min(1),
    permissionBasis: z.enum(['public_license', 'repository_owner_authorization']),
    collectedAt: z.string().datetime({ offset: true })
  }),
  contextPacket: ReviewContextPacketSchema,
  blindReviews: z.array(BlindRootCauseReviewSchema).min(2),
  adjudication: z.object({
    adjudicatedBy: z.string().trim().min(2).max(120),
    adjudicatedAt: z.string().datetime({ offset: true }),
    conflictCount: z.number().int().nonnegative(),
    rootCauses: z.array(RootCauseLabelSchema)
  })
}).superRefine((value, context) => {
  const requiredMaterials = ['full_repository', 'pull_request', 'build_descriptors', 'project_documentation', 'tests'] as const;
  for (const material of requiredMaterials) {
    if (!value.contextPacket.materials.includes(material)) {
      context.addIssue({
        code: z.ZodIssueCode.custom,
        path: ['contextPacket', 'materials'],
        message: `Context packet must include ${material}.`
      });
    }
  }
  const reviewers = new Set(value.blindReviews.map((review) => review.reviewerId));
  if (reviewers.size < 2) {
    context.addIssue({ code: z.ZodIssueCode.custom, path: ['blindReviews'], message: 'Two distinct blind reviewers are required.' });
  }
  if (value.adjudication.conflictCount > 0 && reviewers.has(value.adjudication.adjudicatedBy)) {
    context.addIssue({
      code: z.ZodIssueCode.custom,
      path: ['adjudication', 'adjudicatedBy'],
      message: 'A third reviewer must adjudicate conflicts.'
    });
  }
  for (const [index, review] of value.blindReviews.entries()) {
    if (review.contextPacketId !== value.contextPacket.id) {
      context.addIssue({
        code: z.ZodIssueCode.custom,
        path: ['blindReviews', index, 'contextPacketId'],
        message: 'Every blind review must use the frozen context packet.'
      });
    }
    if (Date.parse(review.submittedAt) < Date.parse(value.contextPacket.frozenAt)) {
      context.addIssue({
        code: z.ZodIssueCode.custom,
        path: ['blindReviews', index, 'submittedAt'],
        message: 'Blind review cannot predate the frozen context packet.'
      });
    }
    if (Date.parse(review.qualification.calibrationCompletedAt) > Date.parse(review.submittedAt)) {
      context.addIssue({
        code: z.ZodIssueCode.custom,
        path: ['blindReviews', index, 'qualification', 'calibrationCompletedAt'],
        message: 'Reviewer calibration must be completed before blind review.'
      });
    }
  }
  if (value.blindReviews.some((review) => Date.parse(value.adjudication.adjudicatedAt) <= Date.parse(review.submittedAt))) {
    context.addIssue({
      code: z.ZodIssueCode.custom,
      path: ['adjudication', 'adjudicatedAt'],
      message: 'Adjudication must occur after all blind reviews.'
    });
  }
  const rootCauseIds = value.adjudication.rootCauses.map((rootCause) => rootCause.rootCauseId);
  if (new Set(rootCauseIds).size !== rootCauseIds.length) {
    context.addIssue({
      code: z.ZodIssueCode.custom,
      path: ['adjudication', 'rootCauses'],
      message: 'Adjudicated rootCauseId values must be unique within a case.'
    });
  }
});

export type Phase0Case = z.infer<typeof Phase0CaseSchema>;

export const ReviewerSessionSchema = z.object({
  caseId: z.string().trim().min(1),
  reviewerId: z.string().trim().min(2).max(120),
  arm: z.enum(['codelens', 'control']),
  startedAt: z.string().datetime({ offset: true }),
  completedAt: z.string().datetime({ offset: true }),
  activeSeconds: z.number().positive().max(8 * 60 * 60),
  decision: z.enum(['approve', 'request_changes', 'comment']),
  excludedReason: z.string().trim().min(1).max(500).optional()
}).superRefine((value, context) => {
  if (Date.parse(value.completedAt) <= Date.parse(value.startedAt)) {
    context.addIssue({ code: z.ZodIssueCode.custom, path: ['completedAt'], message: 'completedAt must be after startedAt.' });
  }
});

export type ReviewerSession = z.infer<typeof ReviewerSessionSchema>;

export interface Phase0EvidenceThresholds {
  minCases: number;
  minPositiveRootCauses: number;
  minTimedSessions: number;
}

export interface Phase0EvidenceReport {
  passed: boolean;
  cases: number;
  positiveCases: number;
  positiveRootCauses: number;
  dualReviewedCases: number;
  adjudicatedCases: number;
  timedSessions: number;
  timedCodelensSessions: number;
  timedControlSessions: number;
  failures: string[];
  thresholds: Phase0EvidenceThresholds;
}

export function evaluatePhase0Evidence(
  rawCases: unknown[],
  rawSessions: unknown[],
  thresholds: Phase0EvidenceThresholds = { minCases: 50, minPositiveRootCauses: 20, minTimedSessions: 20 }
): Phase0EvidenceReport {
  if (!Number.isInteger(thresholds.minCases) || thresholds.minCases <= 0) throw new Error('minCases must be positive.');
  if (!Number.isInteger(thresholds.minPositiveRootCauses) || thresholds.minPositiveRootCauses <= 0) {
    throw new Error('minPositiveRootCauses must be positive.');
  }
  if (!Number.isInteger(thresholds.minTimedSessions) || thresholds.minTimedSessions <= 1) {
    throw new Error('minTimedSessions must be greater than one.');
  }

  const failures: string[] = [];
  const cases: Phase0Case[] = [];
  rawCases.forEach((value, index) => {
    const parsed = Phase0CaseSchema.safeParse(value);
    if (parsed.success) cases.push(parsed.data);
    else failures.push(`case[${index}] invalid: ${parsed.error.issues.map((issue) => issue.message).join('; ')}`);
  });
  const sessions: ReviewerSession[] = [];
  rawSessions.forEach((value, index) => {
    const parsed = ReviewerSessionSchema.safeParse(value);
    if (parsed.success) sessions.push(parsed.data);
    else failures.push(`session[${index}] invalid: ${parsed.error.issues.map((issue) => issue.message).join('; ')}`);
  });

  const uniqueCases = new Map(cases.map((item) => [item.id, item]));
  if (uniqueCases.size !== cases.length) failures.push('case ids must be unique.');
  const knownCaseIds = new Set(uniqueCases.keys());
  const eligibleSessions = sessions.filter((session) => !session.excludedReason);
  for (const session of sessions) {
    if (!knownCaseIds.has(session.caseId)) failures.push(`session references unknown case ${session.caseId}.`);
  }
  const uniqueSessionKeys = new Set(sessions.map((session) => `${session.caseId}\n${session.reviewerId}\n${session.arm}`));
  if (uniqueSessionKeys.size !== sessions.length) failures.push('reviewer sessions must be unique by case, reviewer, and arm.');
  const positiveCases = cases.filter((item) => item.adjudication.rootCauses.length > 0).length;
  const positiveRootCauses = cases.reduce((sum, item) => sum + item.adjudication.rootCauses.length, 0);
  const dualReviewedCases = cases.filter((item) => new Set(item.blindReviews.map((review) => review.reviewerId)).size >= 2).length;
  const adjudicatedCases = cases.filter((item) => item.adjudication.adjudicatedBy.length > 0).length;
  const timedCodelensSessions = eligibleSessions.filter((session) => session.arm === 'codelens').length;
  const timedControlSessions = eligibleSessions.filter((session) => session.arm === 'control').length;

  if (cases.length < thresholds.minCases) failures.push(`cases ${cases.length} < ${thresholds.minCases}`);
  if (positiveRootCauses < thresholds.minPositiveRootCauses) {
    failures.push(`positive root causes ${positiveRootCauses} < ${thresholds.minPositiveRootCauses}`);
  }
  if (dualReviewedCases !== cases.length) failures.push(`dual-reviewed cases ${dualReviewedCases} != ${cases.length}`);
  if (adjudicatedCases !== cases.length) failures.push(`adjudicated cases ${adjudicatedCases} != ${cases.length}`);
  if (eligibleSessions.length < thresholds.minTimedSessions) {
    failures.push(`timed sessions ${eligibleSessions.length} < ${thresholds.minTimedSessions}`);
  }
  if (timedCodelensSessions === 0 || timedControlSessions === 0) failures.push('both codelens and control timing arms are required.');

  return {
    passed: failures.length === 0,
    cases: cases.length,
    positiveCases,
    positiveRootCauses,
    dualReviewedCases,
    adjudicatedCases,
    timedSessions: eligibleSessions.length,
    timedCodelensSessions,
    timedControlSessions,
    failures,
    thresholds
  };
}

export function reverseUnifiedPatch(patch: string): string {
  return patch.split('\n').map((line) => {
    const hunk = line.match(/^@@ -(\d+(?:,\d+)?) \+(\d+(?:,\d+)?) @@(.*)$/);
    if (hunk) return `@@ -${hunk[2]} +${hunk[1]} @@${hunk[3]}`;
    if (line.startsWith('+++') || line.startsWith('---')) return line;
    if (line.startsWith('+')) return `-${line.slice(1)}`;
    if (line.startsWith('-')) return `+${line.slice(1)}`;
    return line;
  }).join('\n');
}

export interface BenchmarkReport {
  cases: number;
  positiveCases: number;
  negativeCases: number;
  expected: number;
  predicted: number;
  truePositive: number;
  falsePositive: number;
  falseNegative: number;
  precision: number | null;
  recall: number | null;
  latencyMs: { p50: number; p95: number; max: number };
  confidence95?: {
    precision: { lower: number; upper: number };
    recall: { lower: number; upper: number };
  };
  byCategory?: Record<string, {
    expected: number;
    predicted: number;
    truePositive: number;
    falsePositive: number;
    falseNegative: number;
    precision: number | null;
    recall: number | null;
  }>;
  falsePositives?: BenchmarkMismatch[];
  falseNegatives?: BenchmarkMismatch[];
  insufficientSampleWarning?: string;
}

export interface BenchmarkMismatch {
  caseId: string;
  repository: string;
  pullNumber: number;
  sourceUrl?: string;
  ruleId: string;
  path: string;
  line: number;
}

export interface BenchmarkThresholds {
  minCases: number;
  minPositiveCases: number;
  minNegativeCases: number;
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
    ['positive case', thresholds.minPositiveCases],
    ['negative case', thresholds.minNegativeCases]
  ] as const) {
    if (!Number.isInteger(value) || value < 0) {
      throw new Error(`Benchmark minimum ${name} count must be a nonnegative integer.`);
    }
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
  if (report.positiveCases < thresholds.minPositiveCases) {
    failures.push(`positive cases ${report.positiveCases} < ${thresholds.minPositiveCases}`);
  }
  if (report.negativeCases < thresholds.minNegativeCases) {
    failures.push(`negative cases ${report.negativeCases} < ${thresholds.minNegativeCases}`);
  }
  if (report.precision === null) {
    failures.push('precision unavailable: no predicted findings');
  } else if (report.precision < thresholds.minPrecision) {
    failures.push(`precision ${report.precision.toFixed(4)} < ${thresholds.minPrecision.toFixed(4)}`);
  }
  if (report.recall === null) {
    failures.push('recall unavailable: no expected findings');
  } else if (report.recall < thresholds.minRecall) {
    failures.push(`recall ${report.recall.toFixed(4)} < ${thresholds.minRecall.toFixed(4)}`);
  }
  if (report.latencyMs.p95 > thresholds.maxP95LatencyMs) {
    failures.push(`p95 latency ${report.latencyMs.p95}ms > ${thresholds.maxP95LatencyMs}ms`);
  }
  return { passed: failures.length === 0, failures, thresholds };
}

function evaluationCategory(value: { ruleId?: string | undefined; category?: string | undefined }): string {
  if (value.category) return value.category.replaceAll('-', '_');
  const ruleId = value.ruleId ?? 'unknown';
  return (ruleId.startsWith('human/') ? ruleId.slice('human/'.length) : ruleId.split('/')[0] ?? 'unknown')
    .replaceAll('-', '_');
}

function key(value: { ruleId?: string | undefined; category?: string | undefined; path: string; line: number }): string {
  return `${evaluationCategory(value)}|${value.path}|${value.line}`;
}

function percentile(sorted: number[], percent: number): number {
  if (!sorted.length) return 0;
  return sorted[Math.min(sorted.length - 1, Math.ceil(sorted.length * percent) - 1)] ?? 0;
}

function wilson(successes: number, total: number): { lower: number; upper: number } {
  if (total === 0) return { lower: 0, upper: 1 };
  const z = 1.959963984540054;
  const proportion = successes / total;
  const denominator = 1 + z * z / total;
  const center = (proportion + z * z / (2 * total)) / denominator;
  const margin = z * Math.sqrt((proportion * (1 - proportion) + z * z / (4 * total)) / total) / denominator;
  return {
    lower: Number(Math.max(0, center - margin).toFixed(4)),
    upper: Number(Math.min(1, center + margin).toFixed(4))
  };
}

export async function runBenchmark(cases: ReplayCase[]): Promise<BenchmarkReport> {
  const reviewer = new DeterministicRiskReviewer();
  const verifier = new EvidenceVerifier({ maxPublished: 100 });
  let expected = 0;
  let predicted = 0;
  let truePositive = 0;
  const latency: number[] = [];
  const falsePositives: BenchmarkMismatch[] = [];
  const falseNegatives: BenchmarkMismatch[] = [];
  const categoryCounts = new Map<string, { expected: number; predicted: number; truePositive: number }>();

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
    const sourceUrl = item.provenance.kind === 'historical_pr' ? item.provenance.sourceUrl : undefined;
    const mismatch = (finding: { ruleId?: string; path: string; line: number }): BenchmarkMismatch => ({
      caseId: item.id,
      repository: `${item.context.owner}/${item.context.repo}`,
      pullNumber: item.context.number,
      ...(sourceUrl ? { sourceUrl } : {}),
      ruleId: finding.ruleId ?? 'unknown',
      path: finding.path,
      line: finding.line
    });
    for (const finding of findings) {
      const findingKey = key(finding);
      const category = evaluationCategory(finding);
      const counts = categoryCounts.get(category) ?? { expected: 0, predicted: 0, truePositive: 0 };
      counts.predicted += 1;
      if (expectedKeys.has(findingKey)) counts.truePositive += 1;
      else falsePositives.push(mismatch(finding));
      categoryCounts.set(category, counts);
    }
    for (const finding of item.expectedFindings) {
      const category = evaluationCategory(finding);
      const counts = categoryCounts.get(category) ?? { expected: 0, predicted: 0, truePositive: 0 };
      counts.expected += 1;
      categoryCounts.set(category, counts);
      if (!predictedKeys.has(key(finding))) falseNegatives.push(mismatch(finding));
    }
  }

  const falsePositive = predicted - truePositive;
  const falseNegative = expected - truePositive;
  const sorted = latency.sort((left, right) => left - right);
  const positiveCases = cases.filter((item) => item.expectedFindings.length > 0).length;
  const byCategory = Object.fromEntries([...categoryCounts.entries()].sort(([left], [right]) => left.localeCompare(right)).map(([category, counts]) => {
    const falsePositive = counts.predicted - counts.truePositive;
    const falseNegative = counts.expected - counts.truePositive;
    return [category, {
      ...counts,
      falsePositive,
      falseNegative,
      precision: counts.predicted ? counts.truePositive / counts.predicted : null,
      recall: counts.expected ? counts.truePositive / counts.expected : null
    }];
  }));
  return {
    cases: cases.length,
    positiveCases,
    negativeCases: cases.length - positiveCases,
    expected,
    predicted,
    truePositive,
    falsePositive,
    falseNegative,
    precision: predicted ? truePositive / predicted : null,
    recall: expected ? truePositive / expected : null,
    latencyMs: {
      p50: Number(percentile(sorted, 0.5).toFixed(2)),
      p95: Number(percentile(sorted, 0.95).toFixed(2)),
      max: Number((sorted.at(-1) ?? 0).toFixed(2))
    },
    confidence95: {
      precision: wilson(truePositive, predicted),
      recall: wilson(truePositive, expected)
    },
    byCategory,
    falsePositives,
    falseNegatives,
    ...(cases.length < 100
      ? { insufficientSampleWarning: `Only ${cases.length}/100 historical PR cases are loaded.` }
      : {})
  };
}
