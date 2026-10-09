package ai.codelens.api;

import ai.codelens.config.RuntimeConfig;
import ai.codelens.contracts.Models;
import ai.codelens.migration.MigrationSchemaVerifier;
import ai.codelens.security.WebhookSecurity;
import ai.codelens.store.JdbcStore;
import ai.codelens.store.DeliveryConflictException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class WebhookControllerTest {
    @Test
    void publicTrialIgnoresOtherRepositoriesBeforeAnyDatabaseAccess() {
        JdbcStore store = mock(JdbcStore.class);
        RuntimeConfig config = spy(config());
        doReturn(true).when(config).publicTrial();
        doReturn(false).when(config).permitsTrialRepository("octo", "demo");
        var controller = new WebhookController(store, config, new ObjectMapper(), readySchema(), new ClientAddressResolver(config));
        var request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        byte[] body = "{\"repository\":{\"owner\":{\"login\":\"octo\"},\"name\":\"demo\"}}".getBytes(StandardCharsets.UTF_8);
        var response = controller.webhook(body, WebhookSecurity.sign(body, config.webhookSecret()), "trial-delivery", "pull_request", request);
        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertEquals("ignored_outside_public_trial", response.getBody().get("status"));
        verifyNoInteractions(store);
    }
    @Test
    void verifiesAndQueuesPullRequestWebhook() {
        JdbcStore store = mock(JdbcStore.class);
        RuntimeConfig config = config();
        WebhookController controller = new WebhookController(store, config, new ObjectMapper(), readySchema(), new ClientAddressResolver(config));
        HttpServletRequest request = mock(HttpServletRequest.class); when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        byte[] body = """
                {"action":"opened","installation":{"id":42},"repository":{"id":99,"name":"demo","owner":{"login":"octo"}},
                "pull_request":{"number":7,"base":{"sha":"base1234"},"head":{"sha":"head1234"}}}
                """.getBytes(StandardCharsets.UTF_8);
        when(store.processDelivery(any(), any())).thenAnswer(call -> new JdbcStore.ProcessedDelivery(false,
                call.<Supplier<JdbcStore.DeliveryResult>>getArgument(1).get()));
        Models.ReviewRun run = new Models.ReviewRun("run-1", 99, 7, "base1234", "head1234", "queued",
                Models.PIPELINE_VERSION, Models.DEFAULT_CONFIG_HASH, "webhook", "automatic", null);
        when(store.createOrGetAndEnqueue(any(), any())).thenReturn(new JdbcStore.CreatedRun(run, true));

        var response = controller.webhook(body, WebhookSecurity.sign(body, config.webhookSecret()), "delivery-1", "pull_request", request);
        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertEquals("queued", response.getBody().get("status"));
        assertEquals("run-1", response.getBody().get("reviewRunId"));
        verify(store).processDelivery(any(), any());
        verify(store, never()).recordDeliveryFailure(any(), any());
    }

    @Test
    void rejectsInvalidSignatureBeforeDatabaseAccess() {
        JdbcStore store = mock(JdbcStore.class);
        RuntimeConfig config = config();
        WebhookController controller = new WebhookController(store, config, new ObjectMapper(), readySchema(), new ClientAddressResolver(config));
        HttpServletRequest request = mock(HttpServletRequest.class); when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        var response = controller.webhook("{}".getBytes(StandardCharsets.UTF_8), "sha256=bad", "delivery-1", "pull_request", request);
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        verifyNoInteractions(store);
    }

    @Test
    void duplicateReturnsTheCommittedRunWithoutRunningTheHandlerAgain() {
        JdbcStore store = mock(JdbcStore.class); RuntimeConfig config = config();
        when(store.processDelivery(any(), any())).thenReturn(new JdbcStore.ProcessedDelivery(true,
                new JdbcStore.DeliveryResult("queued", "committed-run")));
        var response = deliver(store, config, "{\"action\":\"opened\"}");
        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertEquals("duplicate", response.getBody().get("status"));
        assertEquals("committed-run", response.getBody().get("reviewRunId"));
        verify(store, never()).createOrGetAndEnqueue(any(), any());
    }

    @Test
    void failedTransactionReturnsAnErrorAndRecordsOnlyABestEffortAudit() {
        JdbcStore store = mock(JdbcStore.class); RuntimeConfig config = config();
        when(store.processDelivery(any(), any())).thenThrow(new IllegalStateException("database failure"));
        var response = deliver(store, config, "{\"action\":\"opened\"}");
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        verify(store).recordDeliveryFailure(any(), eq("database failure"));
    }

    @Test
    void deliveryPayloadConflictDoesNotOverwriteTheOriginalAudit() {
        JdbcStore store = mock(JdbcStore.class); RuntimeConfig config = config();
        when(store.processDelivery(any(), any())).thenThrow(new DeliveryConflictException());
        var response = deliver(store, config, "{\"action\":\"opened\"}");
        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        verify(store, never()).recordDeliveryFailure(any(), any());
    }

    @Test
    void rejectsEmptyOrNonObjectJsonBeforeDeliveryWrites() {
        for (String input : new String[]{"", "null", "[]", "42"}) {
            JdbcStore store = mock(JdbcStore.class);
            var response = deliver(store, config(), input);
            assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
            verifyNoInteractions(store);
        }
    }

    @Test
    void historicalRerunCanSeedItsCheckWithoutOverwritingAWorkerPublication() {
        JdbcStore store = mock(JdbcStore.class); RuntimeConfig config = config();
        when(store.processDelivery(any(), any())).thenAnswer(call -> new JdbcStore.ProcessedDelivery(false,
                call.<Supplier<JdbcStore.DeliveryResult>>getArgument(1).get()));
        when(store.createOrGetAndEnqueue(any(), any())).thenReturn(new JdbcStore.CreatedRun(
                new Models.ReviewRun("existing-run", 99, 7, "base1234", "head1234", "queued",
                        Models.PIPELINE_VERSION, Models.DEFAULT_CONFIG_HASH, "rerun", "rerun:delivery-1", null), false));
        var response = deliver(store, config, """
                {"action":"rerequested","installation":{"id":42},"repository":{"id":99,"name":"demo","owner":{"login":"octo"}},
                "check_run":{"id":9,"name":"CodeLens AI Review","app":{"id":123},"head_sha":"head1234",
                "pull_requests":[{"number":7,"base":{"sha":"base1234"},"head":{"sha":"head1234"}}]}}
                """, "check_run");
        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertEquals("rerun_already_queued", response.getBody().get("status"));
        verify(store).seedPublication(new Models.Publication("existing-run", "head1234", 9L, null));
        verify(store, never()).savePublication(any());
    }

    private static org.springframework.http.ResponseEntity<java.util.Map<String, String>> deliver(
            JdbcStore store, RuntimeConfig config, String content) {
        return deliver(store, config, content, "pull_request");
    }

    private static org.springframework.http.ResponseEntity<java.util.Map<String, String>> deliver(
            JdbcStore store, RuntimeConfig config, String content, String event) {
        WebhookController controller = new WebhookController(store, config, new ObjectMapper(), readySchema(), new ClientAddressResolver(config));
        HttpServletRequest request = mock(HttpServletRequest.class); when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        byte[] body = content.getBytes(StandardCharsets.UTF_8);
        return controller.webhook(body, WebhookSecurity.sign(body, config.webhookSecret()), "delivery-1", event, request);
    }

    @Test
    void reportsNotReadyWhenTheDatabaseSchemaIsIncomplete() {
        JdbcStore store = mock(JdbcStore.class);
        MigrationSchemaVerifier schema = mock(MigrationSchemaVerifier.class);
        when(schema.status()).thenReturn(new MigrationSchemaVerifier.Status(false, "migration_missing"));
        RuntimeConfig config = config();
        WebhookController controller = new WebhookController(store, config, new ObjectMapper(), schema, new ClientAddressResolver(config));

        var response = controller.ready();

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals("not_ready", response.getBody().get("status"));
        verify(store).ping();
    }

    private static RuntimeConfig config() {
        return new RuntimeConfig("test", "api", "postgres://codelens:codelens@localhost:5432/codelens", "123", "",
                "a-strong-test-secret-value", 300, false, 100, 120_000, 8, 500_000,
                "", "", "", "", "", "", 4, 250_000, 2, "infra/migrations", "", "", false,
                false, java.util.Set.of(), ".codelens-workspaces/semantic", 512L * 1024 * 1024, 2L * 1024 * 1024 * 1024,
                200_000, 50_000, 2L * 1024 * 1024, "", 512, 128L * 1024 * 1024);
    }

    private static MigrationSchemaVerifier readySchema() {
        MigrationSchemaVerifier schema = mock(MigrationSchemaVerifier.class);
        when(schema.status()).thenReturn(new MigrationSchemaVerifier.Status(true, "ready"));
        return schema;
    }
}
