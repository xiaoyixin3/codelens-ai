import { defineConfig } from 'tsup';

export default defineConfig({
  entry: {
    api: 'apps/api/src/main.ts',
    worker: 'apps/worker/src/main.ts',
    migrate: 'scripts/migrate.ts',
    'beta-readiness': 'scripts/beta-readiness.ts',
    'local-beta': 'scripts/local-beta.ts',
    'github-preflight': 'scripts/github-preflight.ts',
    'smoke-pipeline': 'scripts/smoke-pipeline.ts',
    retention: 'scripts/retention.ts',
    'delete-repository-data': 'scripts/delete-repository-data.ts'
  },
  outDir: 'dist',
  format: ['esm'],
  platform: 'node',
  target: 'node24',
  bundle: true,
  splitting: false,
  sourcemap: true,
  clean: true,
  minify: false,
  treeshake: true
});
