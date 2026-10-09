import { readFile, readdir, writeFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

interface SymbolFact { stableKey: string; path: string }
interface EdgeFact { fromStableKey: string; toStableKey: string; type: string; typeResolved: boolean; sourcePath: string; sourceLine: number }
interface Snapshot { symbols: SymbolFact[]; relationships: EdgeFact[] }

/** Structural diagnostics only: a resolved flag is not an independent correctness label. */
export function auditFacts(index: Snapshot, changedPaths: string[]) {
  const symbols = new Map(index.symbols.map(symbol => [symbol.stableKey, symbol]));
  const changed = new Set(changedPaths);
  const internalCalls = index.relationships.filter(edge => edge.type === 'CALLS' && edge.typeResolved
    && symbols.has(edge.fromStableKey) && symbols.has(edge.toStableKey));
  const incoming = internalCalls.filter(edge => changed.has(symbols.get(edge.toStableKey)!.path)
    && !changed.has(symbols.get(edge.fromStableKey)!.path));
  const annotations = index.relationships.filter(edge => edge.type === 'ANNOTATED_WITH');
  return {
    scope: 'Archived full snapshot structural counts; not independently verified truth or retrieval precision',
    internalResolvedCalls: internalCalls.length,
    unchangedCallerEdgesIntoChangedFiles: incoming.length,
    unchangedCallerFiles: [...new Set(incoming.map(edge => symbols.get(edge.fromStableKey)!.path))].sort(),
    incomingExamples: incoming.slice(0, 6),
    annotationEdges: annotations.length,
    annotationSourceOwnerMismatches: annotations.filter(edge => symbols.has(edge.fromStableKey)
      && symbols.get(edge.fromStableKey)!.path !== edge.sourcePath).length,
  };
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const directory = path.resolve(process.argv[2] ?? '');
  if (!process.argv[2]) throw new Error('Specify existing evidence directory');
  const cache = path.join(directory, 'cache-semantic-codelens.json');
  const snapshots = (await readdir(cache)).filter(file => file.endsWith('.json'));
  if (snapshots.length !== 1) throw new Error('Expected exactly one archived full snapshot');
  const snapshotPath = path.join(cache, snapshots[0]!);
  const bytes = await readFile(snapshotPath);
  const report = JSON.parse(await readFile(path.join(directory, 'semantic-codelens.json'), 'utf8')) as { changedJavaPaths: string[]; headSha: string };
  const result = { untimedAudit: true, headSha: report.headSha, snapshotPath,
    snapshotSha256: createHash('sha256').update(bytes).digest('hex'),
    ...auditFacts(JSON.parse(bytes.toString('utf8')) as Snapshot, report.changedJavaPaths) };
  await writeFile(path.join(directory, 'semantic-fact-audit.json'), JSON.stringify(result, null, 2), { flag: 'wx' });
  console.log(JSON.stringify(result, null, 2));
}
