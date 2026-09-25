package ai.codelens.api;

import ai.codelens.config.RuntimeConfig;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Profile("api")
public class ProviderRateLimitFilter extends OncePerRequestFilter {
    private final int max; private final Map<String, Window> clients = new ConcurrentHashMap<>();
    public ProviderRateLimitFilter(RuntimeConfig config) { this.max = Math.max(1, config.webhookRateLimit()); }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!request.getRequestURI().startsWith("/api/v2/providers")) { chain.doFilter(request, response); return; }
        String forwarded = request.getHeader("X-Forwarded-For");
        String address = forwarded == null || forwarded.isBlank() ? request.getRemoteAddr() : forwarded.split(",", 2)[0].trim();
        long minute = System.currentTimeMillis() / 60_000;
        Window window = clients.compute(address, (ignored, old) -> old == null || old.minute() != minute
                ? new Window(minute, 1) : new Window(minute, old.count() + 1));
        if (window.count() > max) {
            response.setStatus(429); response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"rate_limit_exceeded\"}\n"); return;
        }
        if (clients.size() > 10_000) clients.entrySet().removeIf(item -> item.getValue().minute() < minute - 1);
        chain.doFilter(request, response);
    }
    private record Window(long minute, int count) {}
}
