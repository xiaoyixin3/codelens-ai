import { createHash, randomUUID } from 'node:crypto';
import postgres, { type Sql } from 'postgres';
import { resilientFetch } from '@codelens/resilience';
import { redactSecrets } from '@codelens/security';

export interface LlmCallRecord {
  id: string;
  reviewRunId?: string;
  provider: string;
  model: string;
  task: 'summary' | 'risk_review';
  promptHash: string;
  status: 'succeeded' | 'failed';
  inputChars: number;
  outputChars: number;
  inputTokens?: number;
  outputTokens?: number;
  durationMs: number;
  httpStatus?: number;
  errorCode?: string;
  errorDetail?: string;
  createdAt: Date;
}

export interface LlmTelemetryStore {
  record(call: LlmCallRecord): Promise<void>;
  close(): Promise<void>;
}

export interface LlmBudget {
  consume(reviewRunId: string, inputChars: number): boolean;
}

export class InMemoryLlmBudget implements LlmBudget {
  readonly #usage = new Map<string, { calls: number; inputChars: number }>();
  constructor(
    private readonly limits: { maxCallsPerRun: number; maxInputCharsPerRun: number },
    private readonly maxTrackedRuns = 10_000
  ) {}

  consume(reviewRunId: string, inputChars: number): boolean {
    const current = this.#usage.get(reviewRunId) ?? { calls: 0, inputChars: 0 };
    if (
      current.calls + 1 > this.limits.maxCallsPerRun ||
      current.inputChars + inputChars > this.limits.maxInputCharsPerRun
    ) return false;
    this.#usage.delete(reviewRunId);
    this.#usage.set(reviewRunId, { calls: current.calls + 1, inputChars: current.inputChars + inputChars });
    while (this.#usage.size > this.maxTrackedRuns) {
      const oldest = this.#usage.keys().next().value as string | undefined;
      if (!oldest) break;
      this.#usage.delete(oldest);
    }
    return true;
  }
}

export class InMemoryLlmTelemetryStore implements LlmTelemetryStore {
  readonly calls: LlmCallRecord[] = [];
  async record(call: LlmCallRecord): Promise<void> { this.calls.push(call); }
  async close(): Promise<void> {}
}

export class PostgresLlmTelemetryStore implements LlmTelemetryStore {
  readonly #sql: Sql;
  constructor(databaseUrl: string) { this.#sql = postgres(databaseUrl, { max: 5 }); }
  async record(call: LlmCallRecord): Promise<void> {
    await this.#sql`
      INSERT INTO llm_calls (
        id, review_run_id, provider, model, task, prompt_hash, status,
        input_chars, output_chars, input_tokens, output_tokens, duration_ms,
        http_status, error_code, error_detail, created_at
      ) VALUES (
        ${call.id}, ${call.reviewRunId ?? null}, ${call.provider}, ${call.model}, ${call.task},
        ${call.promptHash}, ${call.status}, ${call.inputChars}, ${call.outputChars},
        ${call.inputTokens ?? null}, ${call.outputTokens ?? null}, ${call.durationMs},
        ${call.httpStatus ?? null}, ${call.errorCode ?? null}, ${call.errorDetail ?? null}, ${call.createdAt}
      )
    `;
  }
  async close(): Promise<void> { await this.#sql.end(); }
}

export interface CompatibleChatOptions {
  baseUrl: string;
  apiKey: string;
  model: string;
  provider?: string;
  task: LlmCallRecord['task'];
  reviewRunId?: string;
  body: Record<string, unknown>;
  telemetry?: LlmTelemetryStore;
  budget?: LlmBudget;
  timeoutMs?: number;
}

export interface CompatibleChatResponse {
  choices?: Array<{ message?: { content?: string } }>;
  usage?: { prompt_tokens?: number; completion_tokens?: number };
}

function redactValue(value: unknown): unknown {
  if (typeof value === 'string') return redactSecrets(value);
  if (Array.isArray(value)) return value.map(redactValue);
  if (value && typeof value === 'object') {
    return Object.fromEntries(
      Object.entries(value as Record<string, unknown>).map(([key, item]) => [key, redactValue(item)])
    );
  }
  return value;
}

async function recordSafely(store: LlmTelemetryStore | undefined, call: LlmCallRecord): Promise<void> {
  if (!store) return;
  try { await store.record(call); } catch { /* Telemetry must not fail a review. */ }
}

export async function callCompatibleChat(options: CompatibleChatOptions): Promise<CompatibleChatResponse> {
  const redactedJson = JSON.stringify(redactValue({ ...options.body, model: options.model }));
  const body = JSON.parse(redactedJson) as Record<string, unknown>;
  const promptHash = createHash('sha256').update(redactedJson).digest('hex');
  const started = Date.now();
  const base = {
    id: randomUUID(),
    ...(options.reviewRunId ? { reviewRunId: options.reviewRunId } : {}),
    provider: options.provider ?? new URL(options.baseUrl).hostname,
    model: options.model,
    task: options.task,
    promptHash,
    inputChars: redactedJson.length,
    createdAt: new Date()
  };

  try {
    if (options.budget && !options.budget.consume(options.reviewRunId ?? 'unscoped', redactedJson.length)) {
      throw Object.assign(new Error('LLM budget exceeded for this review run.'), {
        code: 'LLM_BUDGET_EXCEEDED'
      });
    }
    const response = await resilientFetch(
      `${options.baseUrl.replace(/\/$/, '')}/chat/completions`,
      {
        method: 'POST',
        headers: { authorization: `Bearer ${options.apiKey}`, 'content-type': 'application/json' },
        body: JSON.stringify(body)
      },
      { attempts: 3, baseDelayMs: 500, maxDelayMs: 10_000, timeoutMs: options.timeoutMs ?? 45_000 }
    );
    if (!response.ok) {
      const detail = redactSecrets(await response.text());
      throw Object.assign(new Error(`LLM request failed with ${response.status}: ${detail.slice(0, 500)}`), {
        status: response.status
      });
    }
    const payload = (await response.json()) as CompatibleChatResponse;
    const output = payload.choices?.[0]?.message?.content ?? '';
    await recordSafely(options.telemetry, {
      ...base,
      status: 'succeeded',
      outputChars: output.length,
      ...(payload.usage?.prompt_tokens !== undefined ? { inputTokens: payload.usage.prompt_tokens } : {}),
      ...(payload.usage?.completion_tokens !== undefined ? { outputTokens: payload.usage.completion_tokens } : {}),
      durationMs: Date.now() - started,
      httpStatus: response.status
    });
    return payload;
  } catch (error) {
    const candidate = error as { status?: number; code?: string; message?: string };
    await recordSafely(options.telemetry, {
      ...base,
      status: 'failed',
      outputChars: 0,
      durationMs: Date.now() - started,
      ...(candidate.status ? { httpStatus: candidate.status } : {}),
      errorCode: candidate.code ?? (candidate.status ? `HTTP_${candidate.status}` : 'LLM_REQUEST_FAILED'),
      errorDetail: redactSecrets(candidate.message ?? String(error)).slice(0, 1_000)
    });
    throw error;
  }
}
