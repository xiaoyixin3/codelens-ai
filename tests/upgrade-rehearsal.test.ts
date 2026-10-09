import { spawnSync } from 'node:child_process';
import { describe, expect, it } from 'vitest';

describe('isolated publication upgrade rehearsal', () => {
  it.each([{ args: [] as string[] }, { args: ['--isolated-fixture', '--database-url=external-target'] }])(
    'rejects missing activation and arbitrary database arguments before work: %j', ({ args }) => {
      const result = spawnSync(process.execPath, ['--import', 'tsx',
        'scripts/rehearse-publication-upgrade.ts', ...args],
      { encoding: 'utf8', windowsHide: true, timeout: 10_000 });
      expect(result.error).toBeUndefined();
      expect(result.status).not.toBe(0);
      expect(result.stdout).toBe('');
      expect(result.stderr).toContain('Requires --isolated-fixture');
      expect(result.stderr).not.toContain('external-target');
    });
});
