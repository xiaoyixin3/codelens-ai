import { afterEach, describe, expect, it } from 'vitest';
import { buildApi } from '../apps/api/src/app.js';
import { InMemoryReviewStore } from '@codelens/persistence';
import { InMemoryReviewQueue } from '@codelens/queue';
import { signWebhook } from '@codelens/security';

const secret = 'test-webhook-secret-with-enough-entropy';
const openApps: Array<ReturnType<typeof buildApi>> = [];

afterEach(async () => {
  await Promise.all(openApps.splice(0).map((app) => app.close()));
});

function createPayload(headSha = 'bbbbbbb2222222') {
  return {
    action: 'opened',
    installation: { id: 42 },
    repository: { id: 99, name: 'checkout', owner: { login: 'acme' } },
    pull_request: {
      number: 7,
      base: { sha: 'aaaaaaa1111111' },
      head: { sha: headSha }
    }
  };
}

describe('GitHub webhook API', () => {
  it('exposes liveness and dependency readiness separately', async () => {
    const app = buildApi({
      store: new InMemoryReviewStore(),
      queue: new InMemoryReviewQueue(),
      webhookSecret: secret
    });
    openApps.push(app);

    expect((await app.inject({ method: 'GET', url: '/healthz' })).json()).toEqual({ status: 'ok' });
    expect((await app.inject({ method: 'GET', url: '/readyz' })).json()).toEqual({ status: 'ready' });
  });

  it('rejects an invalid signature before queueing work', async () => {
    const store = new InMemoryReviewStore();
    const queue = new InMemoryReviewQueue();
    const app = buildApi({ store, queue, webhookSecret: secret });
    openApps.push(app);
    const body = JSON.stringify(createPayload());

    const response = await app.inject({
      method: 'POST',
      url: '/webhooks/github',
      headers: {
        'content-type': 'application/json',
        'x-github-event': 'pull_request',
        'x-github-delivery': 'delivery-invalid',
        'x-hub-signature-256': 'sha256=deadbeef'
      },
      payload: body
    });

    expect(response.statusCode).toBe(401);
    expect(queue.jobs).toHaveLength(0);
    expect(store.runs.size).toBe(0);
  });

  it('queues a review exactly once for a valid delivery', async () => {
    const store = new InMemoryReviewStore();
    const queue = new InMemoryReviewQueue();
    const app = buildApi({ store, queue, webhookSecret: secret });
    openApps.push(app);
    const body = JSON.stringify(createPayload());
    const headers = {
      'content-type': 'application/json',
      'x-github-event': 'pull_request',
      'x-github-delivery': 'delivery-1',
      'x-hub-signature-256': signWebhook(body, secret)
    };

    const first = await app.inject({ method: 'POST', url: '/webhooks/github', headers, payload: body });
    const duplicate = await app.inject({ method: 'POST', url: '/webhooks/github', headers, payload: body });

    expect(first.statusCode).toBe(202);
    expect(first.json().status).toBe('queued');
    expect(duplicate.statusCode).toBe(202);
    expect(duplicate.json().status).toBe('duplicate');
    expect(queue.jobs).toHaveLength(1);
    expect(store.runs.size).toBe(1);
  });

  it('deduplicates different deliveries for the same PR head SHA', async () => {
    const store = new InMemoryReviewStore();
    const queue = new InMemoryReviewQueue();
    const app = buildApi({ store, queue, webhookSecret: secret });
    openApps.push(app);
    const body = JSON.stringify(createPayload());

    for (const delivery of ['delivery-a', 'delivery-b']) {
      const response = await app.inject({
        method: 'POST',
        url: '/webhooks/github',
        headers: {
          'content-type': 'application/json',
          'x-github-event': 'pull_request',
          'x-github-delivery': delivery,
          'x-hub-signature-256': signWebhook(body, secret)
        },
        payload: body
      });
      expect(response.statusCode).toBe(202);
    }

    expect(queue.jobs).toHaveLength(1);
    expect(store.runs.size).toBe(1);
  });

  it('queues a manual rerun and reuses the requested Check Run', async () => {
    const store = new InMemoryReviewStore();
    const queue = new InMemoryReviewQueue();
    const app = buildApi({ store, queue, webhookSecret: secret });
    openApps.push(app);
    const payload = {
      action: 'rerequested',
      installation: { id: 42 },
      repository: { id: 99, name: 'checkout', owner: { login: 'acme' } },
      check_run: {
        id: 701,
        name: 'CodeLens AI Review',
        head_sha: 'bbbbbbb2222222',
        pull_requests: [{
          number: 7,
          base: { sha: 'aaaaaaa1111111' },
          head: { sha: 'bbbbbbb2222222' }
        }]
      }
    };
    const body = JSON.stringify(payload);
    const response = await app.inject({
      method: 'POST',
      url: '/webhooks/github',
      headers: {
        'content-type': 'application/json',
        'x-github-event': 'check_run',
        'x-github-delivery': 'delivery-rerun-1',
        'x-hub-signature-256': signWebhook(body, secret)
      },
      payload: body
    });

    expect(response.statusCode).toBe(202);
    expect(response.json().status).toBe('rerun_queued');
    expect(queue.jobs).toHaveLength(1);
    const run = [...store.runs.values()][0]!;
    expect(run).toMatchObject({ trigger: 'rerun', requestKey: 'rerun:delivery-rerun-1' });
    expect(store.publications.get(run.id)?.checkRunId).toBe(701);
  });

  it('ignores a rerequest for a superseded Check SHA', async () => {
    const store = new InMemoryReviewStore();
    const queue = new InMemoryReviewQueue();
    const app = buildApi({ store, queue, webhookSecret: secret });
    openApps.push(app);
    const payload = {
      action: 'rerequested',
      installation: { id: 42 },
      repository: { id: 99, name: 'checkout', owner: { login: 'acme' } },
      check_run: {
        id: 701,
        name: 'CodeLens AI Review',
        head_sha: 'oldoldold111111',
        pull_requests: [{
          number: 7,
          base: { sha: 'aaaaaaa1111111' },
          head: { sha: 'bbbbbbb2222222' }
        }]
      }
    };
    const body = JSON.stringify(payload);
    const response = await app.inject({
      method: 'POST',
      url: '/webhooks/github',
      headers: {
        'content-type': 'application/json',
        'x-github-event': 'check_run',
        'x-github-delivery': 'delivery-rerun-stale',
        'x-hub-signature-256': signWebhook(body, secret)
      },
      payload: body
    });

    expect(response.json().status).toBe('ignored_stale_check');
    expect(queue.jobs).toHaveLength(0);
  });

  it('records signed finding feedback from a PR comment exactly once', async () => {
    const store = new InMemoryReviewStore();
    const queue = new InMemoryReviewQueue();
    const app = buildApi({ store, queue, webhookSecret: secret });
    openApps.push(app);
    const payload = {
      action: 'created',
      installation: { id: 42 },
      repository: { id: 99 },
      issue: { number: 7, pull_request: { url: 'https://api.github.test/pulls/7' } },
      comment: {
        id: 9001,
        body: '/codelens feedback aabbccddeeff false-positive',
        user: { login: 'reviewer' }
      }
    };
    const body = JSON.stringify(payload);
    const headers = {
      'content-type': 'application/json',
      'x-github-event': 'issue_comment',
      'x-github-delivery': 'delivery-feedback-1',
      'x-hub-signature-256': signWebhook(body, secret)
    };
    const response = await app.inject({ method: 'POST', url: '/webhooks/github', headers, payload: body });
    const duplicate = await app.inject({ method: 'POST', url: '/webhooks/github', headers, payload: body });

    expect(response.json().status).toBe('feedback_recorded');
    expect(duplicate.json().status).toBe('duplicate');
    expect(store.feedback).toEqual([
      expect.objectContaining({
        fingerprintPrefix: 'aabbccddeeff', verdict: 'false_positive', actorLogin: 'reviewer'
      })
    ]);
  });
});
