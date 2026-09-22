# CodeLens AI V2: LLM provider platform

Status: proposed  
Target: V2.0  
Primary runtime: Go

## Product outcome

V2 turns the existing operator-only model configuration into a product feature.
An installation owner can connect a model provider, verify the connection, choose
models for a repository, set a spend ceiling, and see whether each review used the
model or safely fell back to deterministic analysis.

The V2 promise is:

> Connect one model once, then use it safely across selected repositories without
> editing environment variables or restarting CodeLens.

## What already exists

V1 already provides a useful foundation:

- an OpenAI-compatible Chat Completions client;
- primary/fallback routing;
- structured JSON validation;
- secret redaction before transmission;
- per-review call and input-size limits;
- request telemetry without full prompt or completion storage;
- deterministic fallback when a provider is missing or fails;
- exact diff-line verification before model findings are published.

V1 configuration is global and deployment-owned. `LLM_BASE_URL`, `LLM_API_KEY`,
and `LLM_MODEL` must be set on the server. There is no user-facing connection
flow, repository-specific model policy, encrypted credential store, connection
test, cost calculation, or model-health view.

## V2 scope

### 1. Provider connections

Support these connection types in the first release:

1. OpenAI-compatible endpoints, covering OpenAI-compatible hosted providers.
2. OpenAI Responses API through a native adapter.
3. Anthropic Messages API through a native adapter.
4. Local or private OpenAI-compatible endpoints such as Ollama.

Gemini can initially use its official OpenAI-compatible endpoint. A native Gemini
adapter is deferred until CodeLens needs provider-specific tools or capabilities.

Each connection contains:

- display name;
- provider kind;
- base URL, when applicable;
- encrypted API credential;
- default model;
- enabled state;
- timeout and retry policy;
- last connection-test result and timestamp.

### 2. Repository model policy

An installation owner can configure each repository with one of three modes:

- `deterministic`: never send repository content to a model;
- `assisted`: deterministic analysis plus model summary and risk review;
- `required`: fail the model portion visibly instead of silently treating it as
  an ordinary deterministic review.

Each repository selects a primary connection/model and an optional fallback.
The existing `.codelens.yml` file continues to control review scope and publishing,
while server-side settings control credentials, cost, and data destination.
Repository files must never contain API keys.

### 3. Model routing

Introduce a provider-neutral interface:

```go
type ModelProvider interface {
    TestConnection(ctx context.Context, model string) error
    GenerateStructured(ctx context.Context, request StructuredRequest) (StructuredResponse, error)
}
```

The review engine asks a router for a capability, not for a named vendor:

```text
review job
  -> repository model policy
  -> capability router
  -> primary provider
  -> fallback provider
  -> deterministic fallback
```

Routing rules remain bounded and auditable. V2 does not allow the model to execute
repository code, issue arbitrary network requests, write to the repository, or
publish unverified findings.

### 4. Setup and operations UI

Add a small authenticated installation console with four pages:

1. **Providers** — add, test, rotate, disable, or delete a connection.
2. **Repositories** — select review mode, primary model, and fallback model.
3. **Usage** — calls, tokens, estimated cost, latency, failure rate, and fallback rate.
4. **Review detail** — model used, policy decision, degradation reason, and verified
   findings; prompts and raw repository content remain hidden.

The first-run flow is:

```text
Install GitHub App
  -> connect provider
  -> test connection
  -> choose repositories
  -> choose assisted/deterministic mode
  -> run a sample review
  -> show result and estimated cost
```

### 5. API surface

All mutation endpoints require an authenticated installation owner and CSRF
protection when used from the browser.

```text
GET    /api/v2/providers
POST   /api/v2/providers
POST   /api/v2/providers/{id}/test
PATCH  /api/v2/providers/{id}
POST   /api/v2/providers/{id}/rotate-secret
DELETE /api/v2/providers/{id}

GET    /api/v2/repositories
GET    /api/v2/repositories/{repositoryId}/model-policy
PUT    /api/v2/repositories/{repositoryId}/model-policy

GET    /api/v2/usage/summary
GET    /api/v2/reviews/{reviewRunId}/model-trace
```

API responses never return credential plaintext. A successful secret write returns
only a fingerprint such as `sk-...9f2a` and the rotation timestamp.

## Data model

V2 first makes the existing implicit GitHub tenancy explicit:

### `github_installations`

Stores the GitHub App installation ID, account identity/type, active state, and
installation timestamps. This is the authorization and data-isolation boundary.

### `github_repositories`

Stores the GitHub repository ID, installation ID, owner/name, selected state, and
last synchronization timestamp. Existing review rows continue to use the immutable
GitHub repository ID and can be joined to this table.

### `provider_connections`

| Column | Purpose |
| --- | --- |
| `id` | UUID |
| `installation_id` | GitHub App installation owner |
| `name` | User-visible unique name per installation |
| `provider_kind` | `openai_compatible`, `openai_responses`, `anthropic` |
| `base_url` | Validated HTTPS URL; loopback/private addresses allowed only in self-hosted mode |
| `credential_ciphertext` | Envelope-encrypted credential |
| `credential_key_version` | Key rotation support |
| `credential_fingerprint` | Safe display and audit value |
| `default_model` | Default model identifier |
| `enabled` | Routing eligibility |
| `last_test_status` | `untested`, `succeeded`, `failed` |
| `last_tested_at` | Last explicit test |
| `created_at`, `updated_at` | Audit timestamps |

### `repository_model_policies`

| Column | Purpose |
| --- | --- |
| `github_repository_id` | Repository key |
| `installation_id` | Authorization boundary |
| `mode` | `deterministic`, `assisted`, `required` |
| `primary_connection_id` | Preferred provider |
| `primary_model` | Optional override |
| `fallback_connection_id` | Optional fallback |
| `fallback_model` | Optional override |
| `monthly_budget_microusd` | Hard application budget |
| `max_calls_per_review` | Per-review call ceiling |
| `max_input_tokens_per_review` | Per-review input ceiling |
| `updated_by`, `updated_at` | Audit fields |

### Changes to `llm_calls`

Add:

- `connection_id`;
- `installation_id`;
- `github_repository_id`;
- `provider_request_id` when available;
- `estimated_cost_microusd`;
- `fallback_reason`;
- `prompt_version`;
- `response_schema_version`.

No raw API key, complete prompt, completion, or unredacted repository text is stored.

## Security boundaries

- Use envelope encryption: a deployment master key protects per-credential data keys.
- Keep decrypted credentials in memory only for the duration of a provider request.
- Validate custom base URLs and block metadata endpoints and unsafe redirects.
- Make private-network endpoints an explicit self-hosted-only option.
- Rate-limit provider testing and settings mutations.
- Record credential creation, test, rotation, disable, and deletion as audit events.
- Never expose a provider error body without redaction and length limits.
- Preserve the existing evidence verifier as the final publication gate.
- Preserve deterministic review when an assisted provider fails.

## Cost and reliability

V2 uses two independent controls:

1. application controls: per-review call/token limits and per-repository monthly budget;
2. provider controls: account-level hard budgets and alerts.

The provider catalog stores model pricing as versioned operator data. Cost is marked
`estimated` because providers may apply caching, batch, or tier adjustments. Unknown
models still record tokens but display cost as unavailable.

Operational targets for V2 beta:

- provider test p95 under 10 seconds;
- model-assisted review success at least 95%;
- deterministic fallback completes at least 99% of eligible provider failures;
- zero plaintext credentials in logs, telemetry, API responses, and database dumps;
- every model-generated published finding passes the existing evidence verifier.

## Delivery plan

### Milestone A — provider control plane

- add explicit GitHub installation and repository tenancy tables;
- add provider and repository-policy migrations;
- add encrypted credential service;
- extract provider-neutral interface and keep the current adapter working;
- add CRUD and connection-test API;
- make environment variables a legacy deployment-level default.

Exit condition: a connection can be created and tested without restarting services.

### Milestone B — repository routing

- resolve model policy per review job;
- add primary/fallback routing by repository;
- persist routing and degradation decisions;
- add budget enforcement and estimated cost.

Exit condition: two repositories can safely use different providers or modes.

### Milestone C — usable console

- add GitHub-owner authentication and installation authorization;
- build Providers, Repositories, Usage, and Review detail pages;
- add first-run sample review and actionable error messages.

Exit condition: a design partner can complete setup without editing files or server
environment variables.

### Milestone D — quality and release

- run provider contract tests against at least one native and one compatible endpoint;
- run failure drills for timeout, rate limit, invalid JSON, budget exhaustion, and
  credential rotation;
- compare assisted versus deterministic review quality on the approved replay set;
- document data processing and model-provider disclosure.

Exit condition: the V2 beta gates meet the reliability and privacy targets above.

## Explicitly deferred

- autonomous code changes or merge actions;
- arbitrary tools, shell execution, or repository write access for models;
- user-authored prompt templates in the first V2 release;
- full agent loops;
- native adapters for every provider;
- storing complete prompts or completions for debugging.

These are deferred because the product's value is trustworthy review, not autonomous
coding. Provider choice should improve analysis quality without weakening evidence,
privacy, or operational control.
