import { createHash, randomUUID } from 'node:crypto';
import postgres, { type Sql } from 'postgres';
import type { ChangeSummary, ReviewRun, ReviewRunStatus } from '@codelens/contracts';

export interface CreateReviewRunInput {
  repositoryId: number;
  pullNumber: number;
  baseSha: string;
  headSha: string;
  pipelineVersion: string;
  configHash: string;
  trigger?: 'webhook' | 'rerun';
  requestKey?: string;
}

export interface FindingFeedbackInput {
  repositoryId: number;
  pullNumber: number;
  fingerprintPrefix: string;
  verdict: 'helpful' | 'false_positive';
  actorLogin: string;
  sourceCommentId: number;
}

export interface DeliveryInput {
  deliveryId: string;
  event: string;
  action?: string;
  rawBody: Buffer;
  signatureValid: boolean;
}

export interface Publication {
  reviewRunId: string;
  headSha: string;
  checkRunId?: number;
  summaryCommentId?: number;
}

export interface ReviewStore {
  healthCheck(): Promise<void>;
  claimDelivery(input: DeliveryInput): Promise<boolean>;
  markDeliveryProcessed(deliveryId: string, errorDetail?: string): Promise<void>;
  createOrGetReviewRun(input: CreateReviewRunInput): Promise<{ run: ReviewRun; created: boolean }>;
  getReviewRun(id: string): Promise<ReviewRun | undefined>;
  updateReviewRunConfig(id: string, configHash: string): Promise<void>;
  updateReviewRun(
    id: string,
    update: { status: ReviewRunStatus; summary?: ChangeSummary; errorCode?: string; errorDetail?: string }
  ): Promise<void>;
  getPublication(reviewRunId: string): Promise<Publication | undefined>;
  savePublication(publication: Publication): Promise<void>;
  saveFindingFeedback(input: FindingFeedbackInput): Promise<boolean>;
  close(): Promise<void>;
}

interface ReviewRunRow {
  id: string;
  github_repository_id: string;
  pull_number: number;
  base_sha: string;
  head_sha: string;
  status: ReviewRunStatus;
  pipeline_version: string;
  config_hash: string;
  trigger: 'webhook' | 'rerun';
  request_key: string;
  summary: ChangeSummary | null;
  error_code: string | null;
  error_detail: string | null;
  created_at: Date;
  updated_at: Date;
}

function mapReviewRun(row: ReviewRunRow): ReviewRun {
  return {
    id: row.id,
    repositoryId: Number(row.github_repository_id),
    pullNumber: row.pull_number,
    baseSha: row.base_sha,
    headSha: row.head_sha,
    status: row.status,
    pipelineVersion: row.pipeline_version,
    configHash: row.config_hash,
    trigger: row.trigger,
    requestKey: row.request_key,
    ...(row.summary ? { summary: row.summary } : {}),
    ...(row.error_code ? { errorCode: row.error_code } : {}),
    ...(row.error_detail ? { errorDetail: row.error_detail } : {}),
    createdAt: row.created_at,
    updatedAt: row.updated_at
  };
}

export class PostgresReviewStore implements ReviewStore {
  readonly #sql: Sql;

  constructor(databaseUrl: string) {
    this.#sql = postgres(databaseUrl, { max: 10 });
  }

  async healthCheck(): Promise<void> {
    await this.#sql`SELECT 1`;
  }

  async claimDelivery(input: DeliveryInput): Promise<boolean> {
    const payloadHash = createHash('sha256').update(input.rawBody).digest('hex');
    const rows = await this.#sql`
      INSERT INTO webhook_deliveries (
        delivery_id, event, action, payload_hash, signature_valid
      ) VALUES (
        ${input.deliveryId}, ${input.event}, ${input.action ?? null}, ${payloadHash}, ${input.signatureValid}
      )
      ON CONFLICT (delivery_id) DO NOTHING
      RETURNING delivery_id
    `;
    return rows.length === 1;
  }

  async markDeliveryProcessed(deliveryId: string, errorDetail?: string): Promise<void> {
    await this.#sql`
      UPDATE webhook_deliveries
      SET status = ${errorDetail ? 'failed' : 'processed'},
          error_detail = ${errorDetail ?? null},
          processed_at = now()
      WHERE delivery_id = ${deliveryId}
    `;
  }

  async createOrGetReviewRun(input: CreateReviewRunInput): Promise<{ run: ReviewRun; created: boolean }> {
    const id = randomUUID();
    const trigger = input.trigger ?? 'webhook';
    const requestKey = input.requestKey ?? 'automatic';
    const inserted = await this.#sql<ReviewRunRow[]>`
      INSERT INTO review_runs (
        id, github_repository_id, pull_number, base_sha, head_sha,
        status, pipeline_version, config_hash, trigger, request_key
      ) VALUES (
        ${id}, ${input.repositoryId}, ${input.pullNumber}, ${input.baseSha}, ${input.headSha},
        'queued', ${input.pipelineVersion}, ${input.configHash}, ${trigger}, ${requestKey}
      )
      ON CONFLICT (github_repository_id, pull_number, head_sha, pipeline_version, request_key)
      DO NOTHING
      RETURNING *
    `;

    if (inserted[0]) return { run: mapReviewRun(inserted[0]), created: true };

    const existing = await this.#sql<ReviewRunRow[]>`
      SELECT * FROM review_runs
      WHERE github_repository_id = ${input.repositoryId}
        AND pull_number = ${input.pullNumber}
        AND head_sha = ${input.headSha}
        AND pipeline_version = ${input.pipelineVersion}
        AND request_key = ${requestKey}
      LIMIT 1
    `;
    if (!existing[0]) throw new Error('Review run conflict resolved without an existing row.');
    return { run: mapReviewRun(existing[0]), created: false };
  }

  async getReviewRun(id: string): Promise<ReviewRun | undefined> {
    const rows = await this.#sql<ReviewRunRow[]>`SELECT * FROM review_runs WHERE id = ${id} LIMIT 1`;
    return rows[0] ? mapReviewRun(rows[0]) : undefined;
  }

  async updateReviewRunConfig(id: string, configHash: string): Promise<void> {
    await this.#sql`
      UPDATE review_runs SET config_hash = ${configHash}, updated_at = now() WHERE id = ${id}
    `;
  }

  async updateReviewRun(
    id: string,
    update: { status: ReviewRunStatus; summary?: ChangeSummary; errorCode?: string; errorDetail?: string }
  ): Promise<void> {
    await this.#sql`
      UPDATE review_runs
      SET status = ${update.status},
          summary = ${update.summary ? this.#sql.json(JSON.parse(JSON.stringify(update.summary))) : null},
          error_code = ${update.errorCode ?? null},
          error_detail = ${update.errorDetail ?? null},
          started_at = CASE WHEN ${update.status} = 'in_progress' THEN COALESCE(started_at, now()) ELSE started_at END,
          completed_at = CASE WHEN ${update.status} IN ('completed', 'failed', 'stale', 'skipped') THEN now() ELSE completed_at END,
          updated_at = now()
      WHERE id = ${id}
    `;
  }

  async getPublication(reviewRunId: string): Promise<Publication | undefined> {
    const rows = await this.#sql<{
      review_run_id: string;
      head_sha: string;
      check_run_id: string | null;
      summary_comment_id: string | null;
    }[]>`
      SELECT review_run_id, head_sha, check_run_id, summary_comment_id
      FROM publications WHERE review_run_id = ${reviewRunId} LIMIT 1
    `;
    const row = rows[0];
    if (!row) return undefined;
    return {
      reviewRunId: row.review_run_id,
      headSha: row.head_sha,
      ...(row.check_run_id ? { checkRunId: Number(row.check_run_id) } : {}),
      ...(row.summary_comment_id ? { summaryCommentId: Number(row.summary_comment_id) } : {})
    };
  }

  async savePublication(publication: Publication): Promise<void> {
    await this.#sql`
      INSERT INTO publications (
        id, review_run_id, head_sha, check_run_id, summary_comment_id
      ) VALUES (
        ${randomUUID()}, ${publication.reviewRunId}, ${publication.headSha},
        ${publication.checkRunId ?? null}, ${publication.summaryCommentId ?? null}
      )
      ON CONFLICT (review_run_id) DO UPDATE
      SET head_sha = EXCLUDED.head_sha,
          check_run_id = COALESCE(EXCLUDED.check_run_id, publications.check_run_id),
          summary_comment_id = COALESCE(EXCLUDED.summary_comment_id, publications.summary_comment_id),
          updated_at = now()
    `;
  }

  async saveFindingFeedback(input: FindingFeedbackInput): Promise<boolean> {
    const rows = await this.#sql`
      WITH matched AS (
        SELECT f.id
        FROM findings f
        JOIN review_runs r ON r.id = f.review_run_id
        WHERE r.github_repository_id = ${input.repositoryId}
          AND r.pull_number = ${input.pullNumber}
          AND f.fingerprint LIKE ${`${input.fingerprintPrefix}%`}
          AND f.status = 'verified'
        ORDER BY r.created_at DESC
        LIMIT 1
      )
      INSERT INTO finding_feedback (
        id, finding_id, verdict, actor_login, source_comment_id
      )
      SELECT ${randomUUID()}, matched.id, ${input.verdict}, ${input.actorLogin}, ${input.sourceCommentId}
      FROM matched
      ON CONFLICT (source_comment_id) DO NOTHING
      RETURNING id
    `;
    return rows.length === 1;
  }

  async close(): Promise<void> {
    await this.#sql.end();
  }
}

export class InMemoryReviewStore implements ReviewStore {
  readonly deliveries = new Set<string>();
  readonly runs = new Map<string, ReviewRun>();
  readonly publications = new Map<string, Publication>();
  readonly feedback: FindingFeedbackInput[] = [];

  async healthCheck(): Promise<void> {}

  async claimDelivery(input: DeliveryInput): Promise<boolean> {
    if (this.deliveries.has(input.deliveryId)) return false;
    this.deliveries.add(input.deliveryId);
    return true;
  }

  async markDeliveryProcessed(_deliveryId: string, _errorDetail?: string): Promise<void> {}

  async createOrGetReviewRun(input: CreateReviewRunInput): Promise<{ run: ReviewRun; created: boolean }> {
    const existing = [...this.runs.values()].find(
      (run) =>
        run.repositoryId === input.repositoryId &&
        run.pullNumber === input.pullNumber &&
        run.headSha === input.headSha &&
        run.pipelineVersion === input.pipelineVersion &&
        run.requestKey === (input.requestKey ?? 'automatic')
    );
    if (existing) return { run: existing, created: false };

    const now = new Date();
    const run: ReviewRun = {
      id: randomUUID(),
      repositoryId: input.repositoryId,
      pullNumber: input.pullNumber,
      baseSha: input.baseSha,
      headSha: input.headSha,
      status: 'queued',
      pipelineVersion: input.pipelineVersion,
      configHash: input.configHash,
      trigger: input.trigger ?? 'webhook',
      requestKey: input.requestKey ?? 'automatic',
      createdAt: now,
      updatedAt: now
    };
    this.runs.set(run.id, run);
    return { run, created: true };
  }

  async getReviewRun(id: string): Promise<ReviewRun | undefined> {
    return this.runs.get(id);
  }

  async updateReviewRunConfig(id: string, configHash: string): Promise<void> {
    const current = this.runs.get(id);
    if (!current) throw new Error(`Unknown review run ${id}`);
    this.runs.set(id, { ...current, configHash, updatedAt: new Date() });
  }

  async updateReviewRun(
    id: string,
    update: { status: ReviewRunStatus; summary?: ChangeSummary; errorCode?: string; errorDetail?: string }
  ): Promise<void> {
    const current = this.runs.get(id);
    if (!current) throw new Error(`Unknown review run ${id}`);
    const { errorCode: _errorCode, errorDetail: _errorDetail, ...withoutPreviousError } = current;
    this.runs.set(id, {
      ...withoutPreviousError,
      status: update.status,
      ...(update.summary ? { summary: update.summary } : {}),
      ...(update.errorCode ? { errorCode: update.errorCode } : {}),
      ...(update.errorDetail ? { errorDetail: update.errorDetail } : {}),
      updatedAt: new Date()
    });
  }

  async getPublication(reviewRunId: string): Promise<Publication | undefined> {
    return this.publications.get(reviewRunId);
  }

  async savePublication(publication: Publication): Promise<void> {
    this.publications.set(publication.reviewRunId, {
      ...this.publications.get(publication.reviewRunId),
      ...publication
    });
  }

  async saveFindingFeedback(input: FindingFeedbackInput): Promise<boolean> {
    if (this.feedback.some((item) => item.sourceCommentId === input.sourceCommentId)) return false;
    this.feedback.push(input);
    return true;
  }

  async close(): Promise<void> {}
}
