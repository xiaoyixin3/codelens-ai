package ai.codelens.api;

import ai.codelens.config.RuntimeConfig;
import ai.codelens.credentials.CredentialVault;
import ai.codelens.security.Redactor;
import ai.codelens.store.ProviderStore;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
@Profile("api")
@RequestMapping("/api/v2/providers")
public class ProviderController {
    private static final Set<String> KINDS = Set.of("openai_compatible", "openai_responses", "anthropic");
    private final ProviderStore store; private final RuntimeConfig config; private final CredentialVault vault;
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    public ProviderController(ProviderStore store, RuntimeConfig config) {
        this.store = store; this.config = config;
        this.vault = config.credentialKey().isBlank() ? null : new CredentialVault(config.credentialKey());
    }

    @GetMapping
    public ResponseEntity<?> list(@RequestHeader Map<String,String> headers) {
        Auth auth = authorize(headers); if (auth.error() != null) return auth.error();
        return ResponseEntity.ok(Map.of("providers", store.list(auth.installationId())));
    }
    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable String id, @RequestHeader Map<String,String> headers) {
        Auth auth = authorize(headers); if (auth.error() != null) return auth.error();
        try { return ResponseEntity.ok(store.get(auth.installationId(), id)); } catch (RuntimeException error) { return storeError(error); }
    }
    @PostMapping
    public ResponseEntity<?> create(@RequestBody CreateRequest request, @RequestHeader Map<String,String> headers) {
        Auth auth = authorize(headers); if (auth.error() != null) return auth.error();
        String kind = trim(request.providerKind()); String base = trim(request.baseUrl());
        if (base.isBlank()) base = defaultBase(kind);
        String invalid = validate(trim(request.name()), kind, base, trim(request.apiKey()), trim(request.defaultModel()),
                request.timeoutSeconds() == null ? 45 : request.timeoutSeconds(), request.maxRetries() == null ? 2 : request.maxRetries(), config.allowPrivateModels());
        if (invalid != null) return invalid(invalid);
        String id = UUID.randomUUID().toString(); String secret = trim(request.apiKey());
        try {
            var created = store.create(new ProviderStore.CreateInput(id, auth.installationId(), trim(request.name()), kind, base,
                    vault.seal(secret, context(auth.installationId(), id)), CredentialVault.KEY_VERSION, CredentialVault.fingerprint(secret),
                    trim(request.defaultModel()), request.enabled() == null || request.enabled(), request.timeoutSeconds() == null ? 45 : request.timeoutSeconds(),
                    request.maxRetries() == null ? 2 : request.maxRetries(), auth.actor()));
            return ResponseEntity.status(HttpStatus.CREATED).body(created);
        } catch (RuntimeException error) { return storeError(error); }
    }
    @PatchMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable String id, @RequestBody UpdateRequest request, @RequestHeader Map<String,String> headers) {
        Auth auth = authorize(headers); if (auth.error() != null) return auth.error();
        try {
            var current = store.get(auth.installationId(), id);
            String name = request.name() == null ? current.name() : trim(request.name());
            String base = request.baseUrl() == null ? current.baseUrl() : trim(request.baseUrl());
            String model = request.defaultModel() == null ? current.defaultModel() : trim(request.defaultModel());
            boolean enabled = request.enabled() == null ? current.enabled() : request.enabled();
            int timeout = request.timeoutSeconds() == null ? current.timeoutSeconds() : request.timeoutSeconds();
            int retries = request.maxRetries() == null ? current.maxRetries() : request.maxRetries();
            String invalid = validate(name, current.providerKind(), base, "unchanged", model, timeout, retries, config.allowPrivateModels());
            if (invalid != null) return invalid(invalid);
            return ResponseEntity.ok(store.update(new ProviderStore.UpdateInput(auth.installationId(), id, name, base, model, enabled, timeout, retries, auth.actor())));
        } catch (RuntimeException error) { return storeError(error); }
    }
    @PostMapping("/{id}/rotate-secret")
    public ResponseEntity<?> rotate(@PathVariable String id, @RequestBody RotateRequest request, @RequestHeader Map<String,String> headers) {
        Auth auth = authorize(headers); if (auth.error() != null) return auth.error(); String secret = trim(request.apiKey());
        if (secret.isBlank() || secret.length() > 16_384) return invalid("apiKey is required and must not exceed 16 KB");
        try {
            store.get(auth.installationId(), id);
            return ResponseEntity.ok(store.rotate(auth.installationId(), id, vault.seal(secret, context(auth.installationId(), id)),
                    CredentialVault.fingerprint(secret), CredentialVault.KEY_VERSION, auth.actor()));
        } catch (RuntimeException error) { return storeError(error); }
    }
    @PostMapping("/{id}/test")
    public ResponseEntity<?> test(@PathVariable String id, @RequestHeader Map<String,String> headers) {
        Auth auth = authorize(headers); if (auth.error() != null) return auth.error();
        try {
            var provider = store.get(auth.installationId(), id);
            String secret = vault.open(provider.credentialCiphertext(), context(auth.installationId(), id));
            TestResult result = testConnection(provider, secret);
            var updated = store.recordTest(auth.installationId(), id, result.ok() ? "succeeded" : "failed", result.detail(), auth.actor());
            return ResponseEntity.status(result.ok() ? HttpStatus.OK : HttpStatus.UNPROCESSABLE_ENTITY)
                    .body(result.ok() ? Map.of("status", "succeeded", "provider", updated, "test", Map.of("httpStatus", result.httpStatus()))
                            : Map.of("error", "provider_test_failed", "detail", result.detail(), "provider", updated));
        } catch (RuntimeException error) { return storeError(error); }
    }
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable String id, @RequestHeader Map<String,String> headers) {
        Auth auth = authorize(headers); if (auth.error() != null) return auth.error();
        try { store.delete(auth.installationId(), id, auth.actor()); return ResponseEntity.noContent().build(); }
        catch (RuntimeException error) { return storeError(error); }
    }

    private Auth authorize(Map<String,String> headers) {
        if (vault == null || config.modelAdminToken().isBlank()) return new Auth(0,"",ResponseEntity.notFound().build());
        String authorization = header(headers, "authorization");
        String token = authorization.startsWith("Bearer ") ? authorization.substring(7).trim() : "";
        if (token.isBlank() || !CredentialVault.tokenMatches(token, config.modelAdminToken())) return new Auth(0,"",error(HttpStatus.UNAUTHORIZED,"invalid_admin_token"));
        long installation;
        try { installation = Long.parseLong(header(headers, "x-codelens-installation-id")); }
        catch (NumberFormatException exception) { return new Auth(0,"",error(HttpStatus.BAD_REQUEST,"invalid_installation_id")); }
        if (installation < 1) return new Auth(0,"",error(HttpStatus.BAD_REQUEST,"invalid_installation_id"));
        String actor = trim(header(headers, "x-codelens-actor")); if (actor.isBlank()) actor = "model-admin-token";
        return new Auth(installation, actor.substring(0, Math.min(200, actor.length())), null);
    }
    static String validate(String name, String kind, String base, String key, String model, int timeout, int retries, boolean allowPrivate) {
        if (name.isBlank() || name.length() > 100) return "name must contain between 1 and 100 characters";
        if (!KINDS.contains(kind)) return "providerKind is unsupported";
        if (key.isBlank() || key.length() > 16_384) return "apiKey is required and must not exceed 16 KB";
        if (model.isBlank() || model.length() > 200) return "defaultModel must contain between 1 and 200 characters";
        if (timeout < 1 || timeout > 120) return "timeoutSeconds must be between 1 and 120";
        if (retries < 0 || retries > 5) return "maxRetries must be between 0 and 5";
        try {
            URI uri = URI.create(base);
            if (!uri.getScheme().equals("https") || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null) return "baseUrl must be an absolute HTTPS URL";
            if (!allowPrivate) for (InetAddress address : InetAddress.getAllByName(uri.getHost()))
                if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress() || address.isSiteLocalAddress() || address.isMulticastAddress()) return "baseUrl must not resolve to a private address";
        } catch (Exception exception) { return "baseUrl is invalid or cannot be resolved"; }
        return null;
    }
    private TestResult testConnection(ProviderStore.ProviderConnection provider, String secret) {
        try {
            String path = provider.providerKind().equals("openai_responses") ? "/responses" : provider.providerKind().equals("anthropic") ? "/v1/messages" : "/chat/completions";
            String body = provider.providerKind().equals("openai_responses")
                    ? "{\"model\":\"" + json(provider.defaultModel()) + "\",\"input\":\"Reply OK\",\"max_output_tokens\":1}"
                    : provider.providerKind().equals("anthropic")
                    ? "{\"model\":\"" + json(provider.defaultModel()) + "\",\"max_tokens\":1,\"messages\":[{\"role\":\"user\",\"content\":\"Reply OK\"}]}"
                    : "{\"model\":\"" + json(provider.defaultModel()) + "\",\"max_tokens\":1,\"messages\":[{\"role\":\"user\",\"content\":\"Reply OK\"}]}";
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(provider.baseUrl().replaceFirst("/+$", "") + path))
                    .timeout(Duration.ofSeconds(provider.timeoutSeconds())).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body));
            if (provider.providerKind().equals("anthropic")) builder.header("x-api-key", secret).header("anthropic-version", "2023-06-01");
            else builder.header("Authorization", "Bearer " + secret);
            int status = http.send(builder.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
            return new TestResult(status >= 200 && status < 300, status, status >= 200 && status < 300 ? "" : "provider returned HTTP " + status);
        } catch (Exception exception) { return new TestResult(false, 0, truncate(Redactor.redact(exception.getMessage()), 500)); }
    }
    private static String defaultBase(String kind) { return kind.equals("anthropic") ? "https://api.anthropic.com" : "https://api.openai.com/v1"; }
    private static String context(long installation, String id) { return "installation:" + installation + ":connection:" + id; }
    private static String trim(String value) { return value == null ? "" : value.trim(); }
    private static String header(Map<String,String> headers, String name) { return headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name)).map(Map.Entry::getValue).findFirst().orElse(""); }
    private static String json(String value) { return value.replace("\\", "\\\\").replace("\"", "\\\""); }
    private static String truncate(String value, int max) { value = value == null ? "provider request failed" : value; return value.substring(0, Math.min(max, value.length())); }
    private static ResponseEntity<Map<String,String>> invalid(String detail) { return ResponseEntity.unprocessableEntity().body(Map.of("error","invalid_provider","detail",detail)); }
    private static ResponseEntity<Map<String,String>> error(HttpStatus status, String code) { return ResponseEntity.status(status).body(Map.of("error",code)); }
    private static ResponseEntity<?> storeError(RuntimeException failure) {
        if (failure instanceof EmptyResultDataAccessException) return error(HttpStatus.NOT_FOUND,"provider_not_found");
        if (failure instanceof DataIntegrityViolationException && failure.getMessage() != null && failure.getMessage().contains("provider_connections_installation_id_name_key")) return error(HttpStatus.CONFLICT,"provider_name_conflict");
        return error(HttpStatus.INTERNAL_SERVER_ERROR,"internal_error");
    }
    public record CreateRequest(String name, String providerKind, String baseUrl, String apiKey, String defaultModel, Boolean enabled, Integer timeoutSeconds, Integer maxRetries) {}
    public record UpdateRequest(String name, String baseUrl, String defaultModel, Boolean enabled, Integer timeoutSeconds, Integer maxRetries) {}
    public record RotateRequest(String apiKey) {}
    private record Auth(long installationId, String actor, ResponseEntity<?> error) {}
    private record TestResult(boolean ok, int httpStatus, String detail) {}
}
