import Fastify, { type FastifyInstance } from 'fastify';
import {
  DEFAULT_CONFIG_HASH,
  REVIEW_PIPELINE_VERSION,
  type CheckRunWebhook,
  type IssueCommentWebhook,
  type PullRequestWebhook
} from '@codelens/contracts';
import type { ReviewStore } from '@codelens/persistence';
import type { ReviewQueue } from '@codelens/queue';
import { redactSecrets, verifyWebhookSignature } from '@codelens/security';

declare module 'fastify' {
  interface FastifyRequest {
    rawBody?: Buffer;
  }
}

const REVIEW_ACTIONS = new Set(['opened', 'reopened', 'synchronize', 'ready_for_review']);

export interface ApiDependencies {
  store: ReviewStore;
  queue: ReviewQueue;
  webhookSecret: string;
  githubAppId?: string;
  logger?: boolean | { level: string };
}

function isPullRequestWebhook(value: unknown): value is PullRequestWebhook {
  if (!value || typeof value !== 'object') return false;
  const body = value as Partial<PullRequestWebhook>;
  return Boolean(
    body.action &&
      body.installation?.id &&
      body.repository?.id &&
      body.repository?.name &&
      body.repository?.owner?.login &&
      body.pull_request?.number &&
      body.pull_request?.base?.sha &&
      body.pull_request?.head?.sha
  );
}

function isCheckRunRerequest(value: unknown): value is CheckRunWebhook {
  if (!value || typeof value !== 'object') return false;
  const body = value as Partial<CheckRunWebhook>;
  const pull = body.check_run?.pull_requests?.[0];
  return Boolean(
    body.action === 'rerequested' &&
      body.installation?.id &&
      body.repository?.id &&
      body.repository?.name &&
      body.repository?.owner?.login &&
      body.check_run?.id &&
      body.check_run?.name === 'CodeLens AI Review' &&
      body.check_run?.head_sha &&
      pull?.number &&
      pull.base?.sha &&
      pull.head?.sha
  );
}

function isIssueCommentWebhook(value: unknown): value is IssueCommentWebhook {
  if (!value || typeof value !== 'object') return false;
  const body = value as Partial<IssueCommentWebhook>;
  return Boolean(
    body.action === 'created' &&
      body.repository?.id &&
      body.issue?.number &&
      body.issue?.pull_request &&
      body.comment?.id &&
      body.comment?.body &&
      body.comment?.user?.login
  );
}

function parseFeedbackCommand(body: string): {
  fingerprintPrefix: string;
  verdict: 'helpful' | 'false_positive';
} | undefined {
  const match = body.trim().match(
    /^\/codelens\s+feedback\s+([a-f0-9]{12,64})\s+(helpful|false-positive)$/i
  );
  if (!match?.[1] || !match[2]) return undefined;
  return {
    fingerprintPrefix: match[1].toLowerCase(),
    verdict: match[2].toLowerCase() === 'helpful' ? 'helpful' : 'false_positive'
  };
}

export function buildApi(dependencies: ApiDependencies): FastifyInstance {
  const app = Fastify({ logger: dependencies.logger ?? false });

  app.addContentTypeParser(
    'application/json',
    { parseAs: 'buffer' },
    (request, body, done) => {
      const rawBody = Buffer.isBuffer(body) ? body : Buffer.from(body);
      request.rawBody = rawBody;
      try {
        done(null, JSON.parse(rawBody.toString('utf8')));
      } catch (error) {
        done(error as Error, undefined);
      }
    }
  );

  app.get('/healthz', async () => ({ status: 'ok' }));

  app.get('/readyz', async (_request, reply) => {
    try {
      await Promise.all([dependencies.store.healthCheck(), dependencies.queue.healthCheck()]);
      return { status: 'ready' };
    } catch {
      return reply.code(503).send({ status: 'not_ready' });
    }
  });

  app.post('/webhooks/github', async (request, reply) => {
    const rawBody = request.rawBody ?? Buffer.alloc(0);
    const signature = request.headers['x-hub-signature-256'];
    const deliveryId = request.headers['x-github-delivery'];
    const event = request.headers['x-github-event'];

    if (
      typeof signature !== 'string' ||
      typeof deliveryId !== 'string' ||
      typeof event !== 'string' ||
      !verifyWebhookSignature(rawBody, signature, dependencies.webhookSecret)
    ) {
      return reply.code(401).send({ error: 'invalid_webhook_signature' });
    }

    const body = request.body as { action?: string };
    const claimed = await dependencies.store.claimDelivery({
      deliveryId,
      event,
      ...(body.action ? { action: body.action } : {}),
      rawBody,
      signatureValid: true
    });
    if (!claimed) return reply.code(202).send({ status: 'duplicate' });

    try {
      if (event === 'check_run' && isCheckRunRerequest(body)) {
        if (
          dependencies.githubAppId &&
          String(body.check_run.app?.id ?? '') !== dependencies.githubAppId
        ) {
          await dependencies.store.markDeliveryProcessed(deliveryId);
          return reply.code(202).send({ status: 'ignored_foreign_check' });
        }
        const pull = body.check_run.pull_requests[0]!;
        if (pull.head.sha !== body.check_run.head_sha) {
          await dependencies.store.markDeliveryProcessed(deliveryId);
          return reply.code(202).send({ status: 'ignored_stale_check' });
        }
        const { run, created } = await dependencies.store.createOrGetReviewRun({
          repositoryId: body.repository.id,
          pullNumber: pull.number,
          baseSha: pull.base.sha,
          headSha: pull.head.sha,
          pipelineVersion: REVIEW_PIPELINE_VERSION,
          configHash: DEFAULT_CONFIG_HASH,
          trigger: 'rerun',
          requestKey: `rerun:${deliveryId}`
        });
        if (created) {
          await dependencies.store.savePublication({
            reviewRunId: run.id,
            headSha: pull.head.sha,
            checkRunId: body.check_run.id
          });
          await dependencies.queue.enqueue({
            reviewRunId: run.id,
            installationId: body.installation.id,
            owner: body.repository.owner.login,
            repo: body.repository.name,
            pullNumber: pull.number,
            baseSha: pull.base.sha,
            headSha: pull.head.sha
          });
        }
        await dependencies.store.markDeliveryProcessed(deliveryId);
        return reply.code(202).send({ status: created ? 'rerun_queued' : 'rerun_already_queued', reviewRunId: run.id });
      }

      if (event === 'issue_comment' && isIssueCommentWebhook(body)) {
        const command = parseFeedbackCommand(body.comment.body);
        if (!command) {
          await dependencies.store.markDeliveryProcessed(deliveryId);
          return reply.code(202).send({ status: 'ignored_comment' });
        }
        const recorded = await dependencies.store.saveFindingFeedback({
          repositoryId: body.repository.id,
          pullNumber: body.issue.number,
          fingerprintPrefix: command.fingerprintPrefix,
          verdict: command.verdict,
          actorLogin: body.comment.user.login,
          sourceCommentId: body.comment.id
        });
        await dependencies.store.markDeliveryProcessed(deliveryId);
        return reply.code(202).send({ status: recorded ? 'feedback_recorded' : 'feedback_not_found' });
      }

      if (event !== 'pull_request' || !isPullRequestWebhook(body)) {
        await dependencies.store.markDeliveryProcessed(deliveryId);
        return reply.code(202).send({ status: 'ignored' });
      }
      if (!REVIEW_ACTIONS.has(body.action)) {
        await dependencies.store.markDeliveryProcessed(deliveryId);
        return reply.code(202).send({ status: 'ignored_action' });
      }

      const { run, created } = await dependencies.store.createOrGetReviewRun({
        repositoryId: body.repository.id,
        pullNumber: body.pull_request.number,
        baseSha: body.pull_request.base.sha,
        headSha: body.pull_request.head.sha,
        pipelineVersion: REVIEW_PIPELINE_VERSION,
        configHash: DEFAULT_CONFIG_HASH
      });

      if (created) {
        await dependencies.queue.enqueue({
          reviewRunId: run.id,
          installationId: body.installation.id,
          owner: body.repository.owner.login,
          repo: body.repository.name,
          pullNumber: body.pull_request.number,
          baseSha: body.pull_request.base.sha,
          headSha: body.pull_request.head.sha
        });
      }

      await dependencies.store.markDeliveryProcessed(deliveryId);
      return reply.code(202).send({ status: created ? 'queued' : 'already_queued', reviewRunId: run.id });
    } catch (error) {
      const detail = redactSecrets(error instanceof Error ? error.message : String(error));
      await dependencies.store.markDeliveryProcessed(deliveryId, detail.slice(0, 2_000));
      throw error;
    }
  });

  app.addHook('onClose', async () => {
    await Promise.all([dependencies.queue.close(), dependencies.store.close()]);
  });

  return app;
}
