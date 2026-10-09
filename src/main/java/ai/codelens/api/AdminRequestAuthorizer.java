package ai.codelens.api;

import ai.codelens.config.RuntimeConfig;
import ai.codelens.credentials.CredentialVault;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Shared installation-scoped authorization for explicit administrative API actions. */
@Component
@Profile("api")
public final class AdminRequestAuthorizer {
    private final RuntimeConfig config;

    public AdminRequestAuthorizer(RuntimeConfig config) {
        this.config = config;
    }

    public Result authorize(Map<String, String> headers) {
        if (config.modelAdminToken().isBlank()) return new Result(0, "", ResponseEntity.notFound().build());
        String authorization = header(headers, "authorization");
        String token = authorization.startsWith("Bearer ") ? authorization.substring(7).trim() : "";
        if (token.isBlank() || !CredentialVault.tokenMatches(token, config.modelAdminToken())) {
            return new Result(0, "", error(HttpStatus.UNAUTHORIZED, "invalid_admin_token"));
        }
        long installation;
        try { installation = Long.parseLong(header(headers, "x-codelens-installation-id")); }
        catch (NumberFormatException exception) {
            return new Result(0, "", error(HttpStatus.BAD_REQUEST, "invalid_installation_id"));
        }
        if (installation < 1) return new Result(0, "", error(HttpStatus.BAD_REQUEST, "invalid_installation_id"));
        String actor = trim(header(headers, "x-codelens-actor"));
        if (actor.isBlank()) actor = "admin-token";
        return new Result(installation, actor.substring(0, Math.min(200, actor.length())), null);
    }

    private static String header(Map<String, String> headers, String name) {
        return headers.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .map(Map.Entry::getValue).findFirst().orElse("");
    }

    private static String trim(String value) { return value == null ? "" : value.trim(); }
    private static ResponseEntity<Map<String, String>> error(HttpStatus status, String code) {
        return ResponseEntity.status(status).body(Map.of("error", code));
    }

    public record Result(long installationId, String actor, ResponseEntity<?> error) {}
}
