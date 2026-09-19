import { createHmac, timingSafeEqual } from 'node:crypto';

export function signWebhook(rawBody: Buffer | string, secret: string): string {
  return `sha256=${createHmac('sha256', secret).update(rawBody).digest('hex')}`;
}

export function verifyWebhookSignature(
  rawBody: Buffer | string,
  signature: string | undefined,
  secret: string
): boolean {
  if (!signature?.startsWith('sha256=')) return false;

  const expected = Buffer.from(signWebhook(rawBody, secret));
  const received = Buffer.from(signature);
  return expected.length === received.length && timingSafeEqual(expected, received);
}

const SECRET_PATTERNS: Array<[RegExp, string]> = [
  [/-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----[\s\S]*?-----END (?:RSA |EC |OPENSSH )?PRIVATE KEY-----/g, '[REDACTED PRIVATE KEY]'],
  [/\b(?:gh[pousr]_[A-Za-z0-9_]{20,}|github_pat_[A-Za-z0-9_]{20,})\b/g, '[REDACTED GITHUB TOKEN]'],
  [/\bAKIA[0-9A-Z]{16}\b/g, '[REDACTED AWS ACCESS KEY]'],
  [/\bBearer\s+[A-Za-z0-9._~+/=-]{16,}\b/gi, 'Bearer [REDACTED]'],
  [/(\b(?:password|passwd|api[_-]?key|secret|access[_-]?token)\b\s*[:=]\s*['"]?)[^'"\s,;]{8,}(['"]?)/gi, '$1[REDACTED]$2']
];

export function redactSecrets(value: string): string {
  return SECRET_PATTERNS.reduce(
    (redacted, [pattern, replacement]) => redacted.replace(pattern, replacement),
    value
  );
}

export function isSafeRepositoryPath(value: string): boolean {
  if (!value || value.includes('\0') || value.includes('\\')) return false;
  if (value.startsWith('/') || /^[A-Za-z]:/.test(value)) return false;
  const segments = value.split('/');
  return segments.every((segment) => segment.length > 0 && segment !== '.' && segment !== '..');
}

export function assertSafeRepositoryPath(value: string): void {
  if (!isSafeRepositoryPath(value)) throw new Error(`Unsafe repository path rejected: ${redactSecrets(value)}`);
}
