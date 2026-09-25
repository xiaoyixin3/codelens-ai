package ai.codelens.api;

import ai.codelens.config.RuntimeConfig;
import ai.codelens.contracts.Models;
import ai.codelens.security.WebhookSecurity;
import ai.codelens.store.JdbcStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebhookControllerTest {
    @Test
    void verifiesAndQueuesPullRequestWebhook() {
        JdbcStore store = mock(JdbcStore.class);
        RuntimeConfig config = config();
        WebhookController controller = new WebhookController(store, config, new ObjectMapper());
        HttpServletRequest request = mock(HttpServletRequest.class); when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        byte[] body = """
                {"action":"opened","installation":{"id":42},"repository":{"id":99,"name":"demo","owner":{"login":"octo"}},
                "pull_request":{"number":7,"base":{"sha":"base1234"},"head":{"sha":"head1234"}}}
                """.getBytes(StandardCharsets.UTF_8);
        when(store.claimDelivery(any(), any(), any(), any())).thenReturn(true);
        Models.ReviewRun run = new Models.ReviewRun("run-1", 99, 7, "base1234", "head1234", "queued",
                Models.PIPELINE_VERSION, Models.DEFAULT_CONFIG_HASH, "webhook", "automatic", null);
        when(store.createOrGetAndEnqueue(any(), any())).thenReturn(new JdbcStore.CreatedRun(run, true));

        var response = controller.webhook(body, WebhookSecurity.sign(body, config.webhookSecret()), "delivery-1", "pull_request", request);
        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertEquals("queued", response.getBody().get("status"));
        assertEquals("run-1", response.getBody().get("reviewRunId"));
        verify(store).markDeliveryProcessed("delivery-1", "");
    }

    @Test
    void rejectsInvalidSignatureBeforeDatabaseAccess() {
        JdbcStore store = mock(JdbcStore.class);
        WebhookController controller = new WebhookController(store, config(), new ObjectMapper());
        HttpServletRequest request = mock(HttpServletRequest.class); when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        var response = controller.webhook("{}".getBytes(StandardCharsets.UTF_8), "sha256=bad", "delivery-1", "pull_request", request);
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    private static RuntimeConfig config() {
        return new RuntimeConfig("api", "postgres://codelens:codelens@localhost:5432/codelens", "123", "",
                "a-strong-test-secret-value", 300, 100, 120_000, 8, 500_000,
                "", "", "", "", "", "", 4, 250_000, 2, "infra/migrations", "", "", false);
    }
}
