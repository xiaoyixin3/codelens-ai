import { spawnSync } from 'node:child_process';
import { createHash, randomUUID } from 'node:crypto';
import { access, copyFile, mkdtemp, readdir, realpath, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import assert from 'node:assert/strict';

// Synthetic data only. No external database/container/credential parameters accepted.
if (process.argv.slice(2).join(' ') !== '--isolated-fixture') {
  throw new Error('Requires --isolated-fixture; production database targets are not supported');
}
const root = process.cwd();
const jar = path.join(root, 'target/codelens-ai.jar');
const migrations = path.join(root, 'infra/migrations');
const name = `codelens-upgrade-rehearsal-${randomUUID()}`;
const password = randomUUID();
const temporaryParent = await realpath(tmpdir());
const temporary = await mkdtemp(path.join(temporaryParent, 'codelens-upgrade-rehearsal-'));
let created = false;
let stage = 'preflight';
let cleanupSucceeded = true;
let report: Record<string, unknown> = {};

function run(command: string, args: string[], input?: string, env = process.env, expectedFailure?: string) {
  const result = spawnSync(command, args, { encoding: 'utf8', windowsHide: true,
    timeout: 30_000, maxBuffer: 8 * 1024 * 1024, env,
    ...(input === undefined ? {} : { input }) });
  if (expectedFailure !== undefined) {
    assert.ok(!result.error && result.status !== null && result.status !== 0
      && `${result.stdout}\n${result.stderr}`.includes(expectedFailure),
    'Expected a specific migration refusal, not an arbitrary failure');
  } else if (result.error || result.status !== 0) throw new Error(`Rehearsal failed at ${stage}`);
  return result.stdout.trim(); // Never print SQL rows, child environment or full diagnostics.
}
function sql(database: string, statement: string) {
  assert.ok(['codelens', 'restore_fixture'].includes(database));
  return run('docker', ['exec', '-i', name, 'psql', '-X', '-v', 'ON_ERROR_STOP=1',
    '-U', 'codelens', '-d', database, '-At'], statement);
}
function migrate(database: string, directory: string, port: number, expectedFailure?: string) {
  run('java', ['-Dcodelens.mode=migrate', '-jar', jar], undefined, {
    ...process.env, CODELENS_ENVIRONMENT: 'test', CODELENS_FREEZE_PUBLICATIONS: 'false',
    CODELENS_PUBLIC_TRIAL: 'false', MIGRATIONS_DIR: directory,
    DATABASE_URL: `postgres://codelens:${password}@127.0.0.1:${port}/${database}`
  }, expectedFailure);
}
function fingerprint(database: string, tables: string[]) {
  const queries = tables.map(table => {
    assert.match(table, /^[a-z_]+$/);
    return `SELECT jsonb_build_object('table','${table}','rows',
      COALESCE(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text), '[]'::jsonb))::text FROM "${table}" t;`;
  });
  return createHash('sha256').update(sql(database, queries.join('\n'))).digest('hex');
}

try {
  await access(jar);
  const files = (await readdir(migrations)).filter(file => file.endsWith('.sql')).sort();
  assert.equal(files.length, 22, 'Rehearsal is explicitly scoped to schema 019 -> 022');
  assert.equal(files[19], '020_frozen_review_outputs.sql');
  assert.equal(files[20], '021_publication_recovery_audit.sql');
  assert.equal(files[21], '022_llm_request_plans.sql');
  for (const file of files.slice(0, 19)) await copyFile(path.join(migrations, file), path.join(temporary, file));
  stage = 'create-disposable-postgres';
  run('docker', ['run', '--detach', '--rm', '--name', name,
    '-e', 'POSTGRES_USER=codelens', '-e', `POSTGRES_PASSWORD=${password}`,
    '-e', 'POSTGRES_DB=codelens', '-p', '127.0.0.1::5432', 'postgres:17-alpine']);
  created = true;
  const portLine = run('docker', ['port', name, '5432/tcp']);
  assert.match(portLine, /^127\.0\.0\.1:\d+$/);
  const port = Number(portLine.split(':')[1]);
  stage = 'wait-for-postgres';
  let ready = false;
  for (let attempt = 0; attempt < 20; attempt++) {
    const status = spawnSync('docker', ['exec', name, 'pg_isready', '-h', '127.0.0.1', '-U', 'codelens'],
      { windowsHide: true, timeout: 3000 });
    if (status.status === 0) { ready = true; break; }
    await new Promise(resolve => setTimeout(resolve, 1000));
  }
  assert.ok(ready);
  stage = 'create-schema-019-and-history';
  migrate('codelens', temporary, port);
  const runId = randomUUID();
  sql('codelens', `BEGIN;
    INSERT INTO review_runs(id,github_repository_id,pull_number,base_sha,head_sha,status,
      pipeline_version,config_hash,enqueue_config_hash,summary)
    VALUES ('${runId}',42,7,'base1234','head1234','failed','java.13','fixture','fixture',
      '{"synthetic":true,"explanation":"original historical review"}');
    INSERT INTO review_jobs(id,review_run_id,payload,status,last_error)
    VALUES ('${randomUUID()}','${runId}','{"synthetic":true}','failed','publication uncertain fixture');
    INSERT INTO publications(id,review_run_id,head_sha,check_run_id,summary_comment_id)
    VALUES ('${randomUUID()}','${runId}','head1234',9,11);
    INSERT INTO check_publication_effects(review_run_id,operation,request_hash,state,remote_id)
    VALUES ('${runId}','check_start','${'a'.repeat(64)}','confirmed',9),
      ('${runId}','check_result','${'b'.repeat(64)}','sent',9),
      ('${runId}','summary_comment','${'c'.repeat(64)}','sent',11);
    INSERT INTO summary_publication_slots(repository_id,installation_id,pull_number,active_run_id)
    VALUES (42,1,7,'${runId}');
    COMMIT;`);
  assert.equal(sql('codelens', 'SELECT count(*) FROM schema_migrations;'), '19');
  const tables = sql('codelens', "SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename;").split('\n');
  const original = fingerprint('codelens', tables);
  console.log('Old-schema fixture ready; historical queue/journals/reservation retained.');
  stage = 'backup-and-restore';
  run('docker', ['exec', name, 'pg_dump', '-U', 'codelens', '-d', 'codelens', '-Fc', '-f', '/tmp/rehearsal.dump']);
  run('docker', ['exec', name, 'createdb', '-U', 'codelens', 'restore_fixture']);
  run('docker', ['exec', name, 'pg_restore', '-U', 'codelens', '-d', 'restore_fixture',
    '--exit-on-error', '--no-owner', '/tmp/rehearsal.dump']);
  assert.equal(fingerprint('restore_fixture', tables), original);
  stage = 'forward-upgrade';
  migrate('restore_fixture', migrations, port);
  const historicTables = tables.filter(table => table !== 'schema_migrations');
  assert.equal(fingerprint('restore_fixture', historicTables), fingerprint('codelens', historicTables));
  assert.equal(sql('restore_fixture', 'SELECT count(*) FROM schema_migrations;'), '22');
  assert.equal(sql('restore_fixture', 'SELECT count(*) FROM llm_request_plans;'), '0');
  assert.equal(sql('restore_fixture', 'SELECT count(*) FROM frozen_review_outputs;'), '0');
  assert.equal(sql('restore_fixture', 'SELECT count(*) FROM publication_recovery_audit;'), '0');
  const upgradedTables = [...tables, 'frozen_review_outputs', 'publication_recovery_audit', 'llm_request_plans'].sort();
  const upgraded = fingerprint('restore_fixture', upgradedTables);
  stage = 'idempotent-migration';
  migrate('restore_fixture', migrations, port);
  assert.equal(fingerprint('restore_fixture', upgradedTables), upgraded);
  stage = 'reject-old-manifest';
  migrate('restore_fixture', temporary, port, 'database contains an unknown migration: 020_');
  assert.equal(fingerprint('restore_fixture', upgradedTables), upgraded);
  assert.equal(fingerprint('codelens', tables), original);
  report = { status: 'passed', syntheticOnly: true, fromSchema: 19, toSchema: 22,
    backupRestoredExactly: true, historicalRowsPreserved: true, noArtifactBackfill: true, noAuditBackfill: true,
    repeatedMigrationUnchanged: true, oldManifestRejectedWithoutDataChanges: true,
    sourceUnchanged: true, liveDatabaseAccessed: false, githubWrites: false };
} catch {
  report = { status: 'failed', stage, syntheticOnly: true, liveDatabaseAccessed: false };
  process.exitCode = 1;
} finally {
  if (created) {
    const cleanup = spawnSync('docker', ['stop', name], { windowsHide: true, timeout: 30_000 });
    cleanupSucceeded = cleanup.status === 0;
  }
  // Resolve and constrain the exact generated temporary directory before recursive removal.
  const resolved = await realpath(temporary);
  assert.equal(path.dirname(resolved), temporaryParent);
  assert.ok(path.basename(resolved).startsWith('codelens-upgrade-rehearsal-'));
  await rm(resolved, { recursive: true, force: false });
  if (!cleanupSucceeded) process.exitCode = 1;
  console.log(JSON.stringify({ ...report, disposableContainerRemoved: cleanupSucceeded,
    temporaryManifestRemoved: true }, null, 2));
}
