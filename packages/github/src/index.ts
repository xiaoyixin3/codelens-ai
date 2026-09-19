import { App } from 'octokit';
import type { ChangeSummary, ChangedFile, PullRequestContext } from '@codelens/contracts';
import { withRetry } from '@codelens/resilience';
import { assertSafeRepositoryPath, redactSecrets } from '@codelens/security';

const SUMMARY_MARKER = '<!-- codelens-ai:summary -->';

export interface CheckInput {
  installationId: number;
  owner: string;
  repo: string;
  headSha: string;
  detailsUrl?: string;
}

export interface CompleteCheckInput extends CheckInput {
  checkRunId: number;
  conclusion: 'success' | 'neutral' | 'failure' | 'stale';
  title: string;
  summary: string;
  annotations?: CheckAnnotation[];
}

export interface CheckAnnotation {
  path: string;
  startLine: number;
  endLine: number;
  level: 'notice' | 'warning' | 'failure';
  title: string;
  message: string;
  rawDetails?: string;
}

export interface GitHubGateway {
  getPullRequest(
    installationId: number,
    owner: string,
    repo: string,
    pullNumber: number
  ): Promise<PullRequestContext>;
  getCurrentHeadSha(
    installationId: number,
    owner: string,
    repo: string,
    pullNumber: number
  ): Promise<string>;
  getFileContent(
    installationId: number,
    owner: string,
    repo: string,
    path: string,
    ref: string
  ): Promise<string>;
  startCheck(input: CheckInput, existingCheckRunId?: number): Promise<number>;
  completeCheck(input: CompleteCheckInput): Promise<void>;
  upsertSummaryComment(input: {
    installationId: number;
    owner: string;
    repo: string;
    pullNumber: number;
    body: string;
    existingCommentId?: number;
  }): Promise<number>;
}

export interface GitHubGatewayOptions {
  appId: string;
  privateKey: string;
}

export const REQUIRED_GITHUB_PERMISSIONS = {
  metadata: 'read',
  contents: 'read',
  pull_requests: 'write',
  checks: 'write',
  issues: 'read'
} as const;

type PermissionLevel = 'none' | 'read' | 'write' | 'admin';

export interface GitHubPermissionAssessment {
  ok: boolean;
  missing: Array<{
    permission: keyof typeof REQUIRED_GITHUB_PERMISSIONS;
    required: 'read' | 'write';
    actual: string;
  }>;
}

export function assessGitHubPermissions(
  actual: Record<string, string | undefined>
): GitHubPermissionAssessment {
  const rank: Record<PermissionLevel, number> = { none: 0, read: 1, write: 2, admin: 3 };
  const missing = Object.entries(REQUIRED_GITHUB_PERMISSIONS).flatMap(([permission, required]) => {
    const current = actual[permission] ?? 'none';
    const currentRank = rank[current as PermissionLevel] ?? 0;
    if (currentRank >= rank[required]) return [];
    return [{
      permission: permission as keyof typeof REQUIRED_GITHUB_PERMISSIONS,
      required,
      actual: current
    }];
  });
  return { ok: missing.length === 0, missing };
}

export class OctokitGitHubGateway implements GitHubGateway {
  readonly #app: App;

  constructor(options: GitHubGatewayOptions) {
    this.#app = new App({ appId: options.appId, privateKey: options.privateKey });
  }

  async #client(installationId: number) {
    return this.#app.getInstallationOctokit(installationId);
  }

  async #retry<T>(operation: () => Promise<T>): Promise<T> {
    return withRetry(operation, { attempts: 3, baseDelayMs: 500, maxDelayMs: 10_000 });
  }

  async getPullRequest(
    installationId: number,
    owner: string,
    repo: string,
    pullNumber: number
  ): Promise<PullRequestContext> {
    const client = await this.#client(installationId);
    const [{ data: pull }, files] = await Promise.all([
      this.#retry(() => client.rest.pulls.get({ owner, repo, pull_number: pullNumber })),
      this.#retry(() => client.paginate(client.rest.pulls.listFiles, {
        owner,
        repo,
        pull_number: pullNumber,
        per_page: 100
      }))
    ]);

    const changedFiles: ChangedFile[] = files.map((file) => ({
      path: file.filename,
      ...(file.previous_filename ? { previousPath: file.previous_filename } : {}),
      status: file.status,
      additions: file.additions,
      deletions: file.deletions,
      patch: file.patch ?? ''
    }));

    return {
      owner,
      repo,
      number: pullNumber,
      title: pull.title,
      body: pull.body ?? '',
      baseSha: pull.base.sha,
      headSha: pull.head.sha,
      files: changedFiles
    };
  }

  async getCurrentHeadSha(
    installationId: number,
    owner: string,
    repo: string,
    pullNumber: number
  ): Promise<string> {
    const client = await this.#client(installationId);
    const { data } = await this.#retry(() =>
      client.rest.pulls.get({ owner, repo, pull_number: pullNumber })
    );
    return data.head.sha;
  }

  async getFileContent(
    installationId: number,
    owner: string,
    repo: string,
    path: string,
    ref: string
  ): Promise<string> {
    assertSafeRepositoryPath(path);
    const client = await this.#client(installationId);
    const { data } = await this.#retry(() => client.rest.repos.getContent({ owner, repo, path, ref }));
    if (Array.isArray(data) || data.type !== 'file' || !('content' in data)) {
      throw new Error(`Expected ${redactSecrets(path)} at ${ref} to be a file.`);
    }
    return Buffer.from(data.content.replace(/\n/g, ''), 'base64').toString('utf8');
  }

  async startCheck(input: CheckInput, existingCheckRunId?: number): Promise<number> {
    const client = await this.#client(input.installationId);
    if (existingCheckRunId) {
      await this.#retry(() => client.rest.checks.update({
        owner: input.owner,
        repo: input.repo,
        check_run_id: existingCheckRunId,
        status: 'in_progress',
        started_at: new Date().toISOString()
      }));
      return existingCheckRunId;
    }

    const { data } = await this.#retry(() => client.rest.checks.create({
      owner: input.owner,
      repo: input.repo,
      name: 'CodeLens AI Review',
      head_sha: input.headSha,
      status: 'in_progress',
      started_at: new Date().toISOString(),
      ...(input.detailsUrl ? { details_url: input.detailsUrl } : {})
    }));
    return data.id;
  }

  async completeCheck(input: CompleteCheckInput): Promise<void> {
    const client = await this.#client(input.installationId);
    await this.#retry(() => client.rest.checks.update({
      owner: input.owner,
      repo: input.repo,
      check_run_id: input.checkRunId,
      status: 'completed',
      conclusion: input.conclusion,
      completed_at: new Date().toISOString(),
      output: {
        title: input.title,
        summary: input.summary,
        ...(input.annotations?.length
          ? {
              annotations: input.annotations.slice(0, 50).map((annotation) => ({
                path: annotation.path,
                start_line: annotation.startLine,
                end_line: annotation.endLine,
                annotation_level: annotation.level,
                title: annotation.title,
                message: annotation.message,
                ...(annotation.rawDetails ? { raw_details: annotation.rawDetails } : {})
              }))
            }
          : {})
      }
    }));
  }

  async upsertSummaryComment(input: {
    installationId: number;
    owner: string;
    repo: string;
    pullNumber: number;
    body: string;
    existingCommentId?: number;
  }): Promise<number> {
    const client = await this.#client(input.installationId);
    const body = `${SUMMARY_MARKER}\n${input.body}`;
    let commentId = input.existingCommentId;

    if (!commentId) {
      const comments = await this.#retry(() => client.paginate(client.rest.issues.listComments, {
        owner: input.owner,
        repo: input.repo,
        issue_number: input.pullNumber,
        per_page: 100
      }));
      commentId = comments.find((comment) => comment.body?.includes(SUMMARY_MARKER))?.id;
    }

    if (commentId) {
      await this.#retry(() => client.rest.issues.updateComment({
        owner: input.owner,
        repo: input.repo,
        comment_id: commentId,
        body
      }));
      return commentId;
    }

    const { data } = await this.#retry(() => client.rest.issues.createComment({
      owner: input.owner,
      repo: input.repo,
      issue_number: input.pullNumber,
      body
    }));
    return data.id;
  }
}

export function renderSummaryMarkdown(summary: ChangeSummary): string {
  const zh = summary.policy?.language === 'zh';
  const files = summary.files.length
    ? summary.files.map((file) => `- \`${file.path}\` — ${file.change}`).join('\n')
    : '- No reviewable files.';
  const reasons = summary.riskReasons.length
    ? summary.riskReasons.map((reason) => `- ${reason}`).join('\n')
    : '- No material risk signal detected in the current scope.';
  const impact = summary.impact
    ? [
        '',
        zh ? '### 影响分析' : '### Impact analysis',
        '',
        `Blast radius: **${summary.impact.level.toUpperCase()} (${summary.impact.score}/100)** — ${summary.impact.changedSymbols} changed symbol(s), ${summary.impact.impactedSymbols} impacted caller(s).`,
        '',
        ...(summary.impact.topPaths.length
          ? summary.impact.topPaths.map(
              (path) =>
                `- \`${path.impactedName}\` → \`${path.changedName}\` (depth ${path.depth}, confidence ${path.score.toFixed(2)})`
            )
          : ['- No caller path was found inside the indexed scope.']),
        '',
        `> Coverage: ${summary.impact.coverageWarning}`
      ].join('\n')
    : '';
  const findings = summary.findings
    ? [
        '',
        zh ? '### 已验证问题' : '### Verified findings',
        '',
        summary.findings.published
          ? `${summary.findings.published} finding(s) have exact added-line evidence and passed the publish threshold.`
          : 'No candidate passed evidence verification and the publish threshold.',
        '',
        ...(summary.findings.items.filter((item) => item.publishable).length
          ? summary.findings.items
              .filter((item) => item.publishable)
              .map(
                (item) =>
                  `- **${item.severity.toUpperCase()} — ${item.title}** at \`${item.path}:${item.line}\` (id: \`${item.fingerprint.slice(0, 12)}\`): ${item.claim}`
              )
          : ['- No publishable finding.']),
        '',
        `Audit: ${summary.findings.candidates} candidate(s), ${summary.findings.verified} verified, ${summary.findings.rejected} rejected.`
      ].join('\n')
    : '';
  const policy = summary.policy
    ? [
        '',
        zh ? '### 仓库策略' : '### Repository policy',
        '',
        `Config: \`${summary.policy.configHash.slice(0, 12)}\` — ${summary.policy.rules} rule(s), ${summary.policy.includedFiles} included file(s), ${summary.policy.excludedFiles} excluded file(s), blocking ${summary.policy.blocking ? 'enabled' : 'disabled'}.`,
        ...(summary.policy.warnings.map((warning) => `- ⚠️ ${warning}`))
      ].join('\n')
    : '';

  return [
    zh ? '## CodeLens AI 审核' : '## CodeLens AI Review',
    '',
    `${zh ? '**变更意图：**' : '**Change intent:**'} ${summary.intent}`,
    '',
    summary.overview,
    '',
    `${zh ? '**风险：**' : '**Risk:**'} ${summary.riskLevel.toUpperCase()}`,
    '',
    reasons,
    '',
    zh ? '### 主要文件' : '### Main files',
    '',
    files,
    impact,
    findings,
    policy,
    '',
    zh
      ? `覆盖范围：${summary.coverage.reviewedFiles}/${summary.coverage.totalFiles} 个文件${summary.coverage.truncated ? '（受预算限制）' : ''}。`
      : `Coverage: ${summary.coverage.reviewedFiles}/${summary.coverage.totalFiles} files${summary.coverage.truncated ? ' (budget limited)' : ''}.`,
    '',
    zh
      ? '_这是面向证据的审核辅助工具，不构成正确性证明。_'
      : '_This is an evidence-oriented review aid, not a proof of correctness._',
    '',
    zh
      ? '问题反馈：在本 PR 评论 `/codelens feedback <finding-id> helpful` 或 `/codelens feedback <finding-id> false-positive`。'
      : 'Finding feedback: comment `/codelens feedback <finding-id> helpful` or `/codelens feedback <finding-id> false-positive` on this PR.'
  ].join('\n');
}
