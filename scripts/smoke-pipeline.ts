import assert from 'node:assert/strict';
import { randomInt, randomUUID } from 'node:crypto';
import { Queue } from 'bullmq';
import postgres from 'postgres';
import { buildApi } from '../apps/api/src/app.js';
import { loadConfig } from '@codelens/config';
import type { PullRequestContext } from '@codelens/contracts';
import type { CheckInput, CompleteCheckInput, GitHubGateway } from '@codelens/github';
import { PostgresReviewStore } from '@codelens/persistence';
import {
  BullReviewQueue,
  REVIEW_QUEUE_NAME,
  createReviewWorker
} from '@codelens/queue';
import { DeterministicSummaryGenerator, ReviewOrchestrator } from '@codelens/review-core';
import { signWebhook } from '@codelens/security';

const config = loadConfig();
const repositoryId = 8_000_000_000_000_000 + randomInt(1_000_000);
const pullNumber = 1;
const installationId = 42;
const baseSha = 'a'.repeat(40);
const headSha = 'b'.repeat(40);
const deliveryId = `local-pipeline-${randomUUID()}`;

class SmokeGitHubGateway implements GitHubGateway {
  readonly completedChecks: CompleteCheckInput[] = [];
  readonly summaryComments: string[] = [];
  startedChecks = 0;

  readonly context: PullRequestContext = {
    owner: 'codelens-smoke',
    repo: 'pipeline-fixture',
    number: pullNumber,
    title: 'Exercise the review pipeline',
    body: 'A synthetic pull request used only by the local smoke test.',
    baseSha,
    headSha,
    files: [
      {
        path: 'src/payment.ts',
        status: 'modified',
        additions: 3,
        deletions: 1,
        patch: '@@ -1 +1,3 @@\n-export const retries = 0;\n+export const retries = 3;\n+export const backoff = true;'
      }
    ]
  };

  async getPullRequest(
    _installationId: number,
    _owner: string,
    _repo: string,
    _pullNumber: number
  ): Promise<PullRequestContext> {
    return this.context;
  }

  async getCurrentHeadSha(
    _installationId: number,
    _owner: string,
    _repo: string,
    _pullNumber: number
  ): Promise<string> {
    return headSha;
  }

  async getFileContent(
    _installationId: number,
    _owner: string,
    _repo: string,
    _path: string,
    _ref: string
  ): Promise<string> {
    return 'export const retries = 3;\nexport const backoff = true;';
  }

  async startCheck(_input: CheckInput, existingCheckRunId?: number): Promise<number> {
    this.startedChecks += 1;
    return existingCheckRunId ?? 99_001;
  }

  async completeCheck(input: CompleteCheckInput): Promise<void> {
    this.completedChecks.push(input);
  }

  async upsertSummaryComment(input: {
    installationId: number;
    owner: string;
    repo: string;
    pullNumber: number;
    body: string;
    existingCommentId?: number;
  }): Promise<number> {
    this.summaryComments.push(input.body);
    return input.existingCommentId ?? 99_002;
  }
}

async function waitForTerminalRun(store: PostgresReviewStore, reviewRunId: string) {
  const deadline = Date.now() + 15_000;
  while (Date.now() < deadline) {
    const run = await store.getReviewRun(reviewRunId);
    if (run && ['completed', 'failed', 'stale', 'skipped'].includes(run.status)) return run;
    await new Promise((resolve) => setTimeout(resolve, 50));
  }
  throw new Error(`Review run ${reviewRunId} did not finish within 15 seconds.`);
}

const store = new PostgresReviewStore(config.DATABASE_URL);
const queue = new BullReviewQueue(config.REDIS_URL);
const cleanupQueue = new Queue(REVIEW_QUEUE_NAME, { connection: { url: config.REDIS_URL } });
const cleanupSql = postgres(config.DATABASE_URL, { max: 1 });
const github = new SmokeGitHubGateway();
const summaries = new DeterministicSummaryGenerator({
  maxChangedFiles: config.MAX_CHANGED_FILES,
  maxPatchChars: config.MAX_PATCH_CHARS
});
const orchestrator = new ReviewOrchestrator(store, github, summaries);
const worker = createReviewWorker(config.REDIS_URL, (job) => orchestrator.execute(job));
const app = buildApi({
  store,
  queue,
  webhookSecret: config.GITHUB_WEBHOOK_SECRET
});

let reviewRunId: string | undefined;

try {
  await Promise.all([store.healthCheck(), queue.healthCheck(), worker.waitUntilReady()]);

  const body = JSON.stringify({
    action: 'opened',
    installation: { id: installationId },
    repository: {
      id: repositoryId,
      name: github.context.repo,
      owner: { login: github.context.owner }
    },
    pull_request: {
      number: pullNumber,
      base: { sha: baseSha },
      head: { sha: headSha }
    }
  });
  const headers = {
    'content-type': 'application/json',
    'x-github-event': 'pull_request',
    'x-github-delivery': deliveryId,
    'x-hub-signature-256': signWebhook(body, config.GITHUB_WEBHOOK_SECRET)
  };

  const accepted = await app.inject({
    method: 'POST',
    url: '/webhooks/github',
    headers,
    payload: body
  });
  assert.equal(accepted.statusCode, 202);
  assert.equal(accepted.json().status, 'queued');
  reviewRunId = accepted.json().reviewRunId as string;

  const duplicate = await app.inject({
    method: 'POST',
    url: '/webhooks/github',
    headers,
    payload: body
  });
  assert.equal(duplicate.statusCode, 202);
  assert.equal(duplicate.json().status, 'duplicate');

  const rejected = await app.inject({
    method: 'POST',
    url: '/webhooks/github',
    headers: {
      ...headers,
      'x-github-delivery': `${deliveryId}-invalid`,
      'x-hub-signature-256': 'sha256=deadbeef'
    },
    payload: body
  });
  assert.equal(rejected.statusCode, 401);
  assert.equal(rejected.json().error, 'invalid_webhook_signature');

  const run = await waitForTerminalRun(store, reviewRunId);
  const publication = await store.getPublication(reviewRunId);
  assert.equal(run.status, 'completed');
  assert.ok(run.summary);
  assert.equal(github.startedChecks, 1);
  assert.equal(github.completedChecks.length, 1);
  assert.equal(github.summaryComments.length, 1);
  assert.equal(publication?.checkRunId, 99_001);
  assert.equal(publication?.summaryCommentId, 99_002);

  console.log(JSON.stringify({
    status: 'passed',
    reviewRunId,
    webhook: { accepted: 202, duplicate: 202, invalidSignature: 401 },
    review: {
      status: run.status,
      riskLevel: run.summary.riskLevel,
      checkRunsStarted: github.startedChecks,
      checksCompleted: github.completedChecks.length,
      summaryComments: github.summaryComments.length
    }
  }));
} finally {
  await worker.close().catch(() => undefined);
  await app.close().catch(() => undefined);
  if (reviewRunId) {
    const job = await cleanupQueue.getJob(reviewRunId);
    await job?.remove().catch(() => undefined);
  }
  await cleanupSql.begin(async (sql) => {
    await sql`DELETE FROM review_runs WHERE github_repository_id = ${repositoryId}`;
    await sql`DELETE FROM webhook_deliveries WHERE delivery_id = ${deliveryId}`;
  });
  await Promise.all([
    cleanupQueue.close(),
    cleanupSql.end()
  ]);
}
