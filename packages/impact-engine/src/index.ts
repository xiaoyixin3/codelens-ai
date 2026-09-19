import { randomUUID } from 'node:crypto';
import path from 'node:path';
import postgres, { type Sql } from 'postgres';
import type {
  CodeEdge,
  CodeGraphSnapshot,
  CodeIndexStore,
  CodeSymbol,
  PullRequestCodeIndexer,
  PullRequestIndexInput,
  PullRequestIndexResult
} from '@codelens/code-index';

export type SymbolChangeType = 'ADDED' | 'MODIFIED' | 'DELETED' | 'MOVED';

export interface SymbolChange {
  type: SymbolChangeType;
  kind: CodeSymbol['kind'];
  qualifiedName: string;
  before?: CodeSymbol;
  after?: CodeSymbol;
  bodyChanged: boolean;
  signatureChanged: boolean;
}

export interface ImpactPath {
  changedStableKey: string;
  impactedStableKey: string;
  impactedName: string;
  impactedPath: string;
  impactedKind: CodeSymbol['kind'];
  depth: number;
  score: number;
  path: string[];
  evidence: Array<{
    type: CodeEdge['type'];
    sourcePath: string;
    sourceLine: number;
    confidence: number;
  }>;
}

export interface ImpactAnalysis {
  changes: SymbolChange[];
  paths: ImpactPath[];
  blastRadius: {
    score: number;
    level: 'low' | 'medium' | 'high';
    changedSymbols: number;
    impactedSymbols: number;
    exportedChanges: number;
  };
  coverage: {
    scope: 'pull_request_delta';
    baseSymbols: number;
    headSymbols: number;
    baseEdges: number;
    headEdges: number;
    maxDepth: number;
    warning: string;
  };
}

export interface ImpactAnalyzerOptions {
  maxDepth: number;
  maxPaths: number;
}

function nonFileSymbols(graph: CodeGraphSnapshot): CodeSymbol[] {
  return graph.symbols.filter((symbol) => symbol.kind !== 'file');
}

function uniqueCandidate(
  candidates: Map<string, CodeSymbol[]>,
  key: string,
  used: Set<string>
): CodeSymbol | undefined {
  const available = (candidates.get(key) ?? []).filter((symbol) => !used.has(symbol.stableKey));
  return available.length === 1 ? available[0] : undefined;
}

function groupSymbols(symbols: CodeSymbol[], key: (symbol: CodeSymbol) => string): Map<string, CodeSymbol[]> {
  const groups = new Map<string, CodeSymbol[]>();
  for (const symbol of symbols) {
    const value = key(symbol);
    groups.set(value, [...(groups.get(value) ?? []), symbol]);
  }
  return groups;
}

function linkInternalEdges(graph: CodeGraphSnapshot): CodeEdge[] {
  const fileSymbols = new Map(
    graph.symbols.filter((symbol) => symbol.kind === 'file').map((symbol) => [symbol.path, symbol])
  );
  const exported = new Map<string, CodeSymbol[]>();
  for (const symbol of graph.symbols) {
    if (!symbol.exported || symbol.kind === 'file') continue;
    const key = `${symbol.path}|${symbol.name}`;
    exported.set(key, [...(exported.get(key) ?? []), symbol]);
  }

  const resolveModulePath = (sourcePath: string, specifier: string): string | undefined => {
    if (!specifier.startsWith('.')) return undefined;
    const base = path.posix.normalize(path.posix.join(path.posix.dirname(sourcePath), specifier));
    const extension = path.posix.extname(base);
    const candidates = extension
      ? [base]
      : [
          `${base}.ts`,
          `${base}.tsx`,
          `${base}.js`,
          `${base}.jsx`,
          `${base}.mts`,
          `${base}.cts`,
          `${base}.mjs`,
          `${base}.cjs`,
          `${base}/index.ts`,
          `${base}/index.tsx`,
          `${base}/index.js`,
          `${base}/index.jsx`
        ];
    return candidates.find((candidate) => fileSymbols.has(candidate));
  };

  return graph.edges.map((edge) => {
    if (!edge.toStableKey.startsWith('external:')) return edge;
    const descriptor = edge.toStableKey.slice('external:'.length);
    const separator = descriptor.lastIndexOf('#');
    if (separator < 0) return edge;
    const specifier = descriptor.slice(0, separator);
    const exportedName = descriptor.slice(separator + 1);
    const modulePath = resolveModulePath(edge.sourcePath, specifier);
    if (!modulePath) return edge;

    if (exportedName === '*') {
      const file = fileSymbols.get(modulePath);
      return file ? { ...edge, toStableKey: file.stableKey, confidence: Math.max(edge.confidence, 0.9) } : edge;
    }
    const candidates = exportedName === 'default'
      ? graph.symbols.filter(
          (symbol) => symbol.path === modulePath && symbol.exported && symbol.metadata.default === true
        )
      : (exported.get(`${modulePath}|${exportedName}`) ?? []);
    return candidates.length === 1
      ? { ...edge, toStableKey: candidates[0]!.stableKey, confidence: Math.max(edge.confidence, 0.9) }
      : edge;
  });
}

export class ImpactAnalyzer {
  constructor(private readonly options: ImpactAnalyzerOptions = { maxDepth: 2, maxPaths: 200 }) {}

  compareSymbols(base: CodeGraphSnapshot, head: CodeGraphSnapshot): SymbolChange[] {
    const baseSymbols = nonFileSymbols(base);
    const headSymbols = nonFileSymbols(head);
    const headByStableKey = new Map(headSymbols.map((symbol) => [symbol.stableKey, symbol]));
    const matchedHead = new Set<string>();
    const unmatchedBase: CodeSymbol[] = [];
    const changes: SymbolChange[] = [];

    for (const before of baseSymbols) {
      const after = headByStableKey.get(before.stableKey);
      if (!after) {
        unmatchedBase.push(before);
        continue;
      }
      matchedHead.add(after.stableKey);
      if (before.contentHash !== after.contentHash || before.signature !== after.signature) {
        changes.push({
          type: 'MODIFIED',
          kind: after.kind,
          qualifiedName: after.qualifiedName,
          before,
          after,
          bodyChanged: before.contentHash !== after.contentHash,
          signatureChanged: before.signature !== after.signature
        });
      }
    }

    const unmatchedHead = headSymbols.filter((symbol) => !matchedHead.has(symbol.stableKey));
    const headByIdentityAndContent = groupSymbols(
      unmatchedHead,
      (symbol) => `${symbol.kind}|${symbol.qualifiedName}|${symbol.contentHash}`
    );
    const headByIdentity = groupSymbols(
      unmatchedHead,
      (symbol) => `${symbol.kind}|${symbol.qualifiedName}`
    );
    const movedHead = new Set<string>();
    const deleted: CodeSymbol[] = [];

    for (const before of unmatchedBase) {
      const contentKey = `${before.kind}|${before.qualifiedName}|${before.contentHash}`;
      const identityKey = `${before.kind}|${before.qualifiedName}`;
      const after =
        uniqueCandidate(headByIdentityAndContent, contentKey, movedHead) ??
        uniqueCandidate(headByIdentity, identityKey, movedHead);
      if (!after) {
        deleted.push(before);
        continue;
      }
      movedHead.add(after.stableKey);
      changes.push({
        type: 'MOVED',
        kind: after.kind,
        qualifiedName: after.qualifiedName,
        before,
        after,
        bodyChanged: before.contentHash !== after.contentHash,
        signatureChanged: before.signature !== after.signature
      });
    }

    for (const before of deleted) {
      changes.push({
        type: 'DELETED',
        kind: before.kind,
        qualifiedName: before.qualifiedName,
        before,
        bodyChanged: false,
        signatureChanged: false
      });
    }
    for (const after of unmatchedHead) {
      if (movedHead.has(after.stableKey)) continue;
      changes.push({
        type: 'ADDED',
        kind: after.kind,
        qualifiedName: after.qualifiedName,
        after,
        bodyChanged: false,
        signatureChanged: false
      });
    }

    return changes.sort((left, right) => {
      const leftPath = left.after?.path ?? left.before?.path ?? '';
      const rightPath = right.after?.path ?? right.before?.path ?? '';
      return leftPath.localeCompare(rightPath) || left.qualifiedName.localeCompare(right.qualifiedName);
    });
  }

  analyze(base: CodeGraphSnapshot, head: CodeGraphSnapshot): ImpactAnalysis {
    const changes = this.compareSymbols(base, head);
    const baseSymbols = new Map(base.symbols.map((symbol) => [symbol.stableKey, symbol]));
    const headSymbols = new Map(head.symbols.map((symbol) => [symbol.stableKey, symbol]));
    const paths: ImpactPath[] = [];

    for (const change of changes) {
      if (paths.length >= this.options.maxPaths) break;
      const graph = change.type === 'DELETED' ? base : head;
      const symbols = change.type === 'DELETED' ? baseSymbols : headSymbols;
      const seed = change.after?.stableKey ?? change.before?.stableKey;
      if (!seed) continue;
      const reverse = new Map<string, CodeEdge[]>();
      const linkedEdges = linkInternalEdges(graph).sort(
        (left, right) =>
          right.confidence - left.confidence ||
          left.sourcePath.localeCompare(right.sourcePath) ||
          left.sourceLine - right.sourceLine
      );
      for (const edge of linkedEdges) {
        if (edge.type !== 'CALLS' && edge.type !== 'IMPORTS') continue;
        reverse.set(edge.toStableKey, [...(reverse.get(edge.toStableKey) ?? []), edge]);
      }

      const queue: Array<{
        key: string;
        depth: number;
        path: string[];
        evidence: ImpactPath['evidence'];
        score: number;
      }> = [{ key: seed, depth: 0, path: [seed], evidence: [], score: 1 }];
      const visited = new Set<string>([seed]);

      while (queue.length && paths.length < this.options.maxPaths) {
        const current = queue.shift();
        if (!current || current.depth >= this.options.maxDepth) continue;
        for (const edge of reverse.get(current.key) ?? []) {
          const caller = symbols.get(edge.fromStableKey);
          if (!caller || visited.has(caller.stableKey)) continue;
          visited.add(caller.stableKey);
          const depth = current.depth + 1;
          const edgeWeight = edge.type === 'CALLS' ? 1 : 0.6;
          const score = Number((current.score * edgeWeight * edge.confidence * (depth === 1 ? 1 : 0.55)).toFixed(3));
          const evidence = [
            {
              type: edge.type,
              sourcePath: edge.sourcePath,
              sourceLine: edge.sourceLine,
              confidence: edge.confidence
            },
            ...current.evidence
          ];
          const path = [caller.stableKey, ...current.path];
          paths.push({
            changedStableKey: seed,
            impactedStableKey: caller.stableKey,
            impactedName: caller.qualifiedName,
            impactedPath: caller.path,
            impactedKind: caller.kind,
            depth,
            score,
            path,
            evidence
          });
          queue.push({ key: caller.stableKey, depth, path, evidence, score });
        }
      }
    }

    const impactedSymbols = new Set(paths.map((impact) => impact.impactedStableKey)).size;
    const exportedChanges = changes.filter((change) => change.after?.exported || change.before?.exported).length;
    const pathWeight = paths.reduce((sum, impact) => sum + impact.score, 0);
    const score = Math.min(100, Math.round(changes.length * 3 + exportedChanges * 5 + pathWeight * 8));
    const level = score >= 60 ? 'high' : score >= 25 ? 'medium' : 'low';
    paths.sort(
      (left, right) =>
        right.score - left.score ||
        left.depth - right.depth ||
        left.impactedName.localeCompare(right.impactedName)
    );

    return {
      changes,
      paths,
      blastRadius: {
        score,
        level,
        changedSymbols: changes.length,
        impactedSymbols,
        exportedChanges
      },
      coverage: {
        scope: 'pull_request_delta',
        baseSymbols: base.symbols.length,
        headSymbols: head.symbols.length,
        baseEdges: base.edges.length,
        headEdges: head.edges.length,
        maxDepth: this.options.maxDepth,
        warning: 'Impact paths are limited to files changed by this pull request; unchanged callers are not indexed yet.'
      }
    };
  }
}

export interface ImpactStore {
  save(input: {
    reviewRunId: string;
    baseSnapshotId: string;
    headSnapshotId: string;
    analysis: ImpactAnalysis;
  }): Promise<string>;
  close(): Promise<void>;
}

export class PostgresImpactStore implements ImpactStore {
  readonly #sql: Sql;

  constructor(databaseUrl: string) {
    this.#sql = postgres(databaseUrl, { max: 5 });
  }

  async save(input: {
    reviewRunId: string;
    baseSnapshotId: string;
    headSnapshotId: string;
    analysis: ImpactAnalysis;
  }): Promise<string> {
    return this.#sql.begin(async (sql) => {
      const rows = await sql<{ id: string }[]>`
        INSERT INTO impact_analyses (
          id, review_run_id, base_snapshot_id, head_snapshot_id, max_depth,
          blast_radius_score, risk_level, coverage, updated_at
        ) VALUES (
          ${randomUUID()}, ${input.reviewRunId}, ${input.baseSnapshotId}, ${input.headSnapshotId},
          ${input.analysis.coverage.maxDepth}, ${input.analysis.blastRadius.score},
          ${input.analysis.blastRadius.level},
          ${sql.json(JSON.parse(JSON.stringify(input.analysis.coverage)))}, now()
        )
        ON CONFLICT (review_run_id) DO UPDATE
        SET base_snapshot_id = EXCLUDED.base_snapshot_id,
            head_snapshot_id = EXCLUDED.head_snapshot_id,
            max_depth = EXCLUDED.max_depth,
            blast_radius_score = EXCLUDED.blast_radius_score,
            risk_level = EXCLUDED.risk_level,
            coverage = EXCLUDED.coverage,
            updated_at = now()
        RETURNING id
      `;
      const analysisId = rows[0]?.id;
      if (!analysisId) throw new Error('Impact analysis upsert returned no id.');
      await sql`DELETE FROM symbol_changes WHERE impact_analysis_id = ${analysisId}`;
      await sql`DELETE FROM impact_paths WHERE impact_analysis_id = ${analysisId}`;

      for (const change of input.analysis.changes) {
        await sql`
          INSERT INTO symbol_changes (
            id, impact_analysis_id, change_type, kind, qualified_name,
            before_stable_key, after_stable_key, before_path, after_path,
            body_changed, signature_changed
          ) VALUES (
            ${randomUUID()}, ${analysisId}, ${change.type}, ${change.kind}, ${change.qualifiedName},
            ${change.before?.stableKey ?? null}, ${change.after?.stableKey ?? null},
            ${change.before?.path ?? null}, ${change.after?.path ?? null},
            ${change.bodyChanged}, ${change.signatureChanged}
          )
        `;
      }
      for (const impact of input.analysis.paths) {
        await sql`
          INSERT INTO impact_paths (
            id, impact_analysis_id, changed_stable_key, impacted_stable_key,
            impacted_name, impacted_path, impacted_kind, depth, score, path, evidence
          ) VALUES (
            ${randomUUID()}, ${analysisId}, ${impact.changedStableKey}, ${impact.impactedStableKey},
            ${impact.impactedName}, ${impact.impactedPath}, ${impact.impactedKind},
            ${impact.depth}, ${impact.score},
            ${sql.json(impact.path)}, ${sql.json(JSON.parse(JSON.stringify(impact.evidence)))}
          )
        `;
      }
      return analysisId;
    });
  }

  async close(): Promise<void> {
    await this.#sql.end();
  }
}

export class InMemoryImpactStore implements ImpactStore {
  readonly analyses = new Map<
    string,
    {
      id: string;
      baseSnapshotId: string;
      headSnapshotId: string;
      analysis: ImpactAnalysis;
    }
  >();

  async save(input: {
    reviewRunId: string;
    baseSnapshotId: string;
    headSnapshotId: string;
    analysis: ImpactAnalysis;
  }): Promise<string> {
    const id = this.analyses.get(input.reviewRunId)?.id ?? randomUUID();
    this.analyses.set(input.reviewRunId, { id, ...input });
    return id;
  }

  async close(): Promise<void> {}
}

export class CodeIntelligencePipeline {
  constructor(
    private readonly indexer: PullRequestCodeIndexer,
    private readonly codeStore: CodeIndexStore,
    private readonly impacts: ImpactStore,
    private readonly analyzer = new ImpactAnalyzer()
  ) {}

  async index(
    input: PullRequestIndexInput & { reviewRunId: string }
  ): Promise<PullRequestIndexResult & { impactAnalysisId: string; impact: ImpactAnalysis }> {
    const indexed = await this.indexer.index(input);
    const [base, head] = await Promise.all([
      this.codeStore.loadSnapshotGraph(indexed.baseSnapshotId),
      this.codeStore.loadSnapshotGraph(indexed.headSnapshotId)
    ]);
    const impact = this.analyzer.analyze(base, head);
    const impactAnalysisId = await this.impacts.save({
      reviewRunId: input.reviewRunId,
      baseSnapshotId: indexed.baseSnapshotId,
      headSnapshotId: indexed.headSnapshotId,
      analysis: impact
    });
    return { ...indexed, impactAnalysisId, impact };
  }
}
