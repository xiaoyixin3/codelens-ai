import { loadConfig } from '@codelens/config';
import { PostgresReviewStore } from '@codelens/persistence';
import { BullReviewQueue } from '@codelens/queue';
import { buildApi } from './app.js';

const config = loadConfig();
const app = buildApi({
  store: new PostgresReviewStore(config.DATABASE_URL),
  queue: new BullReviewQueue(config.REDIS_URL),
  webhookSecret: config.GITHUB_WEBHOOK_SECRET,
  ...(config.GITHUB_APP_ID ? { githubAppId: config.GITHUB_APP_ID } : {}),
  logger: { level: config.LOG_LEVEL }
});

try {
  await app.listen({ port: config.PORT, host: '0.0.0.0' });
} catch (error) {
  app.log.error(error);
  process.exitCode = 1;
}
