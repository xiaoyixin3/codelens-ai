package ai.codelens.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Set;

@Component
@Profile("api")
public class WebhookBodyLimitFilter extends OncePerRequestFilter {
    static final int MAX_BYTES = 2 * 1024 * 1024;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        boolean webhook = request.getMethod().equals("POST") && request.getRequestURI().equals("/webhooks/github");
        boolean provider = Set.of("POST", "PATCH").contains(request.getMethod()) && request.getRequestURI().startsWith("/api/v2/providers");
        if (!webhook && !provider) {
            chain.doFilter(request, response); return;
        }
        int limit = webhook ? MAX_BYTES : 64 * 1024;
        byte[] body = request.getInputStream().readNBytes(limit + 1);
        if (body.length > limit) {
            response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"payload_too_large\"}\n");
            return;
        }
        chain.doFilter(new CachedRequest(request, body), response);
    }

    private static final class CachedRequest extends HttpServletRequestWrapper {
        private final byte[] body;
        private CachedRequest(HttpServletRequest request, byte[] body) { super(request); this.body = body; }
        @Override public int getContentLength() { return body.length; }
        @Override public long getContentLengthLong() { return body.length; }
        @Override public ServletInputStream getInputStream() {
            ByteArrayInputStream input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override public int read() { return input.read(); }
                @Override public boolean isFinished() { return input.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener listener) {
                    try { if (isFinished()) listener.onAllDataRead(); else listener.onDataAvailable(); }
                    catch (IOException exception) { listener.onError(exception); }
                }
            };
        }
    }
}
