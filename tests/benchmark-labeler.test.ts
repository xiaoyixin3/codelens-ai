import { spawn, type ChildProcessWithoutNullStreams } from 'node:child_process';
import { once } from 'node:events';
import { mkdtemp, mkdir, rm, writeFile } from 'node:fs/promises';
import { createServer } from 'node:net';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';
import { computeReviewContextBundleDigest, ReviewContextBundleSchema } from '@codelens/evaluation';

const children: ChildProcessWithoutNullStreams[] = [];
const temporaryDirectories: string[] = [];

afterEach(async () => {
  for (const child of children.splice(0)) {
    if (!child.killed) child.kill('SIGTERM');
    await Promise.race([once(child, 'exit'), new Promise((resolve) => setTimeout(resolve, 2_000))]);
  }
  await Promise.all(temporaryDirectories.splice(0).map((directory) => rm(directory, { recursive: true, force: true })));
});

async function unusedPort(): Promise<number> {
  const server = createServer();
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  const address = server.address();
  if (!address || typeof address === 'string') throw new Error('Could not allocate a test port.');
  await new Promise<void>((resolve, reject) => server.close((error) => error ? reject(error) : resolve()));
  return address.port;
}

async function waitForReady(child: ChildProcessWithoutNullStreams): Promise<void> {
  let output = '';
  await new Promise<void>((resolve, reject) => {
    const timeout = setTimeout(() => reject(new Error(`Labeler did not start. Output: ${output}`)), 10_000);
    child.stdout.on('data', (chunk: Buffer) => {
      output += chunk.toString('utf8');
      if (output.includes('"status": "ready"')) {
        clearTimeout(timeout);
        resolve();
      }
    });
    child.stderr.on('data', (chunk: Buffer) => { output += chunk.toString('utf8'); });
    child.once('exit', (code) => {
      clearTimeout(timeout);
      reject(new Error(`Labeler exited with ${code}. Output: ${output}`));
    });
  });
}

describe('review reasoning workbench', () => {
  it('keeps gold API responses prediction-free while accepting cross-file evidence', async () => {
    const directory = await mkdtemp(path.join(tmpdir(), 'codelens-labeler-'));
    temporaryDirectories.push(directory);
    const contextDirectory = path.join(directory, 'context');
    await mkdir(contextDirectory);
    const inputPath = path.join(directory, 'cases.jsonl');
    const decisionsPath = path.join(directory, 'decisions.json');
    const legacyPath = path.join(directory, 'legacy.json');
    const outputPath = path.join(directory, 'frozen.jsonl');
    const baseSha = 'a'.repeat(40);
    const headSha = 'b'.repeat(40);
    const replayCase = {
      id: 'historical-1',
      context: {
        owner: 'example', repo: 'service', number: 7, title: 'Preserve empty input handling', body: 'Updates service behavior.',
        baseSha, headSha,
        files: [{
          path: 'src/Service.java', status: 'modified', additions: 1, deletions: 1,
          patch: '@@ -1,2 +1,2 @@\n-class Service { return guard(input); }\n+class Service { return load(input); }\n class End {}'
        }]
      },
      expectedFindings: [],
      approval: { status: 'candidate' },
      provenance: {
        kind: 'historical_pr', sourceUrl: 'https://github.com/example/service/pull/7',
        repositoryLicense: 'Apache-2.0', collectedAt: '2026-10-02T10:00:00+08:00'
      }
    };
    await writeFile(inputPath, `${JSON.stringify(replayCase)}\n`, 'utf8');

    const unsigned = {
      version: 1 as const,
      caseId: replayCase.id,
      packetId: 'review-context-test',
      generatedAt: '2026-10-02T10:05:00+08:00',
      baseSha,
      headSha,
      files: [
        { path: 'src/Service.java', revision: 'base' as const, role: 'source' as const, language: 'Java', content: 'class Service { return guard(input); }\nclass End {}' },
        { path: 'src/Service.java', revision: 'head' as const, role: 'source' as const, language: 'Java', content: 'class Service { return load(input); }\nclass End {}' },
        { path: 'src/Caller.java', revision: 'head' as const, role: 'source' as const, language: 'Java', content: 'class Caller { service.load(""); }' },
        { path: 'src/ExistingGuard.java', revision: 'head' as const, role: 'source' as const, language: 'Java', content: 'class ExistingGuard { void load() {} }' }
      ],
      symbols: [
        { id: 'head:Service.load', name: 'Service.load', kind: 'method', path: 'src/Service.java', revision: 'head' as const, startLine: 1, endLine: 1 },
        { id: 'head:Caller.run', name: 'Caller.run', kind: 'method', path: 'src/Caller.java', revision: 'head' as const, startLine: 1, endLine: 1 },
        { id: 'head:ExistingGuard.load', name: 'ExistingGuard.load', kind: 'method', path: 'src/ExistingGuard.java', revision: 'head' as const, startLine: 1, endLine: 1 }
      ],
      relationships: [{
        fromSymbolId: 'head:Caller.run', toSymbolId: 'head:Service.load', type: 'calls' as const,
        evidencePath: 'src/Caller.java', evidenceLine: 1
      }],
      limitations: []
    };
    const bundle = ReviewContextBundleSchema.parse({ ...unsigned, digest: computeReviewContextBundleDigest(unsigned) });
    await writeFile(path.join(contextDirectory, 'historical-1.json'), `${JSON.stringify(bundle)}\n`, 'utf8');

    const port = await unusedPort();
    const child = spawn(process.execPath, [
      '--import', 'tsx', 'scripts/benchmark-labeler.ts', `--input=${inputPath}`, `--decisions=${decisionsPath}`,
      `--legacy-decisions=${legacyPath}`, `--output=${outputPath}`, `--context-dir=${contextDirectory}`, '--mode=gold'
    ], {
      cwd: path.resolve(import.meta.dirname, '..'),
      env: { ...process.env, BENCHMARK_LABELER_PORT: String(port) },
      stdio: ['pipe', 'pipe', 'pipe']
    });
    children.push(child);
    await waitForReady(child);

    const baseUrl = `http://localhost:${port}`;
    const initialResponse = await fetch(`${baseUrl}/api/state`);
    expect(initialResponse.status).toBe(200);
    const initialText = await initialResponse.text();
    expect(initialText).not.toMatch(/prediction|assistance|machineSuggestions|reuseInvestigations/i);
    const initialState = JSON.parse(initialText);
    expect(initialState.cases[0].contextPacket.relationships).toHaveLength(1);
    expect(initialState.cases[0].changeBrief.coverage.level).toBe('semantic');
    expect(initialState.cases[0].changeBrief.questions.some((question: { id: string }) => question.id.endsWith(':reuse'))).toBe(true);
    expect(initialState.cases[0].changeBrief.intent.evidenceIds).toEqual(['pr:title', 'pr:body']);
    expect((await fetch(`${baseUrl}/api/reuse/options`, {
      method: 'POST', headers: { 'content-type': 'application/json' }, body: '{}'
    })).status).toBe(403);

    const rootCause = {
      rootCauseId: 'empty-input-contract', category: 'correctness', severity: 'high', confidence: 'likely',
      claim: 'The changed method bypasses the established empty-input contract.',
      trigger: 'Caller.run passes an empty input.',
      impact: 'The service can load data for an invalid request.',
      evidence: [{
        kind: 'caller', path: 'src/Caller.java', revision: 'head', startLine: 1, endLine: 1,
        symbol: 'Caller.run', fact: 'The caller supplies an empty input.'
      }],
      affectedSymbols: ['Service.load', 'Caller.run'],
      acceptableFix: 'Reuse the existing guard before calling load.',
      verification: 'Run the caller test with an empty input and assert rejection.'
    };
    const saveResponse = await fetch(`${baseUrl}/api/decisions/historical-1`, {
      method: 'PUT', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ status: 'frozen', reviewer: 'reviewer-a', rootCauses: [rootCause] })
    });
    expect(saveResponse.status).toBe(200);
    expect(await saveResponse.text()).not.toMatch(/prediction|assistance|machineSuggestions|reuseInvestigations/i);

    const frozenText = await (await fetch(`${baseUrl}/api/state`)).text();
    expect(frozenText).not.toMatch(/prediction|assistance|machineSuggestions|reuseInvestigations/i);
    expect(JSON.parse(frozenText).cases[0].decision.rootCauses[0].verification).toContain('caller test');

    const assistedPort = await unusedPort();
    const assistedDecisionsPath = path.join(directory, 'assisted-decisions.json');
    const assistedChild = spawn(process.execPath, [
      '--import', 'tsx', 'scripts/benchmark-labeler.ts', `--input=${inputPath}`, `--decisions=${assistedDecisionsPath}`,
      `--legacy-decisions=${legacyPath}`, `--output=${path.join(directory, 'assisted-frozen.jsonl')}`,
      `--context-dir=${contextDirectory}`, '--mode=assisted'
    ], {
      cwd: path.resolve(import.meta.dirname, '..'),
      env: { ...process.env, BENCHMARK_LABELER_PORT: String(assistedPort) },
      stdio: ['pipe', 'pipe', 'pipe']
    });
    children.push(assistedChild);
    await waitForReady(assistedChild);
    const assistedState = await (await fetch(`http://localhost:${assistedPort}/api/state`)).json() as any;
    expect(assistedState.mode).toBe('assisted');
    expect(assistedState.cases[0].assistance).toBeDefined();
    expect(assistedState.cases[0].reuseInvestigations[0].candidates[0]).toMatchObject({
      symbolName: 'ExistingGuard.load', relationship: 'same_contract', fit: 'direct'
    });
    expect(assistedState.cases[0].reuseInvestigations[0].patchGate.allowed).toBe(false);
    const investigation = assistedState.cases[0].reuseInvestigations[0];
    const selected = investigation.candidates[0];
    const optionsResponse = await fetch(`http://localhost:${assistedPort}/api/reuse/options`, {
      method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({
        behaviorId: investigation.behaviorId, goal: investigation.goal, searchScope: investigation.searchScope,
        candidates: investigation.candidates, decision: 'reuse', selectedCandidateId: selected.id,
        justification: 'Reuse the established load contract and keep this change local.',
        changeBudget: { maxFiles: 2, maxChangedSymbols: 3, publicContractChangeAllowed: false }
      })
    });
    expect(optionsResponse.status).toBe(200);
    const optionsResult = await optionsResponse.json() as any;
    expect(optionsResult.options[0]).toMatchObject({ strategy: 'reuse', candidateId: selected.id });
  }, 20_000);
});
