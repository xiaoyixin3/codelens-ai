import { createHash, randomUUID } from 'node:crypto';
import postgres, { type Sql } from 'postgres';

export interface RetentionPolicy {
  reviewDays: number;
  snapshotDays: number;
  webhookDays: number;
  llmTelemetryDays: number;
}

export interface RetentionResult {
  reviewRuns: number;
  snapshots: number;
  webhookDeliveries: number;
  llmCalls: number;
}

export interface RepositoryDeletionResult {
  receiptId: string;
  reviewRuns: number;
  snapshots: number;
  projectRules: number;
  llmCalls: number;
}

export interface LifecycleStore {
  applyRetention(policy: RetentionPolicy, now?: Date): Promise<RetentionResult>;
  deleteRepository(repositoryId: number, requestedBy: string): Promise<RepositoryDeletionResult>;
  close(): Promise<void>;
}

export function retentionCutoffs(policy: RetentionPolicy, now = new Date()): {
  reviews: Date;
  snapshots: Date;
  webhooks: Date;
  llmTelemetry: Date;
} {
  for (const value of Object.values(policy)) {
    if (!Number.isInteger(value) || value <= 0) throw new Error('Retention days must be positive integers.');
  }
  const cutoff = (days: number) => new Date(now.getTime() - days * 86_400_000);
  return {
    reviews: cutoff(policy.reviewDays),
    snapshots: cutoff(policy.snapshotDays),
    webhooks: cutoff(policy.webhookDays),
    llmTelemetry: cutoff(policy.llmTelemetryDays)
  };
}

export class PostgresLifecycleStore implements LifecycleStore {
  readonly #sql: Sql;
  constructor(databaseUrl: string) { this.#sql = postgres(databaseUrl, { max: 2 }); }

  async applyRetention(policy: RetentionPolicy, now = new Date()): Promise<RetentionResult> {
    const cutoffs = retentionCutoffs(policy, now);
    return this.#sql.begin(async (sql) => {
      const runs = await sql`
        DELETE FROM review_runs
        WHERE status IN ('completed', 'failed', 'stale', 'skipped')
          AND completed_at < ${cutoffs.reviews}
        RETURNING id
      `;
      const snapshots = await sql`
        DELETE FROM code_snapshots
        WHERE created_at < ${cutoffs.snapshots}
          AND NOT EXISTS (
            SELECT 1 FROM impact_analyses
            WHERE base_snapshot_id = code_snapshots.id OR head_snapshot_id = code_snapshots.id
          )
        RETURNING id
      `;
      const deliveries = await sql`
        DELETE FROM webhook_deliveries
        WHERE received_at < ${cutoffs.webhooks}
        RETURNING delivery_id
      `;
      const calls = await sql`
        DELETE FROM llm_calls
        WHERE created_at < ${cutoffs.llmTelemetry}
        RETURNING id
      `;
      return {
        reviewRuns: runs.length,
        snapshots: snapshots.length,
        webhookDeliveries: deliveries.length,
        llmCalls: calls.length
      };
    });
  }

  async deleteRepository(repositoryId: number, requestedBy: string): Promise<RepositoryDeletionResult> {
    return this.#sql.begin(async (sql) => {
      const calls = await sql`
        DELETE FROM llm_calls
        WHERE review_run_id IN (
          SELECT id FROM review_runs WHERE github_repository_id = ${repositoryId}
        )
        RETURNING id
      `;
      const runs = await sql`
        DELETE FROM review_runs WHERE github_repository_id = ${repositoryId} RETURNING id
      `;
      const snapshots = await sql`
        DELETE FROM code_snapshots WHERE github_repository_id = ${repositoryId} RETURNING id
      `;
      const rules = await sql`
        DELETE FROM project_rules WHERE github_repository_id = ${repositoryId} RETURNING id
      `;
      const receiptId = randomUUID();
      const repositoryHash = createHash('sha256').update(`${repositoryId}:${receiptId}`).digest('hex');
      await sql`
        INSERT INTO data_deletion_audit (
          id, repository_hash, requested_by, review_runs_deleted,
          snapshots_deleted, project_rules_deleted, llm_calls_deleted
        ) VALUES (
          ${receiptId}, ${repositoryHash}, ${requestedBy.slice(0, 200)}, ${runs.length},
          ${snapshots.length}, ${rules.length}, ${calls.length}
        )
      `;
      return {
        receiptId,
        reviewRuns: runs.length,
        snapshots: snapshots.length,
        projectRules: rules.length,
        llmCalls: calls.length
      };
    });
  }

  async close(): Promise<void> { await this.#sql.end(); }
}
