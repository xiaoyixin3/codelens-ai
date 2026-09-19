import { loadConfig } from '@codelens/config';
import { PostgresCodeIndexStore, PullRequestCodeIndexer } from '@codelens/code-index';
import { OctokitGitHubGateway } from '@codelens/github';
import { CodeIntelligencePipeline, PostgresImpactStore } from '@codelens/impact-engine';
import { InMemoryLlmBudget, PostgresLlmTelemetryStore } from '@codelens/llm-runtime';
import { PostgresReviewStore } from '@codelens/persistence';
import { PostgresPolicyStore, RepositoryPolicyLoader } from '@codelens/project-policy';
import { createReviewWorker } from '@codelens/queue';
import {
  CompatibleLlmRiskReviewer,
  CompositeRiskReviewer,
  DeterministicRiskReviewer,
  EvidenceVerifier,
  FallbackRiskReviewer,
  PostgresFindingStore,
  RiskReviewPipeline,
  SafeRiskReviewer,
  type RiskReviewer
} from '@codelens/risk-review';
import {
  CompatibleLlmSummaryGenerator,
  DeterministicSummaryGenerator,
  FallbackSummaryGenerator,
  ReviewOrchestrator,
  type SummaryGenerator
} from '@codelens/review-core';

const config = loadConfig();
if (!config.GITHUB_APP_ID || !config.GITHUB_PRIVATE_KEY) {
  throw new Error('GITHUB_APP_ID and GITHUB_PRIVATE_KEY are required by the worker.');
}

const store = new PostgresReviewStore(config.DATABASE_URL);
const codeIndexStore = new PostgresCodeIndexStore(config.DATABASE_URL);
const impactStore = new PostgresImpactStore(config.DATABASE_URL);
const findingStore = new PostgresFindingStore(config.DATABASE_URL);
const policyStore = new PostgresPolicyStore(config.DATABASE_URL);
const llmTelemetry = new PostgresLlmTelemetryStore(config.DATABASE_URL);
const llmBudget = new InMemoryLlmBudget({
  maxCallsPerRun: config.LLM_MAX_CALLS_PER_RUN,
  maxInputCharsPerRun: config.LLM_MAX_INPUT_CHARS_PER_RUN
});
const github = new OctokitGitHubGateway({
  appId: config.GITHUB_APP_ID,
  privateKey: config.GITHUB_PRIVATE_KEY
});
const fallback = new DeterministicSummaryGenerator({
  maxChangedFiles: config.MAX_CHANGED_FILES,
  maxPatchChars: config.MAX_PATCH_CHARS
});

let summaries: SummaryGenerator = fallback;
if (config.LLM_BASE_URL && config.LLM_API_KEY && config.LLM_MODEL) {
  let providerSummary: SummaryGenerator = new CompatibleLlmSummaryGenerator({
    baseUrl: config.LLM_BASE_URL,
    apiKey: config.LLM_API_KEY,
    model: config.LLM_MODEL,
    provider: 'primary',
    telemetry: llmTelemetry,
    budget: llmBudget,
    maxChangedFiles: config.MAX_CHANGED_FILES,
    maxPatchChars: config.MAX_PATCH_CHARS
  });
  if (config.LLM_FALLBACK_BASE_URL && config.LLM_FALLBACK_API_KEY && config.LLM_FALLBACK_MODEL) {
    providerSummary = new FallbackSummaryGenerator(providerSummary, new CompatibleLlmSummaryGenerator({
      baseUrl: config.LLM_FALLBACK_BASE_URL,
      apiKey: config.LLM_FALLBACK_API_KEY,
      model: config.LLM_FALLBACK_MODEL,
      provider: 'fallback',
      telemetry: llmTelemetry,
      budget: llmBudget,
      maxChangedFiles: config.MAX_CHANGED_FILES,
      maxPatchChars: config.MAX_PATCH_CHARS
    }));
  }
  summaries = new FallbackSummaryGenerator(
    providerSummary,
    fallback
  );
}

const codeIndexer = new PullRequestCodeIndexer(github, codeIndexStore, undefined, {
  maxFiles: config.MAX_CHANGED_FILES,
  maxFileBytes: config.MAX_INDEX_FILE_BYTES
});
const codeIntelligence = new CodeIntelligencePipeline(codeIndexer, codeIndexStore, impactStore);
const riskReviewers: RiskReviewer[] = [new DeterministicRiskReviewer()];
if (config.LLM_BASE_URL && config.LLM_API_KEY && config.LLM_MODEL) {
  let providerRisk: RiskReviewer = new CompatibleLlmRiskReviewer({
    baseUrl: config.LLM_BASE_URL,
    apiKey: config.LLM_API_KEY,
    model: config.LLM_MODEL,
    provider: 'primary',
    telemetry: llmTelemetry,
    budget: llmBudget,
    maxPatchChars: config.MAX_PATCH_CHARS
  });
  if (config.LLM_FALLBACK_BASE_URL && config.LLM_FALLBACK_API_KEY && config.LLM_FALLBACK_MODEL) {
    providerRisk = new FallbackRiskReviewer(providerRisk, new CompatibleLlmRiskReviewer({
      baseUrl: config.LLM_FALLBACK_BASE_URL,
      apiKey: config.LLM_FALLBACK_API_KEY,
      model: config.LLM_FALLBACK_MODEL,
      provider: 'fallback',
      telemetry: llmTelemetry,
      budget: llmBudget,
      maxPatchChars: config.MAX_PATCH_CHARS
    }));
  }
  riskReviewers.push(new SafeRiskReviewer(providerRisk));
}
const riskReview = new RiskReviewPipeline(
  new CompositeRiskReviewer(riskReviewers),
  new EvidenceVerifier({ maxPublished: config.MAX_INLINE_COMMENTS }),
  findingStore
);
const policyLoader = new RepositoryPolicyLoader(github, policyStore, config.MAX_INLINE_COMMENTS);
const orchestrator = new ReviewOrchestrator(
  store,
  github,
  summaries,
  codeIntelligence,
  riskReview,
  policyLoader
);
const worker = createReviewWorker(config.REDIS_URL, (job) => orchestrator.execute(job));

worker.on('completed', (job) => console.log(`Review run completed: ${job.id}`));
worker.on('failed', (job, error) => console.error(`Review run failed: ${job?.id ?? 'unknown'}`, error));

async function shutdown(): Promise<void> {
  await worker.close();
  await Promise.all([
    store.close(),
    codeIndexStore.close(),
    impactStore.close(),
    findingStore.close(),
    policyStore.close(),
    llmTelemetry.close()
  ]);
}

process.once('SIGINT', () => void shutdown());
process.once('SIGTERM', () => void shutdown());
