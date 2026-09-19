import { describe, expect, it } from 'vitest';
import {
  assertSafeRepositoryPath,
  isSafeRepositoryPath,
  redactSecrets,
  signWebhook,
  verifyWebhookSignature
} from '@codelens/security';

describe('webhook signatures', () => {
  it('accepts only the exact signed bytes', () => {
    const secret = 'a-long-test-secret-for-signatures';
    const body = Buffer.from('{"ok":true}');
    const signature = signWebhook(body, secret);

    expect(verifyWebhookSignature(body, signature, secret)).toBe(true);
    expect(verifyWebhookSignature(Buffer.from('{"ok":false}'), signature, secret)).toBe(false);
    expect(verifyWebhookSignature(body, undefined, secret)).toBe(false);
  });
});

describe('repository input security', () => {
  it('rejects path traversal, absolute paths, backslashes, and NUL bytes', () => {
    expect(isSafeRepositoryPath('src/payment/service.ts')).toBe(true);
    for (const value of ['../secret', 'src/../../secret', '/etc/passwd', 'C:/secret', 'src\\file.ts', 'src/\0file']) {
      expect(isSafeRepositoryPath(value)).toBe(false);
      expect(() => assertSafeRepositoryPath(value)).toThrow('Unsafe repository path');
    }
  });

  it('redacts common credentials without retaining their values', () => {
    const redacted = redactSecrets([
      'Authorization: Bearer abcdefghijklmnopqrstuvwxyz',
      'api_key="sk-live-super-secret-value"',
      'token: github_pat_abcdefghijklmnopqrstuvwxyz123456'
    ].join('\n'));

    expect(redacted).toContain('[REDACTED]');
    expect(redacted).not.toContain('sk-live-super-secret-value');
    expect(redacted).not.toContain('github_pat_abcdefghijklmnopqrstuvwxyz123456');
  });
});
