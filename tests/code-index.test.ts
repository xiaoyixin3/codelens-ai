import { describe, expect, it } from 'vitest';
import {
  InMemoryCodeIndexStore,
  PullRequestCodeIndexer,
  TypeScriptFileIndexer
} from '@codelens/code-index';
import type { PullRequestContext } from '@codelens/contracts';
import type { CheckInput, CompleteCheckInput, GitHubGateway } from '@codelens/github';

class ContentGateway implements GitHubGateway {
  readonly requestedPaths: string[] = [];
  readonly requestedRefs: string[] = [];

  constructor(private readonly contents: Record<string, string>) {}

  async getFileContent(
    _installationId: number,
    _owner: string,
    _repo: string,
    path: string,
    ref: string
  ): Promise<string> {
    this.requestedPaths.push(path);
    this.requestedRefs.push(ref);
    const content = this.contents[path];
    if (content === undefined) throw new Error(`Missing fixture ${path}`);
    return content;
  }

  async getPullRequest(): Promise<PullRequestContext> {
    throw new Error('Not used by this test.');
  }

  async getCurrentHeadSha(): Promise<string> {
    throw new Error('Not used by this test.');
  }

  async startCheck(_input: CheckInput): Promise<number> {
    throw new Error('Not used by this test.');
  }

  async completeCheck(_input: CompleteCheckInput): Promise<void> {
    throw new Error('Not used by this test.');
  }

  async upsertSummaryComment(): Promise<number> {
    throw new Error('Not used by this test.');
  }
}

describe('TypeScriptFileIndexer', () => {
  it('rejects unsafe repository paths before parsing', () => {
    expect(() => new TypeScriptFileIndexer().index('../outside.ts', 'export const value = 1;'))
      .toThrow('Unsafe repository path');
  });

  it('extracts stable symbols and explainable relationships', () => {
    const source = `
import { charge } from '@acme/payments';

function delay(ms: number): Promise<void> {
  return Promise.resolve();
}

export class CheckoutService {
  async checkout(total: number): Promise<void> {
    await delay(10);
    await charge(total);
  }
}

test('charges the order', async () => {
  const service = new CheckoutService();
  await service.checkout(42);
});
`;
    const result = new TypeScriptFileIndexer().index('src/checkout.ts', source);

    expect(result.parseErrors).toBe(0);
    expect(result.symbols.map((symbol) => [symbol.kind, symbol.qualifiedName])).toEqual(
      expect.arrayContaining([
        ['file', '$module'],
        ['function', 'delay'],
        ['class', 'CheckoutService'],
        ['method', 'CheckoutService.checkout'],
        ['test', '$test.charges the order']
      ])
    );
    expect(result.symbols.find((symbol) => symbol.qualifiedName === 'CheckoutService')?.exported).toBe(true);
    expect(result.edges).toEqual(
      expect.arrayContaining([
        expect.objectContaining({ type: 'IMPORTS', toStableKey: 'external:@acme/payments#*' }),
        expect.objectContaining({ type: 'CALLS', toStableKey: 'external:@acme/payments#charge' }),
        expect.objectContaining({ type: 'CALLS', toStableKey: 'typescript:src/checkout.ts:function:delay' })
      ])
    );
    expect(result.symbols.every((symbol) => symbol.startLine > 0 && symbol.endLine >= symbol.startLine)).toBe(true);
  });

  it('produces the same stable keys when only function bodies change', () => {
    const indexer = new TypeScriptFileIndexer();
    const before = indexer.index('src/math.ts', 'export function add(a: number, b: number) { return a + b; }');
    const after = indexer.index('src/math.ts', 'export function add(a: number, b: number) { return Number(a) + b; }');

    expect(before.symbols.map((symbol) => symbol.stableKey)).toEqual(
      after.symbols.map((symbol) => symbol.stableKey)
    );
    expect(before.symbols.find((symbol) => symbol.name === 'add')?.contentHash).not.toBe(
      after.symbols.find((symbol) => symbol.name === 'add')?.contentHash
    );
  });

  it('reports syntax damage without throwing away the partial index', () => {
    const result = new TypeScriptFileIndexer().index(
      'src/broken.ts',
      'export function broken(value: string { return value; }'
    );

    expect(result.parseErrors).toBeGreaterThan(0);
    expect(result.symbols.some((symbol) => symbol.name === 'broken')).toBe(true);
  });
});

describe('PullRequestCodeIndexer', () => {
  it('skips unsafe paths before requesting repository content', async () => {
    const gateway = new ContentGateway({});
    const store = new InMemoryCodeIndexStore();
    const result = await new PullRequestCodeIndexer(gateway, store).index({
      repositoryId: 99,
      installationId: 42,
      context: {
        owner: 'acme', repo: 'checkout', number: 10, title: 'Unsafe', body: '',
        baseSha: 'ccccccc1111111', headSha: 'ddddddd2222222',
        files: [{ path: '../outside.ts', status: 'modified', additions: 1, deletions: 0, patch: '@@' }]
      }
    });

    expect(gateway.requestedPaths).toHaveLength(0);
    expect(result.baseCoverage.skippedFiles).toBe(1);
    expect(result.headCoverage.skippedFiles).toBe(1);
  });

  it('indexes only changed TS/JS files and reuses a ready commit snapshot', async () => {
    const gateway = new ContentGateway({
      'src/main.ts': 'export function main() { return 1; }',
      'src/old.ts': 'export const oldValue = true;',
      'src/other.ts': 'export const otherValue = true;'
    });
    const store = new InMemoryCodeIndexStore();
    const indexer = new PullRequestCodeIndexer(gateway, store);
    const context: PullRequestContext = {
      owner: 'acme',
      repo: 'checkout',
      number: 9,
      title: 'Change main',
      body: '',
      baseSha: 'aaaaaaa1111111',
      headSha: 'bbbbbbb2222222',
      files: [
        {
          path: 'src/main.ts',
          status: 'modified',
          additions: 1,
          deletions: 1,
          patch: '@@'
        },
        {
          path: 'README.md',
          status: 'modified',
          additions: 2,
          deletions: 0,
          patch: '@@'
        },
        {
          path: 'src/old.ts',
          status: 'removed',
          additions: 0,
          deletions: 20,
          patch: '@@'
        }
      ]
    };

    const first = await indexer.index({ repositoryId: 99, installationId: 42, context });
    const second = await indexer.index({ repositoryId: 99, installationId: 42, context });

    expect(first).toMatchObject({
      baseReused: false,
      headReused: false,
      baseCoverage: {
        totalChangedFiles: 3,
        indexedFiles: 2,
        skippedFiles: 1,
        absentFiles: 0,
        failedFiles: 0
      },
      headCoverage: {
        totalChangedFiles: 3,
        indexedFiles: 1,
        skippedFiles: 1,
        absentFiles: 1,
        failedFiles: 0
      }
    });
    expect(second.baseReused).toBe(true);
    expect(second.headReused).toBe(true);
    expect(gateway.requestedPaths).toHaveLength(3);
    expect(gateway.requestedPaths).toEqual(
      expect.arrayContaining(['src/main.ts', 'src/main.ts', 'src/old.ts'])
    );
    expect(gateway.requestedRefs).toEqual(
      expect.arrayContaining(['aaaaaaa1111111', 'bbbbbbb2222222'])
    );
    expect(store.snapshots.get(first.headSnapshotId)?.status).toBe('ready');
    expect(store.files.get(first.headSnapshotId)?.get('src/main.ts')).toMatchObject({
      language: 'typescript',
      parseErrors: 0
    });

    const differentScope = await indexer.index({
      repositoryId: 99,
      installationId: 42,
      context: {
        ...context,
        files: [
          {
            path: 'src/other.ts',
            status: 'modified',
            additions: 1,
            deletions: 1,
            patch: '@@'
          }
        ]
      }
    });
    expect(differentScope.baseSnapshotId).not.toBe(first.baseSnapshotId);
    expect(differentScope.headSnapshotId).not.toBe(first.headSnapshotId);
  });
});
