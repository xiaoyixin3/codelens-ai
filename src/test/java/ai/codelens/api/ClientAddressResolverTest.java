package ai.codelens.api;

import ai.codelens.config.RuntimeConfig;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ClientAddressResolverTest {
    @Test
    void ignoresSpoofedForwardingHeadersUnlessProxyTrustIsExplicit() {
        RuntimeConfig config = mock(RuntimeConfig.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(config.trustProxy()).thenReturn(false);
        when(request.getHeader("X-Forwarded-For")).thenReturn("203.0.113.10");
        when(request.getRemoteAddr()).thenReturn("10.0.0.5");

        assertEquals("10.0.0.5", new ClientAddressResolver(config).resolve(request));
    }

    @Test
    void acceptsOnlyAnAddressShapedFirstHopFromATrustedProxy() {
        RuntimeConfig config = mock(RuntimeConfig.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(config.trustProxy()).thenReturn(true);
        when(request.getHeader("X-Forwarded-For")).thenReturn("203.0.113.10, 10.0.0.5");
        when(request.getRemoteAddr()).thenReturn("10.0.0.5");

        assertEquals("203.0.113.10", new ClientAddressResolver(config).resolve(request));

        when(request.getHeader("X-Forwarded-For")).thenReturn("spoofed.example");
        assertEquals("10.0.0.5", new ClientAddressResolver(config).resolve(request));
    }
}
