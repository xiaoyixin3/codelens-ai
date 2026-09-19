import { readFile, readdir } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import postgres from 'postgres';
import { loadConfig } from '@codelens/config';

const config = loadConfig();
const sql = postgres(config.DATABASE_URL, { max: 1 });
const scriptDirectory = path.dirname(fileURLToPath(import.meta.url));
const migrationsDirectory = path.resolve(scriptDirectory, '../infra/migrations');

try {
  await sql`
    CREATE TABLE IF NOT EXISTS schema_migrations (
      name text PRIMARY KEY,
      applied_at timestamptz NOT NULL DEFAULT now()
    )
  `;
  const files = (await readdir(migrationsDirectory))
    .filter((file) => file.endsWith('.sql'))
    .sort();
  for (const file of files) {
    const applied = await sql<{ name: string }[]>`
      SELECT name FROM schema_migrations WHERE name = ${file} LIMIT 1
    `;
    if (applied.length) continue;
    const migration = await readFile(path.join(migrationsDirectory, file), 'utf8');
    await sql.begin(async (transaction) => {
      await transaction.unsafe(migration);
      await transaction`INSERT INTO schema_migrations (name) VALUES (${file})`;
    });
    console.log(`Applied ${file}`);
  }
  console.log('Database migrations completed.');
} finally {
  await sql.end();
}
