import { lstat, mkdir, readFile, readdir, rename, stat, writeFile } from 'node:fs/promises';
import path from 'node:path';
import Fastify from 'fastify';
import { z } from 'zod';
import {
  buildNeutralChangeBrief,
  computeReviewContextBundleDigest,
  ReplayCaseSchema,
  ReviewContextBundleSchema,
  ReviewWorkbenchStoreSchema,
  RootCauseLabelSchema,
  type LegacyFindingDraft,
  type ReplayCase,
  type ReviewContextBundle,
  type ReviewWorkbenchDecision,
  type ReviewWorkbenchStore,
  type RootCauseLabel
} from '@codelens/evaluation';
import { DeterministicRiskReviewer, DiffMap, EvidenceVerifier } from '@codelens/risk-review';
import { buildSolutionOptions, retrieveReuseCandidates, type ReuseDecision } from '@codelens/solution-planning';

interface LegacyExpectedFinding {
  ruleId: string;
  path: string;
  line: number;
  category?: string | undefined;
  severity?: 'critical' | 'high' | 'medium' | 'low' | undefined;
  title?: string | undefined;
  notes?: string | undefined;
}

interface LegacyStoredDecision {
  status: 'approved' | 'deferred';
  expectedFindings: LegacyExpectedFinding[];
  reviewer: string;
  notes?: string;
  updatedAt: string;
  protocol: 'blind-v1';
}

interface LegacyDecisionStore {
  version: 2;
  protocol: 'blind-v1';
  updatedAt: string;
  decisions: Record<string, LegacyStoredDecision>;
}

const args = process.argv.slice(2);
const readArg = (name: string, fallback: string): string =>
  args.find((arg) => arg.startsWith(`${name}=`))?.slice(name.length + 1) ?? fallback;
const root = process.cwd();
const inputPath = path.resolve(root, readArg('--input', 'benchmarks/candidates/public-prs.jsonl'));
const decisionsPath = path.resolve(root, readArg('--decisions', 'benchmarks/candidates/review-reasoning-decisions.json'));
const legacyDecisionsPath = path.resolve(root, readArg('--legacy-decisions', 'benchmarks/candidates/blind-review-decisions.json'));
const outputPath = path.resolve(root, readArg('--output', 'benchmarks/candidates/review-reasoning-frozen.jsonl'));
const contextRoot = path.resolve(root, readArg('--context-dir', 'benchmarks/candidates/review-context'));
const staticRoot = path.resolve(root, 'apps/benchmark-labeler');
const host = '127.0.0.1';
const port = Number(process.env.BENCHMARK_LABELER_PORT ?? '4310');
const requestedMode = readArg('--mode', 'gold');

if (!Number.isInteger(port) || port < 1 || port > 65_535) {
  throw new Error('BENCHMARK_LABELER_PORT must be a valid TCP port.');
}
if (requestedMode !== 'gold' && requestedMode !== 'assisted') throw new Error('--mode must be gold or assisted.');
const mode: 'gold' | 'assisted' = requestedMode;

const LegacyExpectedFindingInputSchema = z.object({
  ruleId: z.string().trim().min(1).max(120),
  path: z.string().trim().min(1),
  line: z.number().int().positive(),
  category: z.enum(['correctness', 'security', 'data_integrity', 'concurrency', 'performance', 'architecture', 'test_gap']).optional(),
  severity: z.enum(['critical', 'high', 'medium', 'low']).optional(),
  title: z.string().trim().min(1).max(160).optional(),
  notes: z.string().trim().min(1).max(2_000).optional()
});

const DecisionInputSchema = z.object({
  status: z.enum(['frozen', 'deferred']),
  reviewer: z.string().trim().min(2).max(80),
  rootCauses: z.array(RootCauseLabelSchema).max(100),
  notes: z.string().trim().min(1).max(2_000).optional()
});

const ExportInputSchema = z.object({ reviewer: z.string().trim().min(2).max(80) });

async function readCases(): Promise<ReplayCase[]> {
  const source = await readFile(inputPath, 'utf8');
  const cases = source.split(/\r?\n/).filter((line) => line.trim()).map((line, index) => {
    try {
      return ReplayCaseSchema.parse(JSON.parse(line)) as ReplayCase;
    } catch (error) {
      throw new Error(`Invalid benchmark JSONL at line ${index + 1}: ${error instanceof Error ? error.message : String(error)}`);
    }
  });
  const ids = new Set<string>();
  for (const item of cases) {
    if (ids.has(item.id)) throw new Error(`Duplicate benchmark case ID: ${item.id}`);
    ids.add(item.id);
  }
  return cases;
}

async function readDecisions(): Promise<ReviewWorkbenchStore> {
  try {
    const source = JSON.parse(await readFile(decisionsPath, 'utf8')) as unknown;
    const parsed = ReviewWorkbenchStoreSchema.parse(source);
    if (parsed.mode !== mode) throw new Error(`Decision store mode ${parsed.mode} does not match requested mode ${mode}.`);
    return parsed;
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code === 'ENOENT') {
      return { version: 3, protocol: 'reasoning-v1', mode, updatedAt: new Date(0).toISOString(), decisions: {} };
    }
    throw error;
  }
}

async function readLegacyDecisions(): Promise<LegacyDecisionStore> {
  try {
    const source = JSON.parse(await readFile(legacyDecisionsPath, 'utf8')) as unknown;
    return z.object({
      version: z.literal(2),
      protocol: z.literal('blind-v1'),
      updatedAt: z.string().datetime({ offset: true }),
      decisions: z.record(z.string(), z.object({
        status: z.enum(['approved', 'deferred']),
        reviewer: z.string().trim().min(2).max(80),
        expectedFindings: z.array(LegacyExpectedFindingInputSchema).max(100),
        notes: z.string().trim().min(1).max(2_000).optional(),
        protocol: z.literal('blind-v1'),
        updatedAt: z.string().datetime({ offset: true })
      }))
    }).parse(source) as LegacyDecisionStore;
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code === 'ENOENT') {
      return { version: 2, protocol: 'blind-v1', updatedAt: new Date(0).toISOString(), decisions: {} };
    }
    throw error;
  }
}

async function readContextBundles(): Promise<Map<string, ReviewContextBundle>> {
  const bundles = new Map<string, ReviewContextBundle>();
  let entries: string[];
  try {
    entries = await readdir(contextRoot);
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code === 'ENOENT') return bundles;
    throw error;
  }
  for (const name of entries.filter((entry) => entry.endsWith('.json')).sort()) {
    const filePath = path.resolve(contextRoot, name);
    if (!filePath.startsWith(`${contextRoot}${path.sep}`)) throw new Error(`Unsafe context path: ${name}`);
    const linkInfo = await lstat(filePath);
    if (linkInfo.isSymbolicLink() || !linkInfo.isFile() || linkInfo.size > 25 * 1024 * 1024) {
      throw new Error(`Context bundle is not a bounded regular file: ${name}`);
    }
    const bundle = ReviewContextBundleSchema.parse(JSON.parse(await readFile(filePath, 'utf8')));
    const { digest, ...unsigned } = bundle;
    if (computeReviewContextBundleDigest(unsigned) !== digest) throw new Error(`Context bundle digest mismatch: ${name}`);
    if (bundles.has(bundle.caseId)) throw new Error(`Duplicate context bundle for case ${bundle.caseId}.`);
    bundles.set(bundle.caseId, bundle);
  }
  return bundles;
}

async function atomicWrite(filePath: string, contents: string): Promise<void> {
  await mkdir(path.dirname(filePath), { recursive: true });
  const temporaryPath = `${filePath}.${process.pid}.tmp`;
  await writeFile(temporaryPath, contents, 'utf8');
  await rename(temporaryPath, filePath);
}

function rootCauseKey(value: RootCauseLabel): string {
  return value.rootCauseId;
}

function diffLines(patch: string): { left: Set<number>; right: Set<number> } {
  const left = new Set<number>();
  const right = new Set<number>();
  let previousLine = 0;
  let nextLine = 0;
  for (const content of patch.split(/\r?\n/)) {
    const hunk = content.match(/^@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@/);
    if (hunk) {
      previousLine = Number(hunk[1]);
      nextLine = Number(hunk[2]);
      continue;
    }
    if (content.startsWith('+') && !content.startsWith('+++')) {
      right.add(nextLine++);
    } else if (content.startsWith('-') && !content.startsWith('---')) {
      left.add(previousLine++);
    } else if (!content.startsWith('+++') && !content.startsWith('---')) {
      if (previousLine) previousLine += 1;
      if (nextLine) nextLine += 1;
    }
  }
  return { left, right };
}

function legacyDrafts(item: ReplayCase, store: LegacyDecisionStore): LegacyFindingDraft[] {
  return (store.decisions[item.id]?.expectedFindings ?? []).map((finding, index) => ({
    sourceProtocol: 'blind-v1',
    rootCauseId: `legacy-${index + 1}-${finding.ruleId.replace(/[^A-Za-z0-9_-]/g, '-')}`.slice(0, 160),
    category: finding.category ?? finding.ruleId.split('/')[0] ?? 'correctness',
    severity: finding.severity ?? 'medium',
    claim: finding.title ?? finding.notes ?? finding.ruleId,
    evidence: [{
      kind: 'diff',
      path: finding.path,
      revision: 'head',
      startLine: finding.line,
      endLine: finding.line,
      side: 'RIGHT',
      fact: finding.notes ?? 'Legacy blind-v1 line anchor; evidence must be re-verified before freezing.'
    }],
    incompleteFields: ['trigger', 'impact', 'verification', 'confidence']
  }));
}

function validateRootCauses(item: ReplayCase, bundle: ReviewContextBundle | undefined, labels: RootCauseLabel[]): string | undefined {
  const changedFiles = new Map(item.context.files.map((file) => [file.path, file]));
  const bundledFiles = new Map((bundle?.files ?? []).map((file) => [`${file.revision}:${file.path}`, file]));
  const seen = new Set<string>();
  for (const label of labels) {
    const key = rootCauseKey(label);
    if (seen.has(key)) return `Duplicate root cause id: ${key}`;
    seen.add(key);
    for (const evidence of label.evidence) {
      if (evidence.kind === 'diff') {
        const file = changedFiles.get(evidence.path);
        if (!file) return `Diff evidence path is not part of this pull request: ${evidence.path}`;
        const lines = diffLines(file.patch);
        const available = evidence.side === 'LEFT' ? lines.left : lines.right;
        for (let line = evidence.startLine; line <= evidence.endLine; line += 1) {
          if (!available.has(line)) return `Diff evidence line is not present on ${evidence.side} side: ${evidence.path}:${line}`;
        }
        continue;
      }
      if (!bundle) return `Context evidence requires a frozen context bundle: ${evidence.path}`;
      const revisions = evidence.revision === 'both' ? ['base', 'head'] as const : [evidence.revision];
      const files = revisions.map((revision) => bundledFiles.get(`${revision}:${evidence.path}`)).filter((file) => file !== undefined);
      if (files.length !== revisions.length) return `Context evidence file is not in the frozen packet for every requested revision: ${evidence.revision}:${evidence.path}`;
      if (files.some((file) => evidence.endLine > file.content.split(/\r?\n/).length)) {
        return `Context evidence line exceeds bundled file length: ${evidence.path}:${evidence.endLine}`;
      }
      if ((evidence.kind === 'symbol' || evidence.kind === 'caller') && evidence.symbol) {
        const matched = bundle.symbols.some((symbol) =>
          (symbol.id === evidence.symbol || symbol.name === evidence.symbol) && symbol.path === evidence.path &&
          (evidence.revision === 'both' || symbol.revision === evidence.revision)
        );
        if (!matched) return `Evidence symbol is not in the frozen packet: ${evidence.symbol}`;
      }
    }
  }
  return undefined;
}

const cases = await readCases();
const caseById = new Map(cases.map((item) => [item.id, item]));
const contextBundles = await readContextBundles();
for (const bundle of contextBundles.values()) {
  const item = caseById.get(bundle.caseId);
  if (!item) throw new Error(`Context bundle references an unknown case: ${bundle.caseId}`);
  if (bundle.baseSha !== item.context.baseSha || bundle.headSha !== item.context.headSha) {
    throw new Error(`Context bundle SHA mismatch for case ${bundle.caseId}.`);
  }
}
const suggestions = new Map<string, Awaited<ReturnType<DeterministicRiskReviewer['review']>>>();

if (mode === 'assisted') {
  const reviewer = new DeterministicRiskReviewer();
  const verifier = new EvidenceVerifier({ maxPublished: 100 });
  for (const item of cases) {
    const diff = new DiffMap(item.context.files);
    const verified = verifier.verify(await reviewer.review(item.context, diff), diff)
      .filter((finding) => finding.status === 'verified');
    suggestions.set(item.id, verified);
  }
}

function relative(value: string): string {
  return path.relative(root, value).replaceAll('\\', '/');
}

function contentType(filePath: string): string {
  const extension = path.extname(filePath).toLowerCase();
  return ({
    '.html': 'text/html; charset=utf-8',
    '.js': 'text/javascript; charset=utf-8',
    '.css': 'text/css; charset=utf-8',
    '.svg': 'image/svg+xml',
    '.png': 'image/png',
    '.ico': 'image/x-icon'
  } as Record<string, string>)[extension] ?? 'application/octet-stream';
}

const app = Fastify({ logger: false, bodyLimit: 64 * 1024 });
const allowedHosts = new Set([`${host}:${port}`, `localhost:${port}`]);
const allowedOrigins = new Set([...allowedHosts].map((value) => `http://${value}`));

app.addHook('onRequest', async (request, reply) => {
  const requestHost = request.headers.host;
  const origin = request.headers.origin;
  if (!requestHost || !allowedHosts.has(requestHost)) {
    return reply.code(403).send({ error: 'This workbench only accepts local requests.' });
  }
  if (origin && !allowedOrigins.has(origin)) {
    return reply.code(403).send({ error: 'Cross-origin requests are not allowed.' });
  }
});

app.addHook('onSend', async (_request, reply, payload) => {
  reply.header('content-security-policy', "default-src 'self'; style-src 'self' 'unsafe-inline'; connect-src 'self'; img-src 'self' data:; object-src 'none'; base-uri 'none'; frame-ancestors 'none'");
  reply.header('x-content-type-options', 'nosniff');
  reply.header('referrer-policy', 'no-referrer');
  return payload;
});

function decisionForApi(decision: ReviewWorkbenchDecision): Omit<ReviewWorkbenchDecision, 'predictionVisible'> | ReviewWorkbenchDecision {
  if (mode === 'assisted') return decision;
  const { predictionVisible: _predictionVisible, ...neutralDecision } = decision;
  return neutralDecision;
}

app.get('/api/state', async () => {
  const [store, legacyStore] = await Promise.all([readDecisions(), readLegacyDecisions()]);
  const frozen = Object.values(store.decisions).filter((item) => item.status === 'frozen').length;
  const deferred = Object.values(store.decisions).filter((item) => item.status === 'deferred').length;
  return {
    protocol: 'reasoning-v1',
    mode,
    generatedAt: new Date().toISOString(),
    inputPath: relative(inputPath),
    outputPath: relative(outputPath),
    contextRoot: relative(contextRoot),
    summary: {
      total: cases.length,
      frozen,
      deferred,
      pending: cases.length - frozen - deferred,
      contextReady: cases.filter((item) => contextBundles.has(item.id)).length
    },
    cases: cases.map((item) => {
      if (item.provenance.kind !== 'historical_pr') {
        throw new Error(`Labeler only accepts historical PR cases: ${item.id}`);
      }
      const decision = store.decisions[item.id];
      const bundle = contextBundles.get(item.id);
      const changeBrief = buildNeutralChangeBrief(item, bundle);
      const result: Record<string, unknown> = {
        id: item.id,
        ...item.context,
        sourceUrl: item.provenance.sourceUrl,
        repositoryLicense: item.provenance.repositoryLicense,
        collectedAt: item.provenance.collectedAt,
        changeBrief,
        contextPacket: bundle ? {
          available: true,
          packetId: bundle.packetId,
          digest: bundle.digest,
          generatedAt: bundle.generatedAt,
          files: bundle.files,
          symbols: bundle.symbols,
          relationships: bundle.relationships,
          limitations: bundle.limitations
        } : {
          available: false,
          limitations: ['Frozen repository context is unavailable. Gold decisions cannot be frozen.']
        },
        legacyDrafts: decision ? [] : legacyDrafts(item, legacyStore),
        ...(decision ? { decision: decisionForApi(decision) } : {})
      };
      if (mode === 'assisted') {
        result.assistance = suggestions.get(item.id) ?? [];
        result.reuseInvestigations = retrieveReuseCandidates(changeBrief, bundle);
      }
      return result;
    })
  };
});

app.put<{ Params: { id: string } }>('/api/decisions/:id', async (request, reply) => {
  const item = caseById.get(request.params.id);
  if (!item) return reply.code(404).send({ error: 'Benchmark case was not found.' });
  const parsed = DecisionInputSchema.safeParse(request.body);
  if (!parsed.success) return reply.code(400).send({ error: parsed.error.issues[0]?.message ?? 'Invalid decision.' });
  if (parsed.data.status === 'deferred' && parsed.data.rootCauses.length) {
    return reply.code(400).send({ error: 'Deferred cases cannot contain frozen root causes.' });
  }

  const store = await readDecisions();
  if (store.decisions[item.id]?.status === 'frozen') {
    return reply.code(409).send({ error: 'Root-cause decisions are immutable after freezing.' });
  }
  const bundle = contextBundles.get(item.id);
  if (mode === 'gold' && parsed.data.status === 'frozen' && !bundle) {
    return reply.code(409).send({ error: 'Gold decisions require a frozen neutral context bundle.' });
  }
  const validationError = validateRootCauses(item, bundle, parsed.data.rootCauses);
  if (validationError) return reply.code(400).send({ error: validationError });
  const updatedAt = new Date().toISOString();
  const decision: ReviewWorkbenchDecision = {
    status: parsed.data.status,
    mode,
    reviewer: parsed.data.reviewer,
    contextPacketId: bundle?.packetId ?? `diff-only:${item.context.baseSha}:${item.context.headSha}`,
    predictionVisible: mode === 'assisted',
    rootCauses: parsed.data.rootCauses,
    ...(parsed.data.notes ? { notes: parsed.data.notes } : {}),
    updatedAt,
    protocol: 'reasoning-v1'
  };
  store.decisions[item.id] = decision;
  store.updatedAt = updatedAt;
  await atomicWrite(decisionsPath, `${JSON.stringify(store, null, 2)}\n`);
  return { saved: true, id: item.id, decision: decisionForApi(store.decisions[item.id]!) };
});

app.post('/api/reuse/options', async (request, reply) => {
  if (mode !== 'assisted') return reply.code(403).send({ error: 'Solution options are not available in formal gold mode.' });
  try {
    return { options: buildSolutionOptions(request.body as ReuseDecision) };
  } catch {
    return reply.code(400).send({ error: 'ReuseDecision did not satisfy the evidence, candidate, and change-budget contract.' });
  }
});

app.post('/api/export', async (request, reply) => {
  const parsed = ExportInputSchema.safeParse(request.body);
  if (!parsed.success) return reply.code(400).send({ error: parsed.error.issues[0]?.message ?? 'Invalid reviewer.' });
  const store = await readDecisions();
  const missing = cases.filter((item) => store.decisions[item.id]?.status !== 'frozen');
  if (missing.length) {
    return reply.code(409).send({ error: `${missing.length} cases still require a frozen root-cause decision.` });
  }
  const exported = cases.map((item) => {
    const decision = store.decisions[item.id]!;
    return {
      caseId: item.id,
      repository: `${item.context.owner}/${item.context.repo}`,
      pullNumber: item.context.number,
      baseSha: item.context.baseSha,
      headSha: item.context.headSha,
      mode: decision.mode,
      reviewerId: decision.reviewer || parsed.data.reviewer,
      submittedAt: decision.updatedAt,
      contextPacketId: decision.contextPacketId,
      predictionVisible: decision.predictionVisible,
      protocol: decision.protocol,
      rootCauses: decision.rootCauses,
      ...(decision.notes ? { notes: decision.notes } : {})
    };
  });
  await atomicWrite(outputPath, `${exported.map((item) => JSON.stringify(item)).join('\n')}\n`);
  return { exported: exported.length, outputPath: relative(outputPath) };
});

app.get('/*', async (request, reply) => {
  const requested = request.url === '/' ? 'index.html' : (request.url.slice(1).split('?')[0] ?? 'index.html');
  const candidate = path.resolve(staticRoot, requested);
  if (candidate !== staticRoot && !candidate.startsWith(`${staticRoot}${path.sep}`)) {
    return reply.code(400).send('Invalid path');
  }
  let filePath = candidate;
  try {
    if (!(await stat(filePath)).isFile()) filePath = path.join(staticRoot, 'index.html');
  } catch {
    filePath = path.join(staticRoot, 'index.html');
  }
  return reply.type(contentType(filePath)).send(await readFile(filePath));
});

await app.listen({ host, port });
console.log(JSON.stringify({
  status: 'ready',
  protocol: 'reasoning-v1',
  mode,
  url: `http://${host}:${port}`,
  cases: cases.length,
  input: relative(inputPath),
  decisions: relative(decisionsPath),
  legacyDecisions: relative(legacyDecisionsPath),
  contextRoot: relative(contextRoot),
  output: relative(outputPath)
}, null, 2));

const shutdown = async () => {
  await app.close();
  process.exit(0);
};
process.on('SIGINT', shutdown);
process.on('SIGTERM', shutdown);
