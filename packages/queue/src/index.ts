import { createHash } from 'node:crypto';
import { Queue, Worker, type Job } from 'bullmq';
import { ReviewJobSchema, type ReviewJob } from '@codelens/contracts';

export const REVIEW_QUEUE_NAME = 'review-runs';

export interface ReviewQueue {
  healthCheck(): Promise<void>;
  enqueue(job: ReviewJob): Promise<void>;
  close(): Promise<void>;
}

export class BullReviewQueue implements ReviewQueue {
  readonly #queue: Queue<ReviewJob>;

  constructor(redisUrl: string) {
    this.#queue = new Queue<ReviewJob>(REVIEW_QUEUE_NAME, {
      connection: { url: redisUrl }
    });
  }

  async healthCheck(): Promise<void> {
    await this.#queue.waitUntilReady();
  }

  async enqueue(job: ReviewJob): Promise<void> {
    const parsed = ReviewJobSchema.parse(job) as ReviewJob;
    const deduplicationId = createHash('sha256')
      .update(`${parsed.owner}/${parsed.repo}#${parsed.pullNumber}@${parsed.headSha}`)
      .digest('hex');

    await this.#queue.add('review', parsed, {
      jobId: parsed.reviewRunId,
      attempts: 3,
      backoff: { type: 'exponential', delay: 2_000 },
      deduplication: { id: deduplicationId, keepLastIfActive: true },
      removeOnComplete: { age: 86_400, count: 1_000 },
      removeOnFail: { age: 604_800, count: 5_000 }
    });
  }

  async close(): Promise<void> {
    await this.#queue.close();
  }
}

export class InMemoryReviewQueue implements ReviewQueue {
  readonly jobs: ReviewJob[] = [];

  async healthCheck(): Promise<void> {}

  async enqueue(job: ReviewJob): Promise<void> {
    this.jobs.push(ReviewJobSchema.parse(job) as ReviewJob);
  }

  async close(): Promise<void> {}
}

export function createReviewWorker(
  redisUrl: string,
  processor: (job: ReviewJob) => Promise<void>
): Worker<ReviewJob> {
  return new Worker<ReviewJob>(
    REVIEW_QUEUE_NAME,
    async (job: Job<ReviewJob>) => processor(ReviewJobSchema.parse(job.data) as ReviewJob),
    {
      connection: { url: redisUrl },
      concurrency: 2,
      limiter: { max: 10, duration: 1_000 }
    }
  );
}
