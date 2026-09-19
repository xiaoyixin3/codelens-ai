import { z } from 'zod';

export const ReviewRunStatusSchema = z.enum([
  'queued',
  'in_progress',
  'completed',
  'failed',
  'stale',
  'skipped'
]);
export type ReviewRunStatus =
  | 'queued'
  | 'in_progress'
  | 'completed'
  | 'failed'
  | 'stale'
  | 'skipped';

export const ReviewJobSchema = z.object({
  reviewRunId: z.string().uuid(),
  installationId: z.number().int().positive(),
  owner: z.string().min(1),
  repo: z.string().min(1),
  pullNumber: z.number().int().positive(),
  baseSha: z.string().min(7),
  headSha: z.string().min(7)
});
export interface ReviewJob {
  reviewRunId: string;
  installationId: number;
  owner: string;
  repo: string;
  pullNumber: number;
  baseSha: string;
  headSha: string;
}

export const ChangedFileSchema = z.object({
  path: z.string().min(1),
  previousPath: z.string().optional(),
  status: z.enum(['added', 'modified', 'removed', 'renamed', 'copied', 'changed', 'unchanged']),
  additions: z.number().int().nonnegative(),
  deletions: z.number().int().nonnegative(),
  patch: z.string().default('')
});
export interface ChangedFile {
  path: string;
  previousPath?: string;
  status: 'added' | 'modified' | 'removed' | 'renamed' | 'copied' | 'changed' | 'unchanged';
  additions: number;
  deletions: number;
  patch: string;
}

export const PullRequestContextSchema = z.object({
  owner: z.string(),
  repo: z.string(),
  number: z.number().int().positive(),
  title: z.string(),
  body: z.string().default(''),
  baseSha: z.string(),
  headSha: z.string(),
  files: z.array(ChangedFileSchema)
});
export interface PullRequestContext {
  owner: string;
  repo: string;
  number: number;
  title: string;
  body: string;
  baseSha: string;
  headSha: string;
  files: ChangedFile[];
}

export const FileSummarySchema = z.object({
  path: z.string(),
  change: z.string()
});

export const ImpactSummarySchema = z.object({
  level: z.enum(['low', 'medium', 'high']),
  score: z.number().min(0).max(100),
  changedSymbols: z.number().int().nonnegative(),
  impactedSymbols: z.number().int().nonnegative(),
  topPaths: z.array(
    z.object({
      changedName: z.string(),
      impactedName: z.string(),
      depth: z.number().int().positive(),
      score: z.number().nonnegative()
    })
  ),
  coverageWarning: z.string()
});

export const FindingCategorySchema = z.enum([
  'correctness',
  'security',
  'data_integrity',
  'concurrency',
  'performance',
  'architecture',
  'test_gap'
]);
export type FindingCategory =
  | 'correctness'
  | 'security'
  | 'data_integrity'
  | 'concurrency'
  | 'performance'
  | 'architecture'
  | 'test_gap';

export const FindingSeveritySchema = z.enum(['critical', 'high', 'medium', 'low']);
export type FindingSeverity = 'critical' | 'high' | 'medium' | 'low';

export const FindingCandidateSchema = z.object({
  source: z.enum(['deterministic', 'llm']),
  ruleId: z.string().min(1).optional(),
  category: FindingCategorySchema,
  severity: FindingSeveritySchema,
  confidence: z.number().min(0).max(1),
  title: z.string().min(1).max(120),
  claim: z.string().min(1).max(1_000),
  suggestion: z.string().min(1).max(1_000),
  verification: z.string().min(1).max(1_000),
  path: z.string().min(1),
  line: z.number().int().positive(),
  excerpt: z.string().optional()
});
export interface FindingCandidate {
  source: 'deterministic' | 'llm';
  ruleId?: string;
  category: FindingCategory;
  severity: FindingSeverity;
  confidence: number;
  title: string;
  claim: string;
  suggestion: string;
  verification: string;
  path: string;
  line: number;
  excerpt?: string;
}

export interface FindingEvidence {
  path: string;
  startLine: number;
  endLine: number;
  side: 'RIGHT';
  excerptHash: string;
  evidenceType: 'diff';
}

export interface ReviewFinding extends FindingCandidate {
  fingerprint: string;
  status: 'verified' | 'rejected';
  rejectionReason?: string;
  publishable: boolean;
  evidence?: FindingEvidence;
}

export const FindingReviewSummarySchema = z.object({
  candidates: z.number().int().nonnegative(),
  verified: z.number().int().nonnegative(),
  published: z.number().int().nonnegative(),
  rejected: z.number().int().nonnegative(),
  items: z.array(
    FindingCandidateSchema.extend({
      fingerprint: z.string().min(1),
      status: z.enum(['verified', 'rejected']),
      rejectionReason: z.string().optional(),
      publishable: z.boolean(),
      evidence: z.object({
        path: z.string(),
        startLine: z.number().int().positive(),
        endLine: z.number().int().positive(),
        side: z.literal('RIGHT'),
        excerptHash: z.string(),
        evidenceType: z.literal('diff')
      }).optional()
    })
  )
});

export const ChangeSummarySchema = z.object({
  intent: z.string().min(1),
  overview: z.string().min(1),
  files: z.array(FileSummarySchema),
  riskLevel: z.enum(['low', 'medium', 'high']),
  riskReasons: z.array(z.string()),
  coverage: z.object({
    reviewedFiles: z.number().int().nonnegative(),
    totalFiles: z.number().int().nonnegative(),
    truncated: z.boolean()
  }),
  impact: ImpactSummarySchema.optional(),
  findings: FindingReviewSummarySchema.optional(),
  policy: z.object({
    configHash: z.string(),
    rules: z.number().int().nonnegative(),
    includedFiles: z.number().int().nonnegative(),
    excludedFiles: z.number().int().nonnegative(),
    blocking: z.boolean(),
    language: z.enum(['en', 'zh']),
    warnings: z.array(z.string())
  }).optional()
});
export interface ChangeSummary {
  intent: string;
  overview: string;
  files: Array<{ path: string; change: string }>;
  riskLevel: 'low' | 'medium' | 'high';
  riskReasons: string[];
  coverage: {
    reviewedFiles: number;
    totalFiles: number;
    truncated: boolean;
  };
  impact?: {
    level: 'low' | 'medium' | 'high';
    score: number;
    changedSymbols: number;
    impactedSymbols: number;
    topPaths: Array<{
      changedName: string;
      impactedName: string;
      depth: number;
      score: number;
    }>;
    coverageWarning: string;
  };
  findings?: {
    candidates: number;
    verified: number;
    published: number;
    rejected: number;
    items: ReviewFinding[];
  };
  policy?: {
    configHash: string;
    rules: number;
    includedFiles: number;
    excludedFiles: number;
    blocking: boolean;
    language: 'en' | 'zh';
    warnings: string[];
  };
}

export interface ReviewRun {
  id: string;
  repositoryId: number;
  pullNumber: number;
  baseSha: string;
  headSha: string;
  status: ReviewRunStatus;
  pipelineVersion: string;
  configHash: string;
  trigger: 'webhook' | 'rerun';
  requestKey: string;
  summary?: ChangeSummary;
  errorCode?: string;
  errorDetail?: string;
  createdAt: Date;
  updatedAt: Date;
}

export interface CheckRunWebhook {
  action: 'rerequested';
  installation: { id: number };
  repository: {
    id: number;
    name: string;
    owner: { login: string };
  };
  check_run: {
    id: number;
    name: string;
    head_sha: string;
    app?: { id: number };
    pull_requests: Array<{
      number: number;
      base: { sha: string };
      head: { sha: string };
    }>;
  };
}

export interface IssueCommentWebhook {
  action: 'created';
  installation: { id: number };
  repository: { id: number };
  issue: { number: number; pull_request?: unknown };
  comment: { id: number; body: string; user: { login: string } };
}

export interface PullRequestWebhook {
  action: string;
  installation: { id: number };
  repository: {
    id: number;
    name: string;
    owner: { login: string };
  };
  pull_request: {
    number: number;
    base: { sha: string };
    head: { sha: string };
  };
}

export const REVIEW_PIPELINE_VERSION = 'v1.0.0-beta.1';
export const DEFAULT_CONFIG_HASH = 'default-v1';
