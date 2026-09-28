import { describe, expect, it } from 'vitest';
import { loadConfig } from '@codelens/config';

describe('configuration loading', () => {
  it('treats blank optional credentials and model settings as absent', () => {
    const config = loadConfig({
      GITHUB_WEBHOOK_SECRET: 'fixture-secret-at-least-16-characters',
      GITHUB_APP_ID: '',
      GITHUB_PRIVATE_KEY: '  ',
      LLM_BASE_URL: '',
      LLM_API_KEY: '',
      LLM_MODEL: '',
      LLM_FALLBACK_BASE_URL: '',
      LLM_FALLBACK_API_KEY: '',
      LLM_FALLBACK_MODEL: ''
    });

    expect(config.GITHUB_APP_ID).toBeUndefined();
    expect(config.GITHUB_PRIVATE_KEY).toBeUndefined();
    expect(config.LLM_BASE_URL).toBeUndefined();
    expect(config.LLM_API_KEY).toBeUndefined();
    expect(config.LLM_MODEL).toBeUndefined();
    expect(config.LLM_FALLBACK_BASE_URL).toBeUndefined();
  });

  it('constructs a safely encoded database URL from split production credentials', () => {
    const config = loadConfig({
      GITHUB_WEBHOOK_SECRET: 'fixture-secret-at-least-16-characters',
      CODELENS_DB_HOST: 'postgres',
      CODELENS_DB_USER: 'codelens',
      CODELENS_DB_PASSWORD: 'strong:p@ss/word?#value',
      CODELENS_DB_NAME: 'codelens'
    });

    expect(config.DATABASE_URL).toContain('codelens:strong%3Ap%40ss%2Fword%3F%23value@postgres:5432/codelens');
  });
});
