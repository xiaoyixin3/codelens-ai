import { access, readFile, readdir } from 'node:fs/promises';
import path from 'node:path';
import { parse } from 'yaml';

const root = process.cwd();
const required = [
  'Dockerfile',
  'LICENSE',
  'CHANGELOG.md',
  '.gitattributes',
  'INSTALLATION.md',
  'CONTRIBUTING.md',
  'SECURITY.md',
  'OPERATIONS.md',
  'RELEASE_CHECKLIST.md',
  '.github/workflows/ci.yml',
  '.github/workflows/codeql.yml',
  '.github/PULL_REQUEST_TEMPLATE.md',
  '.github/dependabot.yml',
  'infra/compose.production.yml',
  'dist/api.js',
  'dist/worker.js',
  'dist/migrate.js',
  'dist/beta-readiness.js',
  'dist/collect-positive-benchmark.js',
  'dist/benchmark-labeler.js',
  'dist/local-beta.js',
  'dist/github-preflight.js',
  'dist/smoke-pipeline.js',
  'dist/retention.js',
  'dist/delete-repository-data.js'
];
const missing: string[] = [];
for (const file of required) {
  try { await access(path.join(root, file)); }
  catch { missing.push(file); }
}

const packageJson = JSON.parse(await readFile(path.join(root, 'package.json'), 'utf8')) as { version?: string };
const migrations = (await readdir(path.join(root, 'infra/migrations')))
  .filter((file) => file.endsWith('.sql'))
  .sort();
const migrationNumbers = migrations.map((file) => Number(file.slice(0, 3)));
const orderedMigrations = migrationNumbers.every((value, index) => value === index + 1);
const versionReady = /^1\.0\.0-beta\.\d+$/.test(packageJson.version ?? '');
const workflowPath = path.join(root, '.github/workflows/ci.yml');
const workflowSource = await readFile(workflowPath, 'utf8');
const workflowDirectory = path.join(root, '.github/workflows');
const workflowFiles = (await readdir(workflowDirectory))
  .filter((file) => /\.ya?ml$/.test(file))
  .sort();
const workflowSources = await Promise.all(
  workflowFiles.map((file) => readFile(path.join(workflowDirectory, file), 'utf8'))
);
for (const source of workflowSources) parse(source);
const actionReferences = workflowSources.flatMap((source) =>
  [...source.matchAll(/^\s*uses:\s*([^\s#]+)/gm)]
  .map((match) => match[1]!)
  .filter((reference) => !reference.startsWith('./'))
);
const unpinnedActions = actionReferences.filter(
  (reference) => !/@[a-f0-9]{40}$/.test(reference)
);
const workflowUsesReadOnlyPermissions = /^permissions:\s*\r?\n\s+contents:\s*read\s*$/m.test(workflowSource);
const releaseChecklist = await readFile(path.join(root, 'RELEASE_CHECKLIST.md'), 'utf8');
const manualGates = [...releaseChecklist.matchAll(/^- \[ \] (.+)$/gm)]
  .map((match) => match[1]!.trim());
const automatedReady = missing.length === 0 && orderedMigrations && versionReady &&
  unpinnedActions.length === 0 && workflowUsesReadOnlyPermissions;

console.log(JSON.stringify({
  automatedReady,
  version: packageJson.version,
  migrations,
  missing,
  workflowFiles,
  unpinnedActions,
  workflowUsesReadOnlyPermissions,
  manualGates
}, null, 2));

if (!automatedReady) process.exitCode = 1;
