import { describe, expect, it } from 'vitest';
import type { PullRequestContext } from '@codelens/contracts';
import type { CheckInput, CompleteCheckInput, GitHubGateway } from '@codelens/github';
import {
  InMemoryPolicyStore,
  RepositoryPolicyLoader,
  filterPolicyFiles,
  parseCodeLensMarkdown,
  parsePolicyFile
} from '@codelens/project-policy';

class PolicyGateway implements GitHubGateway {
  readonly refs: string[] = [];
  constructor(private readonly files: Record<string, string>) {}
  async getFileContent(
    _installationId: number,
    _owner: string,
    _repo: string,
    path: string,
    ref: string
  ): Promise<string> {
    this.refs.push(ref);
    const value = this.files[path];
    if (value === undefined) throw Object.assign(new Error('Not found'), { status: 404 });
    return value;
  }
  async getPullRequest(): Promise<PullRequestContext> { throw new Error('Not used'); }
  async getCurrentHeadSha(): Promise<string> { throw new Error('Not used'); }
  async startCheck(_input: CheckInput): Promise<number> { throw new Error('Not used'); }
  async completeCheck(_input: CompleteCheckInput): Promise<void> { throw new Error('Not used'); }
  async upsertSummaryComment(): Promise<number> { throw new Error('Not used'); }
}

describe('repository policy', () => {
  it('strictly validates the YAML contract', () => {
    const parsed = parsePolicyFile(`
version: 1
review:
  language: zh
  blocking: true
  maxInlineComments: 3
  minimumConfidence:
    high: 0.92
include: ["src/**"]
exclude: ["**/*.generated.ts"]
rules:
  - key: payment-idempotency
    content: Payment writes must use an idempotency key.
    scope: src/payment/**
    severity: high
`);

    expect(parsed.review).toMatchObject({ language: 'zh', blocking: true, maxInlineComments: 3 });
    expect(parsed.rules[0]).toMatchObject({ key: 'payment-idempotency', enabled: true });
    expect(() => parsePolicyFile('version: 2')).toThrow();
  });

  it('loads both policy sources at the immutable head SHA and persists their hash', async () => {
    const gateway = new PolicyGateway({
      '.codelens.yml': 'version: 1\ninclude: ["src/**"]\nexclude: ["**/*.generated.ts"]',
      'CODELENS.md': '# Project guidance\n- All payment writes need an idempotency key.\n- Never log credentials.'
    });
    const store = new InMemoryPolicyStore();
    const policy = await new RepositoryPolicyLoader(gateway, store).load({
      repositoryId: 99,
      installationId: 42,
      owner: 'acme',
      repo: 'checkout',
      headSha: 'bbbbbbb2222222'
    });

    expect(gateway.refs).toEqual(['bbbbbbb2222222', 'bbbbbbb2222222']);
    expect(policy.rules).toHaveLength(2);
    expect(policy.hash).toMatch(/^[a-f0-9]{64}$/);
    expect(store.policies.get('99:bbbbbbb2222222')?.hash).toBe(policy.hash);
    expect(filterPolicyFiles([
      { path: 'src/pay.ts', status: 'modified', additions: 1, deletions: 0, patch: '' },
      { path: 'src/pay.generated.ts', status: 'modified', additions: 1, deletions: 0, patch: '' },
      { path: 'docs/readme.md', status: 'modified', additions: 1, deletions: 0, patch: '' }
    ], policy).map((file) => file.path)).toEqual(['src/pay.ts']);
  });

  it('extracts only bounded bullet rules from CODELENS.md', () => {
    expect(parseCodeLensMarkdown('Intro\n- First rule\n* Second rule\nParagraph').map((rule) => rule.content))
      .toEqual(['First rule', 'Second rule']);
  });
});
