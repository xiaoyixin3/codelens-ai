package ai.codelens.github;

import ai.codelens.config.RuntimeConfig;
import ai.codelens.contracts.Models;
import ai.codelens.store.LeaseLostException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GitHubClientRetrySafetyTest {
    private final HttpClient http = mock(HttpClient.class);
    private final AtomicInteger sends = new AtomicInteger();
    private GitHubClient github;
    private final Models.ReviewJob job = new Models.ReviewJob("run", 1, "o", "r", 7, "base1234", "head1234");

    @BeforeEach void setup() throws Exception {
        RuntimeConfig config = mock(RuntimeConfig.class);
        when(config.githubAppId()).thenReturn("42");
        var keys = KeyPairGenerator.getInstance("RSA"); keys.initialize(2048);
        when(config.githubPrivateKey()).thenReturn(Base64.getEncoder().encodeToString(keys.generateKeyPair().getPrivate().getEncoded()));
        github = new GitHubClient(config, new ObjectMapper(), http);
    }

    @SuppressWarnings("unchecked")
    private HttpResponse<byte[]> response(int status, String body) {
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body.getBytes(StandardCharsets.UTF_8));
        return response;
    }

    private void remote(int status, String body, Exception failure) throws Exception {
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenAnswer(call -> {
            HttpRequest request = call.getArgument(0);
            if (request.uri().getPath().endsWith("/access_tokens")) {
                return response(201, "{\"token\":\"test-value\",\"expires_at\":\"2099-01-01T00:00:00Z\"}");
            }
            sends.incrementAndGet();
            if (failure != null) throw failure;
            return response(status, body);
        });
    }

    @Test void createTimeoutIsNotRetried() throws Exception {
        remote(0, "", new IOException("lost response after remote commit"));
        assertThrows(PublicationUncertainException.class, () -> github.startCheck(1, "o", "r", "head1234", null));
        assertEquals(1, sends.get());
    }

    @Test void serverFailureOnAnnotationPatchIsNotRetried() throws Exception {
        remote(503, "private repository text", null);
        var exception = assertThrows(PublicationUncertainException.class, () -> github.completeCheck(
                1, "o", "r", 9, "success", "title", "summary", List.of()));
        assertEquals(1, sends.get());
        assertFalse(exception.getCause().getMessage().contains("private repository text"));
    }

    @Test void successfulWriteWithInvalidResponseIsUncertain() throws Exception {
        remote(201, "not-json", null);
        assertThrows(PublicationUncertainException.class, () -> github.startCheck(1, "o", "r", "head1234", null));
        assertEquals(1, sends.get());
    }

    @Test void missingRemoteIdOnSuccessfulCreateIsUncertain() throws Exception {
        remote(201, "{}", null);
        assertThrows(PublicationUncertainException.class, () -> github.startCheck(1, "o", "r", "head1234", null));
        assertEquals(1, sends.get());
    }

    @Test void permanentReadFailureIsNotRetriedOrLoggedWithItsBody() throws Exception {
        remote(403, "sensitive details", null);
        var exception = assertThrows(IllegalStateException.class, () -> github.currentRevision(1, "o", "r", 7));
        assertEquals(1, sends.get());
        assertFalse(exception.getMessage().contains("sensitive details"));
    }

    @Test void transientReadFailureRetainsBoundedRetries() throws Exception {
        remote(503, "", null);
        assertThrows(IllegalStateException.class, () -> github.currentRevision(1, "o", "r", 7));
        assertEquals(3, sends.get());
    }

    @Test void writeRateLimitDoesNotTriggerTransportReplay() throws Exception {
        remote(429, "", null);
        assertThrows(IllegalStateException.class, () -> github.startCheck(1, "o", "r", "head1234", null));
        assertEquals(1, sends.get());
    }

    private String check(long id, String app, String head, String external) {
        var mapper = new ObjectMapper();
        return mapper.createObjectNode().put("id", id).put("name", "CodeLens AI Review")
                .put("head_sha", head).put("external_id", external).set("app", mapper.createObjectNode().put("id", app)).toString();
    }

    private void pages(String first, String second) throws Exception {
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenAnswer(call -> {
            HttpRequest request = call.getArgument(0);
            if (request.uri().getPath().endsWith("/access_tokens")) return response(201,
                    "{\"token\":\"test-value\",\"expires_at\":\"2099-01-01T00:00:00Z\"}");
            sends.incrementAndGet();
            assertTrue(request.uri().getQuery().contains("filter=all"));
            return response(200, request.uri().getQuery().endsWith("page=1") ? first : second);
        });
    }

    @Test void discoveryFindsOwnCheckAfterFirstHundredResults() throws Exception {
        String filler = String.join(",", java.util.Collections.nCopies(100, check(1, "43", "head1234", "identity")));
        pages("{\"check_runs\":[" + filler + "]}", "{\"check_runs\":[" + check(9, "42", "head1234", "identity") + "]}");
        assertEquals(9L, github.findReviewCheck(job, "identity", () -> {}).orElseThrow());
        assertEquals(2, sends.get());
    }

    @Test void copiedIdentityOnForeignAppIsNotAdopted() throws Exception {
        remote(200, "{\"check_runs\":[" + check(9, "43", "head1234", "identity") + "]}", null);
        assertTrue(github.findReviewCheck(job, "identity", () -> {}).isEmpty());
    }

    @Test void duplicateOwnIdentityFailsClosed() throws Exception {
        remote(200, "{\"check_runs\":[" + check(9, "42", "head1234", "identity") + ","
                + check(10, "42", "head1234", "identity") + "]}", null);
        assertThrows(PublicationUncertainException.class, () -> github.findReviewCheck(job, "identity", () -> {}));
    }

    @Test void paginationBudgetExhaustionDoesNotMeanTheCheckIsAbsent() throws Exception {
        String filler = String.join(",", java.util.Collections.nCopies(100, check(1, "43", "head1234", "other")));
        remote(200, "{\"check_runs\":[" + filler + "]}", null);
        assertThrows(PublicationUncertainException.class, () -> github.findReviewCheck(job, "identity", () -> {}));
        assertEquals(20, sends.get());
    }

    @Test void discoveryChecksLeaseBeforeEachPage() throws Exception {
        String filler = String.join(",", java.util.Collections.nCopies(100, check(1, "43", "head1234", "other")));
        remote(200, "{\"check_runs\":[" + filler + "]}", null);
        AtomicInteger checks = new AtomicInteger();
        assertThrows(LeaseLostException.class, () -> github.findReviewCheck(job, "identity", () -> {
            if (checks.incrementAndGet() > 1) throw new LeaseLostException();
        }));
        assertEquals(1, sends.get());
    }

    @Test void knownCheckWithWrongAppOrRevisionCannotBeUpdated() throws Exception {
        remote(200, check(9, "43", "head1234", "identity"), null);
        assertThrows(PublicationUncertainException.class, () -> github.getReviewCheck(job, 9, "identity"));
        remote(200, check(9, "42", "other-head", "identity"), null);
        assertThrows(PublicationUncertainException.class, () -> github.getReviewCheck(job, 9, "identity"));
    }

    @Test void malformedCheckListingCannotBeTreatedAsEmpty() throws Exception {
        remote(200, "{}", null);
        assertThrows(PublicationUncertainException.class, () -> github.findReviewCheck(job, "identity", () -> {}));
    }

    private String comment(long id, long authorId, String type, int pr, String body) {
        var mapper = new ObjectMapper();
        return mapper.createObjectNode().put("id", id).put("issue_url", "https://api.github.com/repos/o/r/issues/" + pr)
                .put("body", body).set("user", mapper.createObjectNode().put("id", authorId)
                        .put("login", "reviewer[bot]").put("type", type)).toString();
    }

    private void summaryRoutes(String first, String later, String known) throws Exception {
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenAnswer(call -> {
            HttpRequest request = call.getArgument(0);
            String path = request.uri().getPath();
            if (path.equals("/app")) {
                assertEquals(3, request.headers().firstValue("Authorization").orElseThrow().substring(7).split("\\.").length);
                return response(200, "{\"id\":42,\"slug\":\"reviewer\"}");
            }
            if (path.endsWith("/access_tokens")) return response(201,
                    "{\"token\":\"test-value\",\"expires_at\":\"2099-01-01T00:00:00Z\"}");
            if (path.startsWith("/users/")) return response(200, "{\"id\":77,\"login\":\"reviewer[bot]\",\"type\":\"Bot\"}");
            sends.incrementAndGet();
            if (!request.method().equals("GET")) return response(200, known);
            return response(200, request.uri().getQuery() == null ? known
                    : request.uri().getQuery().endsWith("page=1") ? first : later);
        });
    }

    @Test void foreignAuthorCannotHijackCopiedSummaryMarker() throws Exception {
        summaryRoutes("[" + comment(9, 123, "User", 7, GitHubClient.SUMMARY_MARKER + "\ncopy") + "]", "[]", "{}");
        assertTrue(github.findSummaryComment(job, null, () -> {}).isEmpty());
    }

    @Test void knownForeignCommentIsRejectedBeforePatch() throws Exception {
        summaryRoutes("[]", "[]", comment(9, 123, "User", 7, GitHubClient.SUMMARY_MARKER + "\ncopy"));
        assertThrows(PublicationUncertainException.class, () -> github.writeSummaryComment(job, "body", 9L, () -> {}));
        assertEquals(1, sends.get());
    }

    @Test void ownCommentOnAnotherPrIsRejected() throws Exception {
        summaryRoutes("[]", "[]", comment(9, 77, "Bot", 8, GitHubClient.SUMMARY_MARKER + "\nsummary"));
        assertThrows(PublicationUncertainException.class, () -> github.getSummaryComment(job, 9, () -> {}));
    }

    @Test void summaryBeyondFirstPageIsDiscoveredWithBotUserIdNotAppId() throws Exception {
        String filler = String.join(",", java.util.Collections.nCopies(100, comment(1, 123, "User", 7, "normal")));
        summaryRoutes("[" + filler + "]", "[" + comment(9, 77, "Bot", 7, GitHubClient.SUMMARY_MARKER + "\nsummary") + "]", "{}");
        assertEquals(9L, github.findSummaryComment(job, null, () -> {}).orElseThrow());
        assertEquals(2, sends.get());
    }

    @Test void quotedMarkerIsNotAReusableSummary() throws Exception {
        summaryRoutes("[" + comment(9, 77, "Bot", 7, "Quoted: " + GitHubClient.SUMMARY_MARKER + "\nsummary") + "]", "[]", "{}");
        assertTrue(github.findSummaryComment(job, null, () -> {}).isEmpty());
    }

    @Test void multipleOwnedSummariesFailClosed() throws Exception {
        String body = GitHubClient.SUMMARY_MARKER + "\nsummary";
        summaryRoutes("[" + comment(9, 77, "Bot", 7, body) + "," + comment(10, 77, "Bot", 7, body) + "]", "[]", "{}");
        assertThrows(PublicationUncertainException.class, () -> github.findSummaryComment(job, body, () -> {}));
    }

    @Test void summaryPaginationCapDoesNotPermitCreation() throws Exception {
        String filler = "[" + String.join(",", java.util.Collections.nCopies(100, comment(1, 123, "User", 7, "normal"))) + "]";
        summaryRoutes(filler, filler, "{}");
        assertThrows(PublicationUncertainException.class, () -> github.findSummaryComment(job, null, () -> {}));
        assertEquals(20, sends.get());
    }

    @Test void interruptedWriteIsUncertainAndPreservesInterrupt() throws Exception {
        remote(0, "", new InterruptedException("interrupted send"));
        try {
            assertThrows(PublicationUncertainException.class, () -> github.startCheck(1, "o", "r", "head1234", null));
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(1, sends.get());
        } finally { Thread.interrupted(); }
    }
}
