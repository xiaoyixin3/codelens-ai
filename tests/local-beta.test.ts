import { describe, expect, it } from 'vitest';
import {
  buildWebhookUrl,
  extractQuickTunnelUrl,
  normalizePublicBaseUrl,
  withoutProxyEnvironment
} from '../scripts/local-beta.js';

describe('local beta supervisor', () => {
  it('extracts a Cloudflare quick Tunnel URL from mixed logs', () => {
    expect(extractQuickTunnelUrl(
      'INF starting\nVisit https://calm-river-example.trycloudflare.com when ready\n'
    )).toBe('https://calm-river-example.trycloudflare.com');
  });

  it('builds the exact GitHub webhook endpoint', () => {
    expect(buildWebhookUrl('https://calm-river-example.trycloudflare.com'))
      .toBe('https://calm-river-example.trycloudflare.com/webhooks/github');
  });

  it('accepts only a pathless HTTPS origin for a fixed Tunnel', () => {
    expect(normalizePublicBaseUrl('https://reviews.example.com/'))
      .toBe('https://reviews.example.com');
    expect(() => normalizePublicBaseUrl('http://reviews.example.com'))
      .toThrow('must use HTTPS');
    expect(() => normalizePublicBaseUrl('https://reviews.example.com/admin'))
      .toThrow('without credentials, path, query, or fragment');
  });

  it('removes proxy variables from the ngrok child environment', () => {
    expect(withoutProxyEnvironment({
      HTTP_PROXY: 'socks5h://127.0.0.1:7897',
      HTTPS_PROXY: 'socks5h://127.0.0.1:7897',
      ALL_PROXY: 'socks5h://127.0.0.1:7897',
      KEEP_ME: 'yes'
    })).toEqual({ KEEP_ME: 'yes' });
  });
});
