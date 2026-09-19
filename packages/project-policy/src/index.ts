import { createHash, randomUUID } from 'node:crypto';
import { minimatch } from 'minimatch';
import postgres, { type Sql } from 'postgres';
import { parse as parseYaml } from 'yaml';
import { z } from 'zod';
import type { ChangedFile, FindingSeverity } from '@codelens/contracts';
import type { GitHubGateway } from '@codelens/github';
import { isSafeRepositoryPath, redactSecrets } from '@codelens/security';

const SeverityThresholdSchema = z.object({
  critical: z.number().min(0).max(1).optional(),
  high: z.number().min(0).max(1).optional(),
  medium: z.number().min(0).max(1).optional(),
  low: z.number().min(0).max(1).optional()
}).strict();

const PolicyFileSchema = z.object({
  version: z.literal(1),
  review: z.object({
    language: z.enum(['en', 'zh']).default('en'),
    blocking: z.boolean().default(false),
    maxInlineComments: z.number().int().min(0).max(50).default(8),
    minimumConfidence: SeverityThresholdSchema.default({})
  }).default({ language: 'en', blocking: false, maxInlineComments: 8, minimumConfidence: {} }),
  include: z.array(z.string().min(1)).default(['**/*']),
  exclude: z.array(z.string().min(1)).default([]),
  rules: z.array(z.object({
    key: z.string().min(1).max(120),
    content: z.string().min(1).max(2_000),
    scope: z.string().min(1).optional(),
    severity: z.enum(['critical', 'high', 'medium', 'low']).optional(),
    enabled: z.boolean().default(true)
  }).strict()).default([])
}).strict();

export interface ProjectRule {
  key: string;
  content: string;
  source: 'config' | 'codelens_md';
  scope?: string;
  severity?: FindingSeverity;
  enabled: boolean;
}

export interface RepositoryPolicy {
  hash: string;
  sourceCommitSha: string;
  language: 'en' | 'zh';
  blocking: boolean;
  maxInlineComments: number;
  minimumConfidence: Partial<Record<FindingSeverity, number>>;
  include: string[];
  exclude: string[];
  guidance: string;
  rules: ProjectRule[];
  warnings: string[];
}

interface PolicyFile {
  version: 1;
  review: {
    language: 'en' | 'zh';
    blocking: boolean;
    maxInlineComments: number;
    minimumConfidence: Partial<Record<FindingSeverity, number>>;
  };
  include: string[];
  exclude: string[];
  rules: Array<{
    key: string;
    content: string;
    scope?: string;
    severity?: FindingSeverity;
    enabled: boolean;
  }>;
}

const DEFAULT_POLICY_FILE: PolicyFile = {
  version: 1,
  review: { language: 'en', blocking: false, maxInlineComments: 8, minimumConfidence: {} },
  include: ['**/*'],
  exclude: [],
  rules: []
};

function stableHash(value: unknown): string {
  return createHash('sha256').update(JSON.stringify(value)).digest('hex');
}

export function parsePolicyFile(source: string): PolicyFile {
  if (source.length > 100_000) throw new Error('.codelens.yml exceeds 100 KB.');
  return PolicyFileSchema.parse(parseYaml(source, { maxAliasCount: 10 })) as PolicyFile;
}

export function parseCodeLensMarkdown(source: string): ProjectRule[] {
  const rules: ProjectRule[] = [];
  for (const raw of source.slice(0, 20_000).split(/\r?\n/)) {
    const match = raw.match(/^\s*[-*]\s+(.+?)\s*$/);
    if (!match?.[1]) continue;
    const content = match[1].trim();
    rules.push({
      key: `codelens-md-${stableHash(content).slice(0, 12)}`,
      content,
      source: 'codelens_md',
      enabled: true
    });
    if (rules.length >= 100) break;
  }
  return rules;
}

export function isPathIncluded(path: string, policy: RepositoryPolicy): boolean {
  if (!isSafeRepositoryPath(path)) return false;
  const included = policy.include.some((pattern) => minimatch(path, pattern, { dot: true }));
  const excluded = policy.exclude.some((pattern) => minimatch(path, pattern, { dot: true }));
  return included && !excluded;
}

export function filterPolicyFiles(files: ChangedFile[], policy: RepositoryPolicy): ChangedFile[] {
  return files.filter((file) => isPathIncluded(file.path, policy));
}

export interface PolicyStore {
  save(repositoryId: number, policy: RepositoryPolicy): Promise<void>;
  close(): Promise<void>;
}

export class InMemoryPolicyStore implements PolicyStore {
  readonly policies = new Map<string, RepositoryPolicy>();
  async save(repositoryId: number, policy: RepositoryPolicy): Promise<void> {
    this.policies.set(`${repositoryId}:${policy.sourceCommitSha}`, policy);
  }
  async close(): Promise<void> {}
}

export class PostgresPolicyStore implements PolicyStore {
  readonly #sql: Sql;
  constructor(databaseUrl: string) { this.#sql = postgres(databaseUrl, { max: 5 }); }
  async save(repositoryId: number, policy: RepositoryPolicy): Promise<void> {
    await this.#sql.begin(async (sql) => {
      await sql`
        DELETE FROM project_rules
        WHERE github_repository_id = ${repositoryId} AND source_commit_sha = ${policy.sourceCommitSha}
      `;
      for (const rule of policy.rules) await sql`
        INSERT INTO project_rules (
          id, github_repository_id, source, rule_key, content, scope_glob,
          severity, enabled, source_commit_sha, config_hash
        ) VALUES (
          ${randomUUID()}, ${repositoryId}, ${rule.source}, ${rule.key}, ${rule.content},
          ${rule.scope ?? null}, ${rule.severity ?? null}, ${rule.enabled},
          ${policy.sourceCommitSha}, ${policy.hash}
        )
      `;
    });
  }
  async close(): Promise<void> { await this.#sql.end(); }
}

export class RepositoryPolicyLoader {
  constructor(
    private readonly github: GitHubGateway,
    private readonly store: PolicyStore,
    private readonly defaultMaxInlineComments = 8
  ) {}

  async load(input: {
    repositoryId: number;
    installationId: number;
    owner: string;
    repo: string;
    headSha: string;
  }): Promise<RepositoryPolicy> {
    const warnings: string[] = [];
    let file: PolicyFile = {
      ...DEFAULT_POLICY_FILE,
      review: { ...DEFAULT_POLICY_FILE.review, maxInlineComments: this.defaultMaxInlineComments }
    };
    let guidance = '';

    try {
      const source = await this.github.getFileContent(
        input.installationId, input.owner, input.repo, '.codelens.yml', input.headSha
      );
      try { file = parsePolicyFile(source); }
      catch (error) {
        warnings.push(`.codelens.yml is invalid; defaults were used: ${error instanceof Error ? error.message : String(error)}`);
      }
    } catch (error) {
      if ((error as { status?: number }).status !== 404) {
        warnings.push(`.codelens.yml could not be loaded; defaults were used.`);
      }
    }

    try {
      guidance = redactSecrets(await this.github.getFileContent(
        input.installationId, input.owner, input.repo, 'CODELENS.md', input.headSha
      ));
    } catch (error) {
      if ((error as { status?: number }).status !== 404) {
        warnings.push('CODELENS.md could not be loaded.');
      }
    }

    const rules: ProjectRule[] = [
      ...file.rules.map((rule) => ({ ...rule, content: redactSecrets(rule.content), source: 'config' as const })),
      ...parseCodeLensMarkdown(guidance)
    ].filter((rule) => rule.enabled);
    const hashInput = { file, guidance, rules };
    const policy: RepositoryPolicy = {
      hash: stableHash(hashInput),
      sourceCommitSha: input.headSha,
      language: file.review.language,
      blocking: file.review.blocking,
      maxInlineComments: file.review.maxInlineComments,
      minimumConfidence: file.review.minimumConfidence,
      include: file.include,
      exclude: file.exclude,
      guidance,
      rules,
      warnings
    };
    await this.store.save(input.repositoryId, policy);
    return policy;
  }
}
