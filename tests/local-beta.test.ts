import { describe, expect, it } from 'vitest';
import { buildWebhookUrl, extractQuickTunnelUrl } from '../scripts/local-beta.js';

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
});
