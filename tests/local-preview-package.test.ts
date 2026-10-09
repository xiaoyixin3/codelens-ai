import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

describe('local preview runtime installation', () => {
  it('keeps the TypeScript launcher available without test and build tools', () => {
    const manifest = JSON.parse(readFileSync(new URL('../package.json', import.meta.url), 'utf8'));
    expect(manifest.dependencies.tsx).toBeTruthy();
    expect(manifest.devDependencies.tsx).toBeUndefined();
    expect(manifest.dependencies.vitest).toBeUndefined();
    expect(manifest.dependencies.tsup).toBeUndefined();
  });

  it('uses locked runtime-only dependencies without installation scripts', () => {
    const launcher = readFileSync(new URL('../CodeLens-Local.cmd', import.meta.url), 'utf8');
    expect(launcher).toContain('call npm.cmd ci --omit=dev --ignore-scripts');
    const documentation = readFileSync(new URL('../docs/local-preview-release.md', import.meta.url), 'utf8');
    expect(documentation).toContain('npm ci --omit=dev --ignore-scripts');
  });
});
