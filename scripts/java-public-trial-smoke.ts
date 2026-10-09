import { spawnSync } from 'node:child_process';
import { readFileSync } from 'node:fs';

// Explicitly scoped deployment acceptance fixture, never a general repository writer.
const repository = 'codelens-ai-lab/codelens-beta-test';
const baseBranch = 'codelens/java12-smoke-base-20261004';
const headBranch = 'codelens/java12-smoke-head-20261004';
let stage = 'arguments';

function api(endpoint: string, body?: unknown) {
  const args = ['api', '--hostname', 'github.com', endpoint];
  if (body !== undefined) args.push('--method', 'POST', '--input', '-');
  const result = spawnSync('gh', args, {
    input: body === undefined ? undefined : JSON.stringify(body),
    encoding: 'utf8', timeout: 30_000, windowsHide: true
  });
  if (result.error || result.status !== 0) throw new Error('GitHub operation failed; inspect remote state before repeating writes');
  return JSON.parse(result.stdout);
}

try {
  if (process.argv.slice(2).join(' ') !== '--create-sandbox-pr') {
    throw new Error('Requires the explicit --create-sandbox-pr flag');
  }
  stage = 'sandbox-permissions';
  const metadata = api(`repos/${repository}`);
  if (!metadata.permissions?.push || metadata.full_name !== repository) throw new Error('Sandbox write permission required');
  stage = 'existing-pr';
  const existing = api(`repos/${repository}/pulls?state=open&head=codelens-ai-lab:${headBranch}`);
  if (existing.length !== 0) {
    console.log(JSON.stringify({status: 'existing_pr', url: existing[0].html_url, number: existing[0].number}));
  } else {
    stage = 'branch-collision-check';
    const refs = api(`repos/${repository}/git/matching-refs/heads/codelens/java12-smoke-`);
    if (refs.length !== 0) throw new Error('Fixture refs already exist; inspect rather than overwriting or blindly replaying');
    stage = 'read-base';
    const sourceRef = api(`repos/${repository}/git/ref/heads/${metadata.default_branch}`);
    const sourceCommit = api(`repos/${repository}/git/commits/${sourceRef.object.sha}`);
    const paths = ['pom.xml', 'src/main/java/trial/PriceCalculator.java', 'src/main/java/trial/OrderService.java', 'src/test/java/trial/OrderServiceTest.java'];
    const tree = paths.map(path => ({path, mode: '100644', type: 'blob',
      content: readFileSync(new URL(`../examples/public-trial-java/${path}`, import.meta.url), 'utf8')}));
    stage = 'create-fixture-tree';
    const baselineTree = api(`repos/${repository}/git/trees`, {base_tree: sourceCommit.tree.sha, tree});
    stage = 'create-fixture-base-commit';
    const baseline = api(`repos/${repository}/git/commits`, {message: 'test: isolated Java semantic deployment acceptance fixture', tree: baselineTree.sha, parents: [sourceRef.object.sha]});
    stage = 'create-fixture-base-ref';
    api(`repos/${repository}/git/refs`, {ref: `refs/heads/${baseBranch}`, sha: baseline.sha});
    stage = 'create-change-tree';
    const changed = {...tree[1]!, content: tree[1]!.content.replace('return unitPriceCents * quantity;', 'return Math.multiplyExact(unitPriceCents, quantity);')};
    const changedTree = api(`repos/${repository}/git/trees`, {base_tree: baselineTree.sha, tree: [changed]});
    stage = 'create-change-commit';
    const head = api(`repos/${repository}/git/commits`, {message: 'test: verify Java caller and test impact for checked multiplication', tree: changedTree.sha, parents: [baseline.sha]});
    stage = 'create-change-ref';
    api(`repos/${repository}/git/refs`, {ref: `refs/heads/${headBranch}`, sha: head.sha});
    stage = 'create-sandbox-pr';
    const pr = api(`repos/${repository}/pulls`, {
      title: '[Deployment acceptance] Java whole-repository caller and test impact',
      head: headBranch, base: baseBranch,
      body: 'Isolated deployment smoke fixture; do not merge. Only PriceCalculator changes. OrderService and OrderServiceTest remain unchanged to test whole-repository semantic retrieval. S1 must not execute repository code. Expected delivery outcome: advisory neutral Check and one owned summary. This is not independent quality, precision or time-saving evidence.'
    });
    console.log(JSON.stringify({status: 'created', url: pr.html_url, number: pr.number, baseSha: baseline.sha, headSha: head.sha}));
  }
} catch {
  console.error(JSON.stringify({status: 'stopped', stage, guidance: 'Inspect remote state before another attempt; no automatic mutation retry.'}));
  process.exitCode = 1;
}
