export interface RetryOptions {
  attempts?: number;
  baseDelayMs?: number;
  maxDelayMs?: number;
  sleep?: (milliseconds: number) => Promise<void>;
  random?: () => number;
  shouldRetry?: (error: unknown) => boolean;
  retryAfterMs?: (error: unknown) => number | undefined;
}

function defaultSleep(milliseconds: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}

export function httpRetryAfterMs(error: unknown): number | undefined {
  const candidate = error as {
    status?: number;
    response?: { headers?: Record<string, string | undefined> };
    headers?: Record<string, string | undefined>;
  };
  const headers = candidate.response?.headers ?? candidate.headers ?? {};
  const retryAfter = Number(headers['retry-after']);
  if (Number.isFinite(retryAfter) && retryAfter >= 0) return retryAfter * 1_000;
  const reset = Number(headers['x-ratelimit-reset']);
  if (Number.isFinite(reset) && reset > 0) return Math.max(0, reset * 1_000 - Date.now());
  return undefined;
}

export function isTransientHttpError(error: unknown): boolean {
  const candidate = error as {
    status?: number;
    code?: string;
    name?: string;
    cause?: { code?: string };
    response?: { headers?: Record<string, string | undefined> };
  };
  if (candidate.status === 429 || (candidate.status !== undefined && candidate.status >= 500)) return true;
  if (
    candidate.status === 403 &&
    (candidate.response?.headers?.['x-ratelimit-remaining'] === '0' ||
      candidate.response?.headers?.['retry-after'] !== undefined)
  ) return true;
  if (candidate.name === 'AbortError' || candidate.name === 'TimeoutError') return true;
  return ['ECONNRESET', 'ETIMEDOUT', 'UND_ERR_CONNECT_TIMEOUT', 'UND_ERR_SOCKET'].includes(
    candidate.code ?? candidate.cause?.code ?? ''
  );
}

export async function withRetry<T>(operation: () => Promise<T>, options: RetryOptions = {}): Promise<T> {
  const attempts = options.attempts ?? 3;
  const baseDelayMs = options.baseDelayMs ?? 250;
  const maxDelayMs = options.maxDelayMs ?? 10_000;
  const sleep = options.sleep ?? defaultSleep;
  const random = options.random ?? Math.random;
  const shouldRetry = options.shouldRetry ?? isTransientHttpError;
  const retryAfter = options.retryAfterMs ?? httpRetryAfterMs;
  let lastError: unknown;

  for (let attempt = 1; attempt <= attempts; attempt += 1) {
    try { return await operation(); }
    catch (error) {
      lastError = error;
      if (attempt >= attempts || !shouldRetry(error)) throw error;
      const exponential = baseDelayMs * 2 ** (attempt - 1);
      const hinted = retryAfter(error) ?? 0;
      const delay = Math.min(maxDelayMs, Math.max(hinted, exponential) * (0.8 + random() * 0.4));
      await sleep(Math.round(delay));
    }
  }
  throw lastError;
}

export async function resilientFetch(
  input: string | URL,
  init: RequestInit,
  options: RetryOptions & { timeoutMs?: number } = {}
): Promise<Response> {
  return withRetry(async () => {
    const response = await fetch(input, {
      ...init,
      ...(options.timeoutMs ? { signal: AbortSignal.timeout(options.timeoutMs) } : {})
    });
    if (response.status === 429 || response.status >= 500) {
      const headers = Object.fromEntries(response.headers.entries());
      await response.body?.cancel();
      throw Object.assign(new Error(`HTTP ${response.status}`), { status: response.status, headers });
    }
    return response;
  }, options);
}
