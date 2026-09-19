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
});
