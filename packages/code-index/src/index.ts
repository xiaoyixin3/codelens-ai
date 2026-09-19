import { createHash, randomUUID } from 'node:crypto';
import path from 'node:path';
import postgres, { type Sql } from 'postgres';
import ts from 'typescript';
import type { ChangedFile, PullRequestContext } from '@codelens/contracts';
import type { GitHubGateway } from '@codelens/github';
import { assertSafeRepositoryPath, isSafeRepositoryPath, redactSecrets } from '@codelens/security';

export const CODE_INDEX_VERSION = 'ts-compiler-v1';

export type CodeSymbolKind =
  | 'file'
  | 'class'
  | 'interface'
  | 'type'
  | 'enum'
  | 'function'
  | 'method'
  | 'variable'
  | 'test';

export type CodeEdgeType = 'DECLARES' | 'IMPORTS' | 'EXPORTS' | 'CALLS';

export interface CodeSymbol {
  stableKey: string;
  path: string;
  kind: CodeSymbolKind;
  name: string;
  qualifiedName: string;
  startLine: number;
  endLine: number;
  signature: string;
  contentHash: string;
  exported: boolean;
  metadata: Record<string, string | number | boolean>;
}

export interface CodeEdge {
  fromStableKey: string;
  toStableKey: string;
  type: CodeEdgeType;
  confidence: number;
  sourcePath: string;
  sourceLine: number;
}

export interface FileIndexResult {
  path: string;
  language: 'typescript' | 'javascript';
  contentHash: string;
  symbols: CodeSymbol[];
  edges: CodeEdge[];
  parseErrors: number;
}

export interface SnapshotRecord {
  id: string;
  repositoryId: number;
  commitSha: string;
  baseSha: string;
  parserVersion: string;
  scopeHash: string;
  status: 'building' | 'ready' | 'failed';
  scope: 'pull_request_delta';
  coverage?: SnapshotCoverage;
}

export interface SnapshotCoverage {
  totalChangedFiles: number;
  indexedFiles: number;
  skippedFiles: number;
  absentFiles: number;
  failedFiles: number;
}

export interface CodeGraphSnapshot {
  snapshot: SnapshotRecord;
  symbols: CodeSymbol[];
  edges: CodeEdge[];
}

export interface CodeIndexStore {
  beginSnapshot(input: {
    repositoryId: number;
    commitSha: string;
    baseSha: string;
    parserVersion: string;
    scopeHash: string;
  }): Promise<{ snapshot: SnapshotRecord; created: boolean }>;
  replaceFile(snapshotId: string, result: FileIndexResult): Promise<void>;
  recordSkippedFile(
    snapshotId: string,
    file: ChangedFile,
    status: 'skipped' | 'absent' | 'failed',
    reason: string
  ): Promise<void>;
  loadSnapshotGraph(snapshotId: string): Promise<CodeGraphSnapshot>;
  completeSnapshot(snapshotId: string, coverage: SnapshotCoverage): Promise<void>;
  failSnapshot(snapshotId: string, error: string): Promise<void>;
  close(): Promise<void>;
}

function normalizePath(filePath: string): string {
  return filePath.replace(/\\/g, '/').replace(/^\.\//, '');
}

function hash(content: string): string {
  return createHash('sha256').update(content).digest('hex');
}

function scriptKind(filePath: string): ts.ScriptKind {
  if (filePath.endsWith('.tsx')) return ts.ScriptKind.TSX;
  if (filePath.endsWith('.jsx')) return ts.ScriptKind.JSX;
  if (filePath.endsWith('.js') || filePath.endsWith('.mjs') || filePath.endsWith('.cjs')) {
    return ts.ScriptKind.JS;
  }
  return ts.ScriptKind.TS;
}

function language(filePath: string): 'typescript' | 'javascript' {
  return /\.[cm]?jsx?$/.test(filePath) ? 'javascript' : 'typescript';
}

function hasModifier(node: ts.Node, kind: ts.SyntaxKind): boolean {
  return Boolean(ts.canHaveModifiers(node) && ts.getModifiers(node)?.some((modifier) => modifier.kind === kind));
}

function isExported(node: ts.Node): boolean {
  return hasModifier(node, ts.SyntaxKind.ExportKeyword) || hasModifier(node, ts.SyntaxKind.DefaultKeyword);
}

function nodeName(node: ts.Node): string | undefined {
  if (
    ts.isClassDeclaration(node) ||
    ts.isInterfaceDeclaration(node) ||
    ts.isTypeAliasDeclaration(node) ||
    ts.isEnumDeclaration(node) ||
    ts.isFunctionDeclaration(node) ||
    ts.isMethodDeclaration(node) ||
    ts.isVariableDeclaration(node)
  ) {
    return node.name && ts.isIdentifier(node.name) ? node.name.text : node.name?.getText();
  }
  return undefined;
}

function symbolKind(node: ts.Node): CodeSymbolKind | undefined {
  if (ts.isClassDeclaration(node)) return 'class';
  if (ts.isInterfaceDeclaration(node)) return 'interface';
  if (ts.isTypeAliasDeclaration(node)) return 'type';
  if (ts.isEnumDeclaration(node)) return 'enum';
  if (ts.isFunctionDeclaration(node)) return 'function';
  if (ts.isMethodDeclaration(node)) return 'method';
  if (ts.isVariableDeclaration(node)) return 'variable';
  return undefined;
}

function callableSignature(node: ts.Node, sourceFile: ts.SourceFile): string {
  if (ts.isFunctionDeclaration(node) || ts.isMethodDeclaration(node)) {
    const name = nodeName(node) ?? '<anonymous>';
    const parameters = node.parameters.map((parameter) => parameter.getText(sourceFile)).join(', ');
    const returnType = node.type ? `: ${node.type.getText(sourceFile)}` : '';
    return `${name}(${parameters})${returnType}`.slice(0, 1_000);
  }
  if (
    ts.isClassDeclaration(node) ||
    ts.isInterfaceDeclaration(node) ||
    ts.isTypeAliasDeclaration(node) ||
    ts.isEnumDeclaration(node)
  ) {
    return (nodeName(node) ?? '<anonymous>').slice(0, 1_000);
  }
  return node.getText(sourceFile).split(/[=;]/, 1)[0]?.trim().slice(0, 1_000) ?? '';
}

function lineRange(sourceFile: ts.SourceFile, node: ts.Node): { startLine: number; endLine: number } {
  return {
    startLine: sourceFile.getLineAndCharacterOfPosition(node.getStart(sourceFile)).line + 1,
    endLine: sourceFile.getLineAndCharacterOfPosition(node.getEnd()).line + 1
  };
}

function fileStableKey(filePath: string): string {
  return `typescript:${normalizePath(filePath)}:file:$module`;
}

function declarationStableKey(filePath: string, kind: CodeSymbolKind, qualifiedName: string): string {
  return `typescript:${normalizePath(filePath)}:${kind}:${qualifiedName}`;
}

function externalStableKey(moduleName: string, exportedName = '*'): string {
  return `external:${moduleName}#${exportedName}`;
}

function syntaxErrorCount(sourceFile: ts.SourceFile): number {
  const parseDiagnostics = (sourceFile as ts.SourceFile & { parseDiagnostics?: readonly ts.Diagnostic[] })
    .parseDiagnostics;
  return parseDiagnostics?.length ?? 0;
}

export class TypeScriptFileIndexer {
  index(filePath: string, content: string): FileIndexResult {
    assertSafeRepositoryPath(filePath);
    const normalizedPath = normalizePath(filePath);
    const sourceFile = ts.createSourceFile(
      normalizedPath,
      content,
      ts.ScriptTarget.Latest,
      true,
      scriptKind(normalizedPath)
    );
    const moduleKey = fileStableKey(normalizedPath);
    const symbols: CodeSymbol[] = [
      {
        stableKey: moduleKey,
        path: normalizedPath,
        kind: 'file',
        name: path.posix.basename(normalizedPath),
        qualifiedName: '$module',
        startLine: 1,
        endLine: Math.max(1, sourceFile.getLineAndCharacterOfPosition(sourceFile.end).line + 1),
        signature: normalizedPath,
        contentHash: hash(content),
        exported: true,
        metadata: { scriptKind: ts.ScriptKind[scriptKind(normalizedPath)] ?? 'Unknown' }
      }
    ];
    const edges: CodeEdge[] = [];
    const declarationByName = new Map<string, string>();
    const importedByName = new Map<string, string>();
    const nodeToSymbol = new Map<ts.Node, string>();
    const scopeNames: string[] = [];

    const addEdge = (edge: CodeEdge): void => {
      edges.push(edge);
    };

    const collectDeclarations = (node: ts.Node): void => {
      if (ts.isImportDeclaration(node) && ts.isStringLiteral(node.moduleSpecifier)) {
        const moduleName = node.moduleSpecifier.text;
        addEdge({
          fromStableKey: moduleKey,
          toStableKey: externalStableKey(moduleName),
          type: 'IMPORTS',
          confidence: 1,
          sourcePath: normalizedPath,
          sourceLine: lineRange(sourceFile, node).startLine
        });
        const clause = node.importClause;
        if (clause?.name) importedByName.set(clause.name.text, externalStableKey(moduleName, 'default'));
        if (clause?.namedBindings && ts.isNamedImports(clause.namedBindings)) {
          for (const element of clause.namedBindings.elements) {
            importedByName.set(
              element.name.text,
              externalStableKey(moduleName, element.propertyName?.text ?? element.name.text)
            );
          }
        }
        if (clause?.namedBindings && ts.isNamespaceImport(clause.namedBindings)) {
          importedByName.set(clause.namedBindings.name.text, externalStableKey(moduleName));
        }
      }

      const kind = symbolKind(node);
      const name = nodeName(node);
      const addsScope = Boolean(kind && name && (kind === 'class' || kind === 'function' || kind === 'method'));
      if (kind && name) {
        const qualifiedName = [...scopeNames, name].join('.');
        const stableKey = declarationStableKey(normalizedPath, kind, qualifiedName);
        const range = lineRange(sourceFile, node);
        const exported = isExported(node) ||
          (ts.isVariableDeclaration(node) && ts.isVariableStatement(node.parent.parent) && isExported(node.parent.parent));
        const symbol: CodeSymbol = {
          stableKey,
          path: normalizedPath,
          kind,
          name,
          qualifiedName,
          ...range,
          signature: callableSignature(node, sourceFile),
          contentHash: hash(node.getText(sourceFile)),
          exported,
          metadata: {
            async: hasModifier(node, ts.SyntaxKind.AsyncKeyword),
            default: hasModifier(node, ts.SyntaxKind.DefaultKeyword)
          }
        };
        symbols.push(symbol);
        nodeToSymbol.set(node, stableKey);
        declarationByName.set(name, stableKey);
        declarationByName.set(qualifiedName, stableKey);
        addEdge({
          fromStableKey: moduleKey,
          toStableKey: stableKey,
          type: 'DECLARES',
          confidence: 1,
          sourcePath: normalizedPath,
          sourceLine: range.startLine
        });
        if (exported) {
          addEdge({
            fromStableKey: moduleKey,
            toStableKey: stableKey,
            type: 'EXPORTS',
            confidence: 1,
            sourcePath: normalizedPath,
            sourceLine: range.startLine
          });
        }
      }

      if (addsScope && name) scopeNames.push(name);
      ts.forEachChild(node, collectDeclarations);
      if (addsScope) scopeNames.pop();
    };
    collectDeclarations(sourceFile);

    const symbolStack: string[] = [moduleKey];
    const testNames = new Set(['describe', 'it', 'test']);
    const testScopeNames: string[] = [];
    const collectCalls = (node: ts.Node): void => {
      const declaredSymbol = nodeToSymbol.get(node);
      if (declaredSymbol) symbolStack.push(declaredSymbol);

      let syntheticTestKey: string | undefined;
      let pushedDescribe = false;
      if (ts.isCallExpression(node) && ts.isIdentifier(node.expression) && testNames.has(node.expression.text)) {
        const first = node.arguments[0];
        if (first && (ts.isStringLiteral(first) || ts.isNoSubstitutionTemplateLiteral(first))) {
          const testName = first.text;
          if (node.expression.text === 'describe') {
            testScopeNames.push(testName);
            pushedDescribe = true;
          } else {
            const range = lineRange(sourceFile, node);
            const qualifiedName = `$test.${[...testScopeNames, testName].join(' > ')}`;
            syntheticTestKey = declarationStableKey(normalizedPath, 'test', qualifiedName);
            symbols.push({
              stableKey: syntheticTestKey,
              path: normalizedPath,
              kind: 'test',
              name: testName,
              qualifiedName,
              ...range,
              signature: `${node.expression.text}(${JSON.stringify(testName)})`,
              contentHash: hash(node.getText(sourceFile)),
              exported: false,
              metadata: { frameworkCall: node.expression.text }
            });
            addEdge({
              fromStableKey: moduleKey,
              toStableKey: syntheticTestKey,
              type: 'DECLARES',
              confidence: 0.95,
              sourcePath: normalizedPath,
              sourceLine: range.startLine
            });
            symbolStack.push(syntheticTestKey);
          }
        }
      }

      if (ts.isCallExpression(node)) {
        const expressionText = node.expression.getText(sourceFile);
        const localName = ts.isIdentifier(node.expression)
          ? node.expression.text
          : ts.isPropertyAccessExpression(node.expression)
            ? node.expression.name.text
            : expressionText;
        const importRoot = expressionText.split('.')[0] ?? expressionText;
        const resolved = declarationByName.get(localName) ?? importedByName.get(importRoot);
        if (!testNames.has(localName)) {
          addEdge({
            fromStableKey: symbolStack.at(-1) ?? moduleKey,
            toStableKey: resolved ?? `unresolved:${localName}`,
            type: 'CALLS',
            confidence: resolved ? (importedByName.has(importRoot) ? 0.75 : 0.9) : 0.4,
            sourcePath: normalizedPath,
            sourceLine: lineRange(sourceFile, node).startLine
          });
        }
      }

      ts.forEachChild(node, collectCalls);
      if (syntheticTestKey) symbolStack.pop();
      if (pushedDescribe) testScopeNames.pop();
      if (declaredSymbol) symbolStack.pop();
    };
    collectCalls(sourceFile);

    const uniqueSymbols = [...new Map(symbols.map((symbol) => [symbol.stableKey, symbol])).values()];
    const uniqueEdges = [
      ...new Map(
        edges.map((edge) => [
          `${edge.fromStableKey}|${edge.toStableKey}|${edge.type}|${edge.sourceLine}`,
          edge
        ])
      ).values()
    ];

    return {
      path: normalizedPath,
      language: language(normalizedPath),
      contentHash: hash(content),
      symbols: uniqueSymbols,
      edges: uniqueEdges,
      parseErrors: syntaxErrorCount(sourceFile)
    };
  }
}

export interface PullRequestIndexInput {
  repositoryId: number;
  installationId: number;
  context: PullRequestContext;
}

export interface PullRequestIndexResult {
  baseSnapshotId: string;
  headSnapshotId: string;
  baseCoverage: SnapshotCoverage;
  headCoverage: SnapshotCoverage;
  baseReused: boolean;
  headReused: boolean;
}

export interface PullRequestIndexOptions {
  maxFiles: number;
  maxFileBytes: number;
}

const REVIEWABLE_EXTENSION = /\.(?:[cm]?[jt]s|[jt]sx)$/i;

export class PullRequestCodeIndexer {
  constructor(
    private readonly github: GitHubGateway,
    private readonly store: CodeIndexStore,
    private readonly fileIndexer = new TypeScriptFileIndexer(),
    private readonly options: PullRequestIndexOptions = { maxFiles: 100, maxFileBytes: 500_000 }
  ) {}

  async #indexSide(
    input: PullRequestIndexInput,
    snapshot: SnapshotRecord,
    side: 'base' | 'head'
  ): Promise<SnapshotCoverage> {
    if (snapshot.status === 'ready' && snapshot.coverage) return snapshot.coverage;
    const coverage: SnapshotCoverage = {
      totalChangedFiles: input.context.files.length,
      indexedFiles: 0,
      skippedFiles: 0,
      absentFiles: 0,
      failedFiles: 0
    };

    try {
      for (const [index, file] of input.context.files.entries()) {
        const absent = side === 'head' ? file.status === 'removed' : file.status === 'added';
        const indexedPath = side === 'base' ? (file.previousPath ?? file.path) : file.path;
        const sideFile: ChangedFile = { ...file, path: indexedPath };
        if (!isSafeRepositoryPath(indexedPath)) {
          coverage.skippedFiles += 1;
          await this.store.recordSkippedFile(snapshot.id, sideFile, 'skipped', 'Unsafe repository path rejected.');
          continue;
        }
        if (absent) {
          coverage.absentFiles += 1;
          await this.store.recordSkippedFile(
            snapshot.id,
            sideFile,
            'absent',
            `File is not present on the ${side} side of the change.`
          );
          continue;
        }
        if (index >= this.options.maxFiles || !REVIEWABLE_EXTENSION.test(indexedPath)) {
          coverage.skippedFiles += 1;
          await this.store.recordSkippedFile(
            snapshot.id,
            sideFile,
            'skipped',
            index >= this.options.maxFiles ? 'Changed-file budget exceeded.' : 'Unsupported file type.'
          );
          continue;
        }

        try {
          const content = await this.github.getFileContent(
            input.installationId,
            input.context.owner,
            input.context.repo,
            indexedPath,
            side === 'head' ? input.context.headSha : input.context.baseSha
          );
          if (Buffer.byteLength(content, 'utf8') > this.options.maxFileBytes) {
            coverage.skippedFiles += 1;
            await this.store.recordSkippedFile(snapshot.id, sideFile, 'skipped', 'File-size budget exceeded.');
            continue;
          }
          await this.store.replaceFile(snapshot.id, this.fileIndexer.index(indexedPath, content));
          coverage.indexedFiles += 1;
        } catch (error) {
          coverage.failedFiles += 1;
          const message = redactSecrets(error instanceof Error ? error.message : String(error));
          await this.store.recordSkippedFile(snapshot.id, sideFile, 'failed', message.slice(0, 1_000));
        }
      }
      await this.store.completeSnapshot(snapshot.id, coverage);
      return coverage;
    } catch (error) {
      const message = redactSecrets(error instanceof Error ? error.message : String(error));
      await this.store.failSnapshot(snapshot.id, message.slice(0, 2_000));
      throw error;
    }
  }

  async index(input: PullRequestIndexInput): Promise<PullRequestIndexResult> {
    const scopeHash = hash(
      JSON.stringify({
        files: input.context.files
          .map((file) => ({ path: file.path, previousPath: file.previousPath, status: file.status }))
          .sort((left, right) => left.path.localeCompare(right.path)),
        maxFiles: this.options.maxFiles,
        maxFileBytes: this.options.maxFileBytes
      })
    );
    const [base, head] = await Promise.all([
      this.store.beginSnapshot({
        repositoryId: input.repositoryId,
        commitSha: input.context.baseSha,
        baseSha: input.context.baseSha,
        parserVersion: CODE_INDEX_VERSION,
        scopeHash
      }),
      this.store.beginSnapshot({
        repositoryId: input.repositoryId,
        commitSha: input.context.headSha,
        baseSha: input.context.baseSha,
        parserVersion: CODE_INDEX_VERSION,
        scopeHash
      })
    ]);
    const [baseCoverage, headCoverage] = await Promise.all([
      this.#indexSide(input, base.snapshot, 'base'),
      this.#indexSide(input, head.snapshot, 'head')
    ]);
    return {
      baseSnapshotId: base.snapshot.id,
      headSnapshotId: head.snapshot.id,
      baseCoverage,
      headCoverage,
      baseReused: !base.created && base.snapshot.status === 'ready',
      headReused: !head.created && head.snapshot.status === 'ready'
    };
  }
}

interface SnapshotRow {
  id: string;
  github_repository_id: string;
  commit_sha: string;
  base_sha: string;
  parser_version: string;
  scope_hash: string;
  status: SnapshotRecord['status'];
  scope: SnapshotRecord['scope'];
  coverage: SnapshotCoverage | null;
}

interface SymbolRow {
  stable_key: string;
  path: string;
  kind: CodeSymbolKind;
  name: string;
  qualified_name: string;
  start_line: number;
  end_line: number;
  signature: string;
  content_hash: string;
  exported: boolean;
  metadata: Record<string, string | number | boolean>;
}

interface EdgeRow {
  from_stable_key: string;
  to_stable_key: string;
  type: CodeEdgeType;
  confidence: string | number;
  source_path: string;
  source_line: number;
}

function mapSnapshot(row: SnapshotRow): SnapshotRecord {
  return {
    id: row.id,
    repositoryId: Number(row.github_repository_id),
    commitSha: row.commit_sha,
    baseSha: row.base_sha,
    parserVersion: row.parser_version,
    scopeHash: row.scope_hash,
    status: row.status,
    scope: row.scope,
    ...(row.coverage ? { coverage: row.coverage } : {})
  };
}

export class PostgresCodeIndexStore implements CodeIndexStore {
  readonly #sql: Sql;

  constructor(databaseUrl: string) {
    this.#sql = postgres(databaseUrl, { max: 5 });
  }

  async beginSnapshot(input: {
    repositoryId: number;
    commitSha: string;
    baseSha: string;
    parserVersion: string;
    scopeHash: string;
  }): Promise<{ snapshot: SnapshotRecord; created: boolean }> {
    const inserted = await this.#sql<SnapshotRow[]>`
      INSERT INTO code_snapshots (
        id, github_repository_id, commit_sha, base_sha, parser_version, scope_hash, status, scope
      ) VALUES (
        ${randomUUID()}, ${input.repositoryId}, ${input.commitSha}, ${input.baseSha},
        ${input.parserVersion}, ${input.scopeHash}, 'building', 'pull_request_delta'
      )
      ON CONFLICT (github_repository_id, commit_sha, parser_version, scope_hash) DO NOTHING
      RETURNING *
    `;
    if (inserted[0]) return { snapshot: mapSnapshot(inserted[0]), created: true };

    const rows = await this.#sql<SnapshotRow[]>`
      SELECT * FROM code_snapshots
      WHERE github_repository_id = ${input.repositoryId}
        AND commit_sha = ${input.commitSha}
        AND parser_version = ${input.parserVersion}
        AND scope_hash = ${input.scopeHash}
      LIMIT 1
    `;
    if (!rows[0]) throw new Error('Snapshot conflict resolved without an existing row.');
    return { snapshot: mapSnapshot(rows[0]), created: false };
  }

  async replaceFile(snapshotId: string, result: FileIndexResult): Promise<void> {
    await this.#sql.begin(async (sql) => {
      await sql`DELETE FROM code_edges WHERE snapshot_id = ${snapshotId} AND source_path = ${result.path}`;
      await sql`DELETE FROM code_symbols WHERE snapshot_id = ${snapshotId} AND path = ${result.path}`;
      await sql`DELETE FROM indexed_files WHERE snapshot_id = ${snapshotId} AND path = ${result.path}`;
      await sql`
        INSERT INTO indexed_files (
          snapshot_id, path, language, content_hash, status, parse_errors
        ) VALUES (
          ${snapshotId}, ${result.path}, ${result.language}, ${result.contentHash}, 'indexed', ${result.parseErrors}
        )
      `;
      for (const symbol of result.symbols) {
        await sql`
          INSERT INTO code_symbols (
            id, snapshot_id, stable_key, path, kind, name, qualified_name,
            start_line, end_line, signature, content_hash, exported, metadata
          ) VALUES (
            ${randomUUID()}, ${snapshotId}, ${symbol.stableKey}, ${symbol.path}, ${symbol.kind},
            ${symbol.name}, ${symbol.qualifiedName}, ${symbol.startLine}, ${symbol.endLine},
            ${symbol.signature}, ${symbol.contentHash}, ${symbol.exported},
            ${sql.json(JSON.parse(JSON.stringify(symbol.metadata)))}
          )
          ON CONFLICT (snapshot_id, stable_key) DO UPDATE
          SET start_line = EXCLUDED.start_line,
              end_line = EXCLUDED.end_line,
              signature = EXCLUDED.signature,
              content_hash = EXCLUDED.content_hash,
              exported = EXCLUDED.exported,
              metadata = EXCLUDED.metadata
        `;
      }
      for (const edge of result.edges) {
        await sql`
          INSERT INTO code_edges (
            id, snapshot_id, from_stable_key, to_stable_key, type,
            confidence, source_path, source_line
          ) VALUES (
            ${randomUUID()}, ${snapshotId}, ${edge.fromStableKey}, ${edge.toStableKey},
            ${edge.type}, ${edge.confidence}, ${edge.sourcePath}, ${edge.sourceLine}
          )
          ON CONFLICT DO NOTHING
        `;
      }
    });
  }

  async recordSkippedFile(
    snapshotId: string,
    file: ChangedFile,
    status: 'skipped' | 'absent' | 'failed',
    reason: string
  ): Promise<void> {
    await this.#sql`
      INSERT INTO indexed_files (snapshot_id, path, language, status, skip_reason, parse_errors)
      VALUES (
        ${snapshotId}, ${normalizePath(file.path)},
        ${REVIEWABLE_EXTENSION.test(file.path) ? language(file.path) : null},
        ${status}, ${reason}, 0
      )
      ON CONFLICT (snapshot_id, path) DO UPDATE
      SET status = EXCLUDED.status, skip_reason = EXCLUDED.skip_reason
    `;
  }

  async loadSnapshotGraph(snapshotId: string): Promise<CodeGraphSnapshot> {
    const [snapshotRows, symbolRows, edgeRows] = await Promise.all([
      this.#sql<SnapshotRow[]>`SELECT * FROM code_snapshots WHERE id = ${snapshotId} LIMIT 1`,
      this.#sql<SymbolRow[]>`
        SELECT stable_key, path, kind, name, qualified_name, start_line, end_line,
               signature, content_hash, exported, metadata
        FROM code_symbols WHERE snapshot_id = ${snapshotId}
      `,
      this.#sql<EdgeRow[]>`
        SELECT from_stable_key, to_stable_key, type, confidence, source_path, source_line
        FROM code_edges WHERE snapshot_id = ${snapshotId}
      `
    ]);
    if (!snapshotRows[0]) throw new Error(`Unknown code snapshot ${snapshotId}`);
    return {
      snapshot: mapSnapshot(snapshotRows[0]),
      symbols: symbolRows.map((row) => ({
        stableKey: row.stable_key,
        path: row.path,
        kind: row.kind,
        name: row.name,
        qualifiedName: row.qualified_name,
        startLine: row.start_line,
        endLine: row.end_line,
        signature: row.signature,
        contentHash: row.content_hash,
        exported: row.exported,
        metadata: row.metadata
      })),
      edges: edgeRows.map((row) => ({
        fromStableKey: row.from_stable_key,
        toStableKey: row.to_stable_key,
        type: row.type,
        confidence: Number(row.confidence),
        sourcePath: row.source_path,
        sourceLine: row.source_line
      }))
    };
  }

  async completeSnapshot(snapshotId: string, coverage: SnapshotCoverage): Promise<void> {
    await this.#sql`
      UPDATE code_snapshots
      SET status = 'ready', coverage = ${this.#sql.json(JSON.parse(JSON.stringify(coverage)))},
          completed_at = now(), updated_at = now(), error_detail = NULL
      WHERE id = ${snapshotId}
    `;
  }

  async failSnapshot(snapshotId: string, error: string): Promise<void> {
    await this.#sql`
      UPDATE code_snapshots SET status = 'failed', error_detail = ${error}, updated_at = now()
      WHERE id = ${snapshotId}
    `;
  }

  async close(): Promise<void> {
    await this.#sql.end();
  }
}

export class InMemoryCodeIndexStore implements CodeIndexStore {
  readonly snapshots = new Map<string, SnapshotRecord & { coverage?: SnapshotCoverage; error?: string }>();
  readonly files = new Map<string, Map<string, FileIndexResult | { status: string; reason: string }>>();

  async beginSnapshot(input: {
    repositoryId: number;
    commitSha: string;
    baseSha: string;
    parserVersion: string;
    scopeHash: string;
  }): Promise<{ snapshot: SnapshotRecord; created: boolean }> {
    const existing = [...this.snapshots.values()].find(
      (snapshot) =>
        snapshot.repositoryId === input.repositoryId &&
        snapshot.commitSha === input.commitSha &&
        snapshot.parserVersion === input.parserVersion &&
        snapshot.scopeHash === input.scopeHash
    );
    if (existing) return { snapshot: existing, created: false };
    const snapshot: SnapshotRecord = {
      id: randomUUID(),
      ...input,
      status: 'building',
      scope: 'pull_request_delta'
    };
    this.snapshots.set(snapshot.id, snapshot);
    this.files.set(snapshot.id, new Map());
    return { snapshot, created: true };
  }

  async replaceFile(snapshotId: string, result: FileIndexResult): Promise<void> {
    this.files.get(snapshotId)?.set(result.path, result);
  }

  async recordSkippedFile(
    snapshotId: string,
    file: ChangedFile,
    status: 'skipped' | 'absent' | 'failed',
    reason: string
  ): Promise<void> {
    this.files.get(snapshotId)?.set(file.path, { status, reason });
  }

  async loadSnapshotGraph(snapshotId: string): Promise<CodeGraphSnapshot> {
    const snapshot = this.snapshots.get(snapshotId);
    if (!snapshot) throw new Error(`Unknown code snapshot ${snapshotId}`);
    const indexed = [...(this.files.get(snapshotId)?.values() ?? [])].filter(
      (file): file is FileIndexResult => 'symbols' in file
    );
    return {
      snapshot,
      symbols: indexed.flatMap((file) => file.symbols),
      edges: indexed.flatMap((file) => file.edges)
    };
  }

  async completeSnapshot(snapshotId: string, coverage: SnapshotCoverage): Promise<void> {
    const snapshot = this.snapshots.get(snapshotId);
    if (!snapshot) throw new Error(`Unknown snapshot ${snapshotId}`);
    this.snapshots.set(snapshotId, { ...snapshot, status: 'ready', coverage });
  }

  async failSnapshot(snapshotId: string, error: string): Promise<void> {
    const snapshot = this.snapshots.get(snapshotId);
    if (!snapshot) throw new Error(`Unknown snapshot ${snapshotId}`);
    this.snapshots.set(snapshotId, { ...snapshot, status: 'failed', error });
  }

  async close(): Promise<void> {}
}
