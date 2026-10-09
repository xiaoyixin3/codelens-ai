import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { parse } from 'yaml';

const compose = parse(readFileSync(new URL('../infra/compose.production.yml', import.meta.url), 'utf8'));

describe('production deployment safety defaults', () => {
  it('keeps the opt-in public trial isolated, sandbox-only and Java-semantic enabled', () => {
    const trial = parse(readFileSync(new URL('../infra/compose.local-public-trial.yml', import.meta.url), 'utf8'));
    expect(trial.name).toBe('codelens-java12-public-trial');
    for (const service of ['api', 'worker']) {
      expect(trial.services[service].environment.CODELENS_PUBLIC_TRIAL).toBe('true');
      expect(trial.services[service].environment.CODELENS_PUBLIC_TRIAL_REPOSITORIES).toBe('codelens-ai-lab/codelens-beta-test');
    }
    expect(trial.services.worker.environment.CODELENS_SEMANTIC_ENABLED).toBe('true');
    expect(trial.services.worker.environment.CODELENS_SEMANTIC_REPOSITORIES).toBe('codelens-ai-lab/codelens-beta-test');
    expect(trial.services.api.environment.CODELENS_TRUST_PROXY).toBe('false');
  });
  it('binds the API to host loopback and requires explicit proxy trust', () => {
    expect(compose.services.api.ports).toEqual([
      '${CODELENS_BIND_ADDRESS:-127.0.0.1}:${PORT:-3000}:3000'
    ]);
    expect(compose.services.api.environment.CODELENS_TRUST_PROXY)
      .toBe('${CODELENS_TRUST_PROXY:-false}');
    expect(compose.services.postgres.ports).toBeUndefined();
  });

  it('checks readiness with a request timeout shorter than the container timeout', () => {
    const check = compose.services.api.healthcheck;
    expect(check.test.slice(0, 3)).toEqual(['CMD', 'node', '-e']);
    expect(check.test[3]).toContain('http://127.0.0.1:3000/readyz');
    expect(check.test[3]).toContain('AbortSignal.timeout(4000)');
    expect(check.timeout).toBe('5s');
    expect(check.test[3]).toContain('if(!r.ok)process.exit(1)');
  });

  it('starts only Java services after a successful migration and retains isolation', () => {
    for (const name of ['api', 'worker']) {
      const service = compose.services[name];
      expect(service.command).toEqual(['java', `-Dcodelens.mode=${name}`, '-jar', '/app/codelens-ai.jar']);
      expect(service.depends_on.migrate.condition).toBe('service_completed_successfully');
      expect(service.environment.CODELENS_ENVIRONMENT).toBe('production');
      expect(service.read_only).toBe(true);
      expect(service.cap_drop).toEqual(['ALL']);
      expect(service.security_opt).toEqual(['no-new-privileges:true']);
      expect(service.env_file[0].path).toBe('${CODELENS_ENV_FILE:-../.env}');
      expect(service.environment.CODELENS_PUBLIC_TRIAL).toBe('${CODELENS_PUBLIC_TRIAL:-false}');
      expect(service.environment.CODELENS_PUBLIC_TRIAL_REPOSITORIES).toBe('${CODELENS_PUBLIC_TRIAL_REPOSITORIES:-}');
    }
  });
});
