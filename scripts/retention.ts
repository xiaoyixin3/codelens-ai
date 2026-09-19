import { loadConfig } from '@codelens/config';
import { PostgresLifecycleStore } from '@codelens/lifecycle';

const config = loadConfig();
const store = new PostgresLifecycleStore(config.DATABASE_URL);
try {
  const result = await store.applyRetention({
    reviewDays: config.RETENTION_REVIEW_DAYS,
    snapshotDays: config.RETENTION_SNAPSHOT_DAYS,
    webhookDays: config.RETENTION_WEBHOOK_DAYS,
    llmTelemetryDays: config.RETENTION_LLM_TELEMETRY_DAYS
  });
  console.log(JSON.stringify({ status: 'completed', ...result }));
} finally {
  await store.close();
}
