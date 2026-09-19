import { fileURLToPath } from 'node:url';
import { defineConfig } from 'vitest/config';

function source(relativePath: string): string {
  return fileURLToPath(new URL(relativePath, import.meta.url));
}

export default defineConfig({
  resolve: {
    alias: {
      '@codelens/code-index': source('./packages/code-index/src/index.ts'),
      '@codelens/config': source('./packages/config/src/index.ts'),
      '@codelens/contracts': source('./packages/contracts/src/index.ts'),
      '@codelens/evaluation': source('./packages/evaluation/src/index.ts'),
      '@codelens/github': source('./packages/github/src/index.ts'),
      '@codelens/impact-engine': source('./packages/impact-engine/src/index.ts'),
      '@codelens/llm-runtime': source('./packages/llm-runtime/src/index.ts'),
      '@codelens/lifecycle': source('./packages/lifecycle/src/index.ts'),
      '@codelens/persistence': source('./packages/persistence/src/index.ts'),
      '@codelens/project-policy': source('./packages/project-policy/src/index.ts'),
      '@codelens/queue': source('./packages/queue/src/index.ts'),
      '@codelens/resilience': source('./packages/resilience/src/index.ts'),
      '@codelens/risk-review': source('./packages/risk-review/src/index.ts'),
      '@codelens/review-core': source('./packages/review-core/src/index.ts'),
      '@codelens/security': source('./packages/security/src/index.ts')
    }
  },
  test: {
    include: ['tests/**/*.test.ts'],
    environment: 'node',
    clearMocks: true
  }
});
