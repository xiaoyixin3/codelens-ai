import { loadConfig } from '@codelens/config';
import { PostgresLifecycleStore } from '@codelens/lifecycle';

const repositoryId = Number(process.argv[2]);
const confirmation = process.argv[3];
if (!Number.isSafeInteger(repositoryId) || repositoryId <= 0) {
  throw new Error('Usage: npm run data:delete -- <github-repository-id> CONFIRM');
}
if (confirmation !== 'CONFIRM') throw new Error('Deletion requires the literal confirmation argument CONFIRM.');

const config = loadConfig();
const store = new PostgresLifecycleStore(config.DATABASE_URL);
try {
  const result = await store.deleteRepository(repositoryId, process.env.CODELENS_OPERATOR ?? 'cli-operator');
  console.log(JSON.stringify({ status: 'deleted', ...result }));
} finally {
  await store.close();
}
