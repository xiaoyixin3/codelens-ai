import { readFile, readdir } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';
import path from 'node:path';
import postgres from 'postgres';
import { loadConfig } from '@codelens/config';

const config = loadConfig();
const sql = postgres(config.DATABASE_URL, { max: 1 });
const scriptDirectory = path.dirname(fileURLToPath(import.meta.url));
const migrationsDirectory = path.resolve(scriptDirectory, '../infra/migrations');
const migrationLockId = '4349336388659987785';

try {
  await sql`SELECT pg_advisory_lock(${migrationLockId}::bigint)`;
  await sql`
    CREATE TABLE IF NOT EXISTS schema_migrations (
      name text PRIMARY KEY,
      checksum text,
      applied_at timestamptz NOT NULL DEFAULT now()
    )
  `;
  await sql`ALTER TABLE schema_migrations ADD COLUMN IF NOT EXISTS checksum text`;
  const files = (await readdir(migrationsDirectory))
    .filter((file) => file.endsWith('.sql'))
    .sort();
  files.forEach((file, index) => {
    if (!/^\d{3}_[a-z0-9_]+\.sql$/.test(file) || Number(file.slice(0, 3)) !== index + 1) {
      throw new Error(`Invalid or non-contiguous migration name: ${file}`);
    }
  });
  if (files.length === 0) throw new Error('Migration manifest must not be empty');
  const manifest = await Promise.all(files.map(async (file) => {
    const migration = await readFile(path.join(migrationsDirectory, file), 'utf8');
    return { file, migration, checksum: createHash('sha256').update(migration).digest('hex') };
  }));
  const applied = await sql<{ name: string; checksum: string | null }[]>`
    SELECT name, checksum FROM schema_migrations ORDER BY name
  `;
  const expected = new Map(manifest.map((entry) => [entry.file, entry.checksum]));
  for (const row of applied) {
    const checksum = expected.get(row.name);
    if (!checksum) throw new Error(`Database contains unknown migration: ${row.name}`);
    if (row.checksum === null) {
      await sql`UPDATE schema_migrations SET checksum = ${checksum} WHERE name = ${row.name} AND checksum IS NULL`;
    } else if (row.checksum !== checksum) {
      throw new Error(`Applied migration checksum mismatch: ${row.name}`);
    }
  }
  const appliedNames = new Set(applied.map((row) => row.name));
  for (const entry of manifest) {
    if (appliedNames.has(entry.file)) continue;
    await sql.begin(async (transaction) => {
      await transaction.unsafe(entry.migration);
      await transaction`INSERT INTO schema_migrations (name, checksum) VALUES (${entry.file}, ${entry.checksum})`;
    });
    console.log(`Applied ${entry.file}`);
  }
  console.log('Database migrations completed.');
} finally {
  try { await sql`SELECT pg_advisory_unlock(${migrationLockId}::bigint)`; } catch {}
  await sql.end();
}
