# Model provider control plane

The V2 Milestone A control plane lets an operator create, test, update, rotate,
list, and delete model-provider connections without restarting the API.

This milestone uses a deployment-level admin token. It is appropriate for the
self-hosted beta and is intentionally not the final multi-user login experience.
GitHub owner authentication and the setup console replace this transitional
boundary in Milestone C.

## Enable the API

Set both values in the API service environment:

```dotenv
CODELENS_MODEL_ADMIN_TOKEN=<at-least-32-random-characters>
CODELENS_CREDENTIAL_KEY=<standard-base64-encoding-of-32-random-bytes>
```

The API refuses partial configuration. The encryption key is decoded as exactly
32 bytes and used for AES-256-GCM. Keep both values in a deployment secret manager,
not in the repository or Docker image.

Private or local HTTP endpoints are disabled by default. A self-hosted operator
can explicitly enable them with:

```dotenv
CODELENS_ALLOW_PRIVATE_MODEL_ENDPOINTS=true
```

Public endpoints must use HTTPS. Redirects are rejected, and resolved private,
loopback, link-local, multicast, and unspecified addresses are blocked unless the
private-endpoint option is enabled.

## Authentication and tenancy

Every request uses these headers:

```http
Authorization: Bearer <CODELENS_MODEL_ADMIN_TOKEN>
X-CodeLens-Installation-ID: <GitHub App installation ID>
X-CodeLens-Actor: <optional audit identity>
```

The installation header scopes every database read and mutation. Credentials are
encrypted with authenticated context containing both the installation ID and the
connection ID, so moving ciphertext between tenants or rows makes decryption fail.

## Endpoints

```text
GET    /api/v2/providers
POST   /api/v2/providers
GET    /api/v2/providers/{id}
PATCH  /api/v2/providers/{id}
POST   /api/v2/providers/{id}/rotate-secret
POST   /api/v2/providers/{id}/test
DELETE /api/v2/providers/{id}
```

Create request:

```json
{
  "name": "primary-review-model",
  "providerKind": "openai_compatible",
  "baseUrl": "https://provider.example/v1",
  "apiKey": "secret value",
  "defaultModel": "review-model",
  "enabled": true,
  "timeoutSeconds": 45,
  "maxRetries": 2
}
```

Supported provider kinds:

- `openai_compatible` — `POST {baseUrl}/chat/completions`;
- `openai_responses` — `POST {baseUrl}/responses`;
- `anthropic` — `POST {baseUrl}/v1/messages`.

If `baseUrl` is omitted, OpenAI-compatible and Responses connections default to
`https://api.openai.com/v1`; Anthropic defaults to `https://api.anthropic.com`.

Responses never include the API key or encrypted credential. They expose only an
irreversible fingerprint, for example `sha256:42f4a67e12ab`.

## Connection testing

Testing sends a minimal structured-output request using the stored credential and
configured model. It records status, bounded redacted detail, duration, HTTP status,
provider request ID when supplied, and an audit event. Transient network errors,
HTTP 429, and HTTP 5xx responses use the configured bounded retry policy.

The test result does not enable repository routing. Milestone B adds repository
model policies and uses these connections in review jobs.

## Rotation and deletion

Secret rotation replaces the ciphertext and fingerprint and resets connection-test
status to `untested`. Deletion removes the connection while keeping an audit event;
repository policies automatically clear references to the deleted connection.

Changing the deployment encryption key requires a dedicated re-encryption operation.
Do not replace `CODELENS_CREDENTIAL_KEY` while provider connections exist until that
operation is available.
