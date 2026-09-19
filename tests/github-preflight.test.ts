import { describe, expect, it } from 'vitest';
import { assessGitHubPermissions } from '@codelens/github';

describe('GitHub App permission preflight', () => {
  it('accepts the minimum required permissions', () => {
    expect(assessGitHubPermissions({
      metadata: 'read',
      contents: 'read',
      pull_requests: 'write',
      checks: 'write',
      issues: 'read'
    })).toEqual({ ok: true, missing: [] });
  });

  it('accepts stronger permissions', () => {
    expect(assessGitHubPermissions({
      metadata: 'read',
      contents: 'write',
      pull_requests: 'admin',
      checks: 'write',
      issues: 'write'
    }).ok).toBe(true);
  });

  it('reports absent and insufficient permissions without exposing credentials', () => {
    expect(assessGitHubPermissions({
      metadata: 'read',
      contents: 'read',
      pull_requests: 'read',
      checks: 'write'
    })).toEqual({
      ok: false,
      missing: [
        { permission: 'pull_requests', required: 'write', actual: 'read' },
        { permission: 'issues', required: 'read', actual: 'none' }
      ]
    });
  });
});
