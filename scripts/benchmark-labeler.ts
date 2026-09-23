import { mkdir, readFile, rename, stat, writeFile } from 'node:fs/promises';
import path from 'node:path';
import Fastify from 'fastify';
import { z } from 'zod';
import { ReplayCaseSchema, type ReplayCase } from '@codelens/evaluation';
import { DeterministicRiskReviewer, DiffMap, EvidenceVerifier } from '@codelens/risk-review';

interface ExpectedFinding {
  ruleId: string;
  path: string;
  line: number;
  category?: string | undefined;
  severity?: 'critical' | 'high' | 'medium' | 'low' | undefined;
  title?: string | undefined;
  notes?: string | undefined;
}

interface StoredDecision {
  status: 'approved' | 'deferred';
  expectedFindings: ExpectedFinding[];
  reviewer: string;
  notes?: string;
  updatedAt: string;
  protocol: 'blind-v1';
}

interface DecisionStore {
  version: 2;
  protocol: 'blind-v1';
  updatedAt: string;
  decisions: Record<string, StoredDecision>;
}

const args = process.argv.slice(2);
const readArg = (name: string, fallback: string): string =>
  args.find((arg) => arg.startsWith(`${name}=`))?.slice(name.length + 1) ?? fallback;
const root = process.cwd();
const inputPath = path.resolve(root, readArg('--input', 'benchmarks/candidates/public-prs.jsonl'));
const decisionsPath = path.resolve(root, readArg('--decisions', 'benchmarks/candidates/blind-review-decisions.json'));
const outputPath = path.resolve(root, readArg('--output', 'benchmarks/candidates/blind-approved-replay.jsonl'));
const staticRoot = path.resolve(root, 'apps/benchmark-labeler');
const host = '127.0.0.1';
const port = Number(process.env.BENCHMARK_LABELER_PORT ?? '4310');

if (!Number.isInteger(port) || port < 1 || port > 65_535) {
  throw new Error('BENCHMARK_LABELER_PORT must be a valid TCP port.');
}

const ExpectedFindingInputSchema = z.object({
  ruleId: z.string().trim().min(1).max(120),
  path: z.string().trim().min(1),
  line: z.number().int().positive(),
  category: z.enum(['correctness', 'security', 'data_integrity', 'concurrency', 'performance', 'architecture', 'test_gap']).optional(),
  severity: z.enum(['critical', 'high', 'medium', 'low']).optional(),
  title: z.string().trim().min(1).max(160).optional(),
  notes: z.string().trim().min(1).max(2_000).optional()
});

const DecisionInputSchema = z.object({
  status: z.enum(['approved', 'deferred']),
  reviewer: z.string().trim().min(2).max(80),
  expectedFindings: z.array(ExpectedFindingInputSchema).max(100),
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

async function readDecisions(): Promise<DecisionStore> {
  try {
    const source = JSON.parse(await readFile(decisionsPath, 'utf8')) as unknown;
    return z.object({
      version: z.literal(2),
      protocol: z.literal('blind-v1'),
      updatedAt: z.string().datetime({ offset: true }),
      decisions: z.record(z.string(), DecisionInputSchema.extend({
        protocol: z.literal('blind-v1'),
        updatedAt: z.string().datetime({ offset: true })
      }))
    }).parse(source) as DecisionStore;
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code === 'ENOENT') {
      return { version: 2, protocol: 'blind-v1', updatedAt: new Date(0).toISOString(), decisions: {} };
    }
    throw error;
  }
}

async function atomicWrite(filePath: string, contents: string): Promise<void> {
  await mkdir(path.dirname(filePath), { recursive: true });
  const temporaryPath = `${filePath}.${process.pid}.tmp`;
  await writeFile(temporaryPath, contents, 'utf8');
  await rename(temporaryPath, filePath);
}

function findingKey(value: ExpectedFinding): string {
  return `${value.ruleId}|${value.path}|${value.line}`;
}

function addedLines(patch: string): Set<number> {
  const result = new Set<number>();
  let nextLine = 0;
  for (const content of patch.split(/\r?\n/)) {
    const hunk = content.match(/^@@ -\d+(?:,\d+)? \+(\d+)(?:,\d+)? @@/);
    if (hunk) {
      nextLine = Number(hunk[1]);
      continue;
    }
    if (content.startsWith('+') && !content.startsWith('+++')) {
      result.add(nextLine++);
    } else if (!content.startsWith('-') || content.startsWith('---')) {
      if (nextLine) nextLine += 1;
    }
  }
  return result;
}

const cases = await readCases();
const caseById = new Map(cases.map((item) => [item.id, item]));
const reviewer = new DeterministicRiskReviewer();
const verifier = new EvidenceVerifier({ maxPublished: 100 });
const suggestions = new Map<string, Awaited<ReturnType<typeof reviewer.review>>>();

for (const item of cases) {
  const diff = new DiffMap(item.context.files);
  const verified = verifier.verify(await reviewer.review(item.context, diff), diff)
    .filter((finding) => finding.status === 'verified');
  suggestions.set(item.id, verified);
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

app.get('/api/state', async () => {
  const store = await readDecisions();
  const approved = Object.values(store.decisions).filter((item) => item.status === 'approved').length;
  const deferred = Object.values(store.decisions).filter((item) => item.status === 'deferred').length;
  return {
    protocol: 'blind-v1',
    generatedAt: new Date().toISOString(),
    inputPath: relative(inputPath),
    outputPath: relative(outputPath),
    summary: {
      total: cases.length,
      approved,
      deferred,
      pending: cases.length - approved - deferred
    },
    cases: cases.map((item) => {
      if (item.provenance.kind !== 'historical_pr') {
        throw new Error(`Labeler only accepts historical PR cases: ${item.id}`);
      }
      const decision = store.decisions[item.id];
      const machinePredictionRevealed = decision?.status === 'approved';
      return {
        id: item.id,
        ...item.context,
        sourceUrl: item.provenance.sourceUrl,
        repositoryLicense: item.provenance.repositoryLicense,
        collectedAt: item.provenance.collectedAt,
        machinePredictionRevealed,
        machineSuggestions: machinePredictionRevealed ? suggestions.get(item.id) ?? [] : [],
        ...(decision ? { decision } : {})
      };
    })
  };
});

app.put<{ Params: { id: string } }>('/api/decisions/:id', async (request, reply) => {
  const item = caseById.get(request.params.id);
  if (!item) return reply.code(404).send({ error: 'Benchmark case was not found.' });
  const parsed = DecisionInputSchema.safeParse(request.body);
  if (!parsed.success) return reply.code(400).send({ error: parsed.error.issues[0]?.message ?? 'Invalid decision.' });
  if (parsed.data.status === 'deferred' && parsed.data.expectedFindings.length) {
    return reply.code(400).send({ error: 'Deferred cases cannot contain approved findings.' });
  }

  const store = await readDecisions();
  if (store.decisions[item.id]?.status === 'approved') {
    return reply.code(409).send({ error: 'Blind decisions are frozen after machine predictions are revealed.' });
  }
  const files = new Map(item.context.files.map((file) => [file.path, file]));
  const seen = new Set<string>();
  for (const finding of parsed.data.expectedFindings) {
    const file = files.get(finding.path);
    if (!file) return reply.code(400).send({ error: `Finding path is not part of this pull request: ${finding.path}` });
    if (!addedLines(file.patch).has(finding.line)) {
      return reply.code(400).send({ error: `Finding line must reference an added right-side diff line: ${finding.path}:${finding.line}` });
    }
    const key = findingKey(finding);
    if (seen.has(key)) return reply.code(400).send({ error: `Duplicate finding: ${key}` });
    seen.add(key);
  }
  const updatedAt = new Date().toISOString();
  store.decisions[item.id] = {
    status: parsed.data.status,
    reviewer: parsed.data.reviewer,
    expectedFindings: parsed.data.expectedFindings,
    ...(parsed.data.notes ? { notes: parsed.data.notes } : {}),
    updatedAt,
    protocol: 'blind-v1'
  };
  store.updatedAt = updatedAt;
  await atomicWrite(decisionsPath, `${JSON.stringify(store, null, 2)}\n`);
  return { saved: true, id: item.id, decision: store.decisions[item.id] };
});

app.post('/api/export', async (request, reply) => {
  const parsed = ExportInputSchema.safeParse(request.body);
  if (!parsed.success) return reply.code(400).send({ error: parsed.error.issues[0]?.message ?? 'Invalid reviewer.' });
  const store = await readDecisions();
  const missing = cases.filter((item) => store.decisions[item.id]?.status !== 'approved');
  if (missing.length) {
    return reply.code(409).send({ error: `${missing.length} cases still require an approved human decision.` });
  }
  const exported = cases.map((item) => {
    const decision = store.decisions[item.id]!;
    return ReplayCaseSchema.parse({
      ...item,
      expectedFindings: decision.expectedFindings,
      approval: {
        status: 'approved',
        approvedBy: decision.reviewer || parsed.data.reviewer,
        approvedAt: decision.updatedAt,
        ...(decision.notes ? { notes: decision.notes } : {})
      }
    });
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
  protocol: 'blind-v1',
  url: `http://${host}:${port}`,
  cases: cases.length,
  input: relative(inputPath),
  decisions: relative(decisionsPath),
  output: relative(outputPath)
}, null, 2));

const shutdown = async () => {
  await app.close();
  process.exit(0);
};
process.on('SIGINT', shutdown);
process.on('SIGTERM', shutdown);
