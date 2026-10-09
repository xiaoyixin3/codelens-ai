import { describe, expect, it } from 'vitest';
import { InMemoryReviewStore } from '@codelens/persistence';

const input = {
  repositoryId: 42, pullNumber: 7, baseSha: 'base1234', headSha: 'head1234',
  pipelineVersion: 'identity-test', configHash: 'enqueue-v1'
};

describe('immutable review enqueue identity', () => {
  it('deduplicates retries even after the resolved policy hash changes', async () => {
    const store = new InMemoryReviewStore();
    const original = await store.createOrGetReviewRun(input);
    await store.updateReviewRunConfig(original.run.id, 'resolved-policy-v2');
    const retry = await store.createOrGetReviewRun(input);
    expect(retry.created).toBe(false);
    expect(retry.run.id).toBe(original.run.id);
    expect(retry.run.configHash).toBe('resolved-policy-v2');
  });

  it.each([
    { baseSha: 'base5678' }, { headSha: 'head5678' }, { configHash: 'enqueue-v2' },
    { pipelineVersion: 'identity-test-v2' }, { requestKey: 'manual-request' },
    { repositoryId: 43 }, { pullNumber: 8 }
  ])('creates a distinct run when identity changes: %j', async (change) => {
    const store = new InMemoryReviewStore();
    const original = await store.createOrGetReviewRun(input);
    const next = await store.createOrGetReviewRun({ ...input, ...change });
    expect(next.created).toBe(true);
    expect(next.run.id).not.toBe(original.run.id);
    expect((await store.createOrGetReviewRun({ ...input, ...change })).created).toBe(false);
  });
});
