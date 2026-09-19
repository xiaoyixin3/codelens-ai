import { z } from 'zod';

const optionalString = z.string().trim().min(1).optional();

const EnvSchema = z.object({
  NODE_ENV: z.enum(['development', 'test', 'production']).default('development'),
  PORT: z.coerce.number().int().positive().default(3000),
  LOG_LEVEL: z.enum(['fatal', 'error', 'warn', 'info', 'debug', 'trace', 'silent']).default('info'),
  DATABASE_URL: z.string().url().default('postgres://codelens:codelens@localhost:5432/codelens'),
  REDIS_URL: z.string().url().default('redis://localhost:6379'),
  GITHUB_APP_ID: optionalString,
  GITHUB_PRIVATE_KEY: optionalString,
  GITHUB_WEBHOOK_SECRET: z.string().min(16),
  WEBHOOK_RATE_LIMIT_MAX: z.coerce.number().int().positive().default(300),
  LLM_BASE_URL: z.string().url().optional(),
  LLM_API_KEY: optionalString,
  LLM_MODEL: optionalString,
  LLM_FALLBACK_BASE_URL: z.string().url().optional(),
  LLM_FALLBACK_API_KEY: optionalString,
  LLM_FALLBACK_MODEL: optionalString,
  LLM_MAX_CALLS_PER_RUN: z.coerce.number().int().nonnegative().default(4),
  LLM_MAX_INPUT_CHARS_PER_RUN: z.coerce.number().int().nonnegative().default(250_000),
  RETENTION_REVIEW_DAYS: z.coerce.number().int().positive().default(90),
  RETENTION_SNAPSHOT_DAYS: z.coerce.number().int().positive().default(30),
  RETENTION_WEBHOOK_DAYS: z.coerce.number().int().positive().default(30),
  RETENTION_LLM_TELEMETRY_DAYS: z.coerce.number().int().positive().default(90),
  MAX_CHANGED_FILES: z.coerce.number().int().positive().default(100),
  MAX_PATCH_CHARS: z.coerce.number().int().positive().default(120_000),
  MAX_INDEX_FILE_BYTES: z.coerce.number().int().positive().default(500_000),
  MAX_INLINE_COMMENTS: z.coerce.number().int().nonnegative().default(8)
});

export type AppConfig = z.infer<typeof EnvSchema>;

export function loadConfig(env: NodeJS.ProcessEnv = process.env): AppConfig {
  const normalized = {
    ...env,
    GITHUB_PRIVATE_KEY: env.GITHUB_PRIVATE_KEY?.replace(/\\n/g, '\n')
  };
  return EnvSchema.parse(normalized);
}
