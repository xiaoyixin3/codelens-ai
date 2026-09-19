import { App } from 'octokit';
import { loadConfig } from '@codelens/config';
import { assessGitHubPermissions } from '@codelens/github';

const config = loadConfig();
if (!config.GITHUB_APP_ID || !config.GITHUB_PRIVATE_KEY) {
  throw new Error(
    'GitHub App preflight requires GITHUB_APP_ID and GITHUB_PRIVATE_KEY. No credentials were sent or logged.'
  );
}

const owner = process.env.GITHUB_TEST_OWNER?.trim();
const repo = process.env.GITHUB_TEST_REPO?.trim();
if (Boolean(owner) !== Boolean(repo)) {
  throw new Error('Set both GITHUB_TEST_OWNER and GITHUB_TEST_REPO, or leave both unset.');
}

const app = new App({
  appId: config.GITHUB_APP_ID,
  privateKey: config.GITHUB_PRIVATE_KEY
});

const authenticated = await app.octokit.rest.apps.getAuthenticated();
if (!authenticated.data) {
  throw new Error('GitHub returned an empty App identity response.');
}
const installations = await app.octokit.paginate(
  app.octokit.rest.apps.listInstallations,
  { per_page: 100 }
);

const result: Record<string, unknown> = {
  status: 'authenticated',
  app: {
    id: authenticated.data.id,
    slug: authenticated.data.slug,
    name: authenticated.data.name
  },
  installations: installations.length,
  targetRepository: 'not_checked'
};

if (owner && repo) {
  const installationResponse = await app.octokit.rest.apps.getRepoInstallation({ owner, repo });
  const installation = installationResponse.data;
  const permissions = installation.permissions as Record<string, string | undefined>;
  const assessment = assessGitHubPermissions(permissions);
  const installationClient = await app.getInstallationOctokit(installation.id);
  const repository = await installationClient.rest.repos.get({ owner, repo });

  result.status = assessment.ok ? 'ready' : 'missing_permissions';
  result.targetRepository = {
    fullName: repository.data.full_name,
    private: repository.data.private,
    installationId: installation.id,
    repositorySelection: installation.repository_selection,
    permissions: {
      ok: assessment.ok,
      missing: assessment.missing
    }
  };
  if (!assessment.ok) process.exitCode = 1;
}

console.log(JSON.stringify(result, null, 2));
