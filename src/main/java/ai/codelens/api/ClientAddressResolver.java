package ai.codelens.api;

import ai.codelens.config.RuntimeConfig;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

@Component
@Profile("api")
public class ClientAddressResolver {
    private static final Pattern ADDRESS = Pattern.compile("[0-9A-Fa-f:.]{2,64}");
    private final boolean trustProxy;

    public ClientAddressResolver(RuntimeConfig config) {
        trustProxy = config.trustProxy();
    }

    public String resolve(HttpServletRequest request) {
        if (trustProxy) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                String first = forwarded.split(",", 2)[0].trim();
                if (ADDRESS.matcher(first).matches()) return first;
            }
        }
        String remote = request.getRemoteAddr();
        return remote == null || remote.isBlank() ? "unknown" : remote;
    }
}
