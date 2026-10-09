package ai.codelens.api;

import ai.codelens.config.RuntimeConfig;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SecurityHeadersFilterTest {
    @Test
    void addsNoStoreAndHttpsSecurityHeadersBehindATrustedProxy() throws Exception {
        RuntimeConfig config = mock(RuntimeConfig.class);
        when(config.trustProxy()).thenReturn(true);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/healthz");
        request.addHeader("X-Forwarded-Proto", "https");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new SecurityHeadersFilter(config).doFilter(request, response, new MockFilterChain());

        assertEquals("no-store", response.getHeader("Cache-Control"));
        assertEquals("nosniff", response.getHeader("X-Content-Type-Options"));
        assertEquals("DENY", response.getHeader("X-Frame-Options"));
        assertEquals("max-age=31536000; includeSubDomains", response.getHeader("Strict-Transport-Security"));
    }
}
