import { describe, expect, it } from 'vitest';
import {
  InMemoryCodeIndexStore,
  PullRequestCodeIndexer,
  TypeScriptFileIndexer,
  type CodeGraphSnapshot,
  type SnapshotRecord
} from '@codelens/code-index';
import type { PullRequestContext } from '@codelens/contracts';
import type { CheckInput, CompleteCheckInput, GitHubGateway } from '@codelens/github';
import {
  CodeIntelligencePipeline,
  ImpactAnalyzer,
  InMemoryImpactStore
} from '@codelens/impact-engine';

function graph(commitSha: string, filePath: string, source: string): CodeGraphSnapshot {
  const indexed = new TypeScriptFileIndexer().index(filePath, source);
  const snapshot: SnapshotRecord = {
    id: `${commitSha}-snapshot`,
    repositoryId: 1,
    commitSha,
    baseSha: 'base-sha',
    parserVersion: 'test',
    scopeHash: 'test-scope',
    status: 'ready',
    scope: 'pull_request_delta'
  };
  return { snapshot, symbols: indexed.symbols, edges: indexed.edges };
}

function graphFiles(commitSha: string, files: Record<string, string>): CodeGraphSnapshot {
  const indexed = Object.entries(files).map(([filePath, source]) =>
    new TypeScriptFileIndexer().index(filePath, source)
  );
  const snapshot: SnapshotRecord = {
    id: `${commitSha}-snapshot`,
    repositoryId: 1,
    commitSha,
    baseSha: 'base-sha',
    parserVersion: 'test',
    scopeHash: 'test-scope',
    status: 'ready',
    scope: 'pull_request_delta'
  };
  return {
    snapshot,
    symbols: indexed.flatMap((file) => file.symbols),
    edges: indexed.flatMap((file) => file.edges)
  };
}

class RefContentGateway implements GitHubGateway {
  constructor(private readonly contents: Record<string, string>) {}

  async getFileContent(
    _installationId: number,
    _owner: string,
    _repo: string,
    path: string,
    ref: string
  ): Promise<string> {
    const content = this.contents[`${ref}:${path}`];
    if (content === undefined) throw new Error(`Missing ${ref}:${path}`);
    return content;
  }

  async getPullRequest(): Promise<PullRequestContext> {
    throw new Error('Not used.');
  }

  async getCurrentHeadSha(): Promise<string> {
    throw new Error('Not used.');
  }

  async startCheck(_input: CheckInput): Promise<number> {
    throw new Error('Not used.');
  }

  async completeCheck(_input: CompleteCheckInput): Promise<void> {
    throw new Error('Not used.');
  }

  async upsertSummaryComment(): Promise<number> {
    throw new Error('Not used.');
  }
}

describe('ImpactAnalyzer', () => {
  it('matches symbol changes and walks reverse calls to depth two', () => {
    const base = graph(
      'base-sha',
      'src/service.ts',
      `
export function target(value: number) { return value + 1; }
export function middle() { return target(1); }
export function controller() { return middle(); }
export function removed() { return true; }
`
    );
    const head = graph(
      'head-sha',
      'src/service.ts',
      `
export function target(value: number) { return value + 2; }
export function middle() { return target(1); }
export function controller() { return middle(); }
export function added() { return false; }
`
    );

    const analysis = new ImpactAnalyzer({ maxDepth: 2, maxPaths: 20 }).analyze(base, head);

    expect(analysis.changes).toEqual(
      expect.arrayContaining([
        expect.objectContaining({ type: 'MODIFIED', qualifiedName: 'target' }),
        expect.objectContaining({ type: 'DELETED', qualifiedName: 'removed' }),
        expect.objectContaining({ type: 'ADDED', qualifiedName: 'added' })
      ])
    );
    const targetPaths = analysis.paths.filter((path) => path.changedStableKey.endsWith(':function:target'));
    expect(targetPaths).toEqual(
      expect.arrayContaining([
        expect.objectContaining({ impactedName: 'middle', depth: 1 }),
        expect.objectContaining({ impactedName: 'controller', depth: 2 })
      ])
    );
    expect(analysis.blastRadius.impactedSymbols).toBeGreaterThanOrEqual(2);
    expect(analysis.coverage.scope).toBe('pull_request_delta');
  });

  it('recognizes a symbol moved between files instead of add plus delete', () => {
    const base = graph('base-sha', 'src/old.ts', 'export function calculate() { return 42; }');
    const head = graph('head-sha', 'src/new.ts', 'export function calculate() { return 42; }');

    const changes = new ImpactAnalyzer().compareSymbols(base, head);

    expect(changes).toHaveLength(1);
    expect(changes[0]).toMatchObject({
      type: 'MOVED',
      qualifiedName: 'calculate',
      bodyChanged: false,
      signatureChanged: false
    });
  });

  it('links relative imports to exported symbols across changed files', () => {
    const base = graphFiles('base-sha', {
      'src/service.ts': 'export function target() { return 1; }',
      'src/controller.ts':
        "import { target } from './service'; export function controller() { return target(); }"
    });
    const head = graphFiles('head-sha', {
      'src/service.ts': 'export function target() { return 2; }',
      'src/controller.ts':
        "import { target } from './service'; export function controller() { return target(); }"
    });

    const analysis = new ImpactAnalyzer().analyze(base, head);

    expect(analysis.paths).toEqual(
      expect.arrayContaining([
        expect.objectContaining({
          changedStableKey: 'typescript:src/service.ts:function:target',
          impactedName: 'controller',
          impactedPath: 'src/controller.ts',
          depth: 1
        })
      ])
    );
  });
});

describe('CodeIntelligencePipeline', () => {
  it('persists base/head comparison for the review run', async () => {
    const baseSha = 'aaaaaaa1111111';
    const headSha = 'bbbbbbb2222222';
    const gateway = new RefContentGateway({
      [`${baseSha}:src/service.ts`]:
        'export function target() { return 1; }\nexport function caller() { return target(); }',
      [`${headSha}:src/service.ts`]:
        'export function target() { return 2; }\nexport function caller() { return target(); }'
    });
    const codeStore = new InMemoryCodeIndexStore();
    const impactStore = new InMemoryImpactStore();
    const indexer = new PullRequestCodeIndexer(gateway, codeStore);
    const pipeline = new CodeIntelligencePipeline(indexer, codeStore, impactStore);
    const context: PullRequestContext = {
      owner: 'acme',
      repo: 'checkout',
      number: 12,
      title: 'Change target',
      body: '',
      baseSha,
      headSha,
      files: [
        {
          path: 'src/service.ts',
          status: 'modified',
          additions: 1,
          deletions: 1,
          patch: '@@'
        }
      ]
    };

    const result = await pipeline.index({
      reviewRunId: '00000000-0000-4000-8000-000000000001',
      repositoryId: 99,
      installationId: 42,
      context
    });

    expect(result.impact.changes).toEqual(
      expect.arrayContaining([expect.objectContaining({ type: 'MODIFIED', qualifiedName: 'target' })])
    );
    expect(result.impact.paths).toEqual(
      expect.arrayContaining([expect.objectContaining({ impactedName: 'caller', depth: 1 })])
    );
    expect(impactStore.analyses.get('00000000-0000-4000-8000-000000000001')).toMatchObject({
      baseSnapshotId: result.baseSnapshotId,
      headSnapshotId: result.headSnapshotId
    });
  });
});
