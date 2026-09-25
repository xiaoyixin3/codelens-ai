package ai.codelens.api;

import ai.codelens.config.RuntimeConfig;
import ai.codelens.contracts.Models;
import ai.codelens.security.Redactor;
import ai.codelens.security.WebhookSecurity;
import ai.codelens.store.JdbcStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Set;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
@Profile("api")
public class WebhookController {
    private static final Logger LOG = LoggerFactory.getLogger(WebhookController.class);
    private static final Set<String> REVIEW_ACTIONS = Set.of("opened", "reopened", "synchronize", "ready_for_review");
    private static final Pattern FEEDBACK = Pattern.compile("(?i)^/codelens\\s+feedback\\s+([a-f0-9]{12,64})\\s+(helpful|false-positive)$");
    private final JdbcStore store;
    private final RuntimeConfig config;
    private final ObjectMapper json;
    private final FixedWindowLimiter limiter;

    public WebhookController(JdbcStore store, RuntimeConfig config, ObjectMapper json) {
        this.store = store; this.config = config; this.json = json;
        this.limiter = new FixedWindowLimiter(config.webhookRateLimit());
    }

    @GetMapping("/healthz")
    public Map<String, String> health() { return Map.of("status", "ok"); }

    @GetMapping("/readyz")
    public ResponseEntity<Map<String, String>> ready() {
        try { store.ping(); return ResponseEntity.ok(Map.of("status", "ready")); }
        catch (RuntimeException exception) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("status", "not_ready"));
        }
    }

    @PostMapping(path = "/webhooks/github", consumes = "application/json")
    public ResponseEntity<Map<String, String>> webhook(
            @RequestBody byte[] body,
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
            @RequestHeader(value = "X-GitHub-Delivery", required = false) String deliveryId,
            @RequestHeader(value = "X-GitHub-Event", required = false) String event,
            HttpServletRequest request) {
        if (!limiter.allow(clientAddress(request))) return response(HttpStatus.TOO_MANY_REQUESTS, "rate_limit_exceeded", null);
        if (blank(signature) || blank(deliveryId) || blank(event)
                || !WebhookSecurity.verify(body, signature, config.webhookSecret())) {
            return response(HttpStatus.UNAUTHORIZED, "invalid_webhook_signature", null);
        }
        JsonNode root;
        try { root = json.readTree(body); }
        catch (IOException exception) { return response(HttpStatus.BAD_REQUEST, "invalid_json", null); }
        try {
            String action = text(root, "action");
            if (!store.claimDelivery(deliveryId, event, action, body)) return response(HttpStatus.ACCEPTED, "duplicate", null);
            Result result = switch (event) {
                case "pull_request" -> pullRequest(root);
                case "check_run" -> checkRun(root, deliveryId);
                case "issue_comment" -> issueComment(root);
                default -> new Result("ignored", null);
            };
            store.markDeliveryProcessed(deliveryId, "");
            return response(HttpStatus.ACCEPTED, result.status(), result.runId());
        } catch (Exception exception) {
            String detail = truncate(Redactor.redact(exception.getMessage()), 2000);
            try { store.markDeliveryProcessed(deliveryId, detail); } catch (RuntimeException ignored) {}
            LOG.error("Webhook processing failed: {}", detail);
            return response(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", null);
        }
    }

    private Result pullRequest(JsonNode root) {
        if (!REVIEW_ACTIONS.contains(text(root, "action"))) return new Result("ignored_action", null);
        long installation = number(root, "installation", "id"); long repository = number(root, "repository", "id");
        int pull = (int) number(root, "pull_request", "number");
        String owner = text(root, "repository", "owner", "login"); String repo = text(root, "repository", "name");
        String base = text(root, "pull_request", "base", "sha"); String head = text(root, "pull_request", "head", "sha");
        if (installation < 1 || repository < 1 || pull < 1 || blank(owner) || blank(repo) || blank(base) || blank(head)) return new Result("ignored", null);
        return queue(repository, pull, base, head, installation, owner, repo, "webhook", "automatic", "queued", "already_queued");
    }

    private Result checkRun(JsonNode root, String deliveryId) {
        JsonNode pulls = root.path("check_run").path("pull_requests");
        if (!text(root, "action").equals("rerequested") || !text(root, "check_run", "name").equals("CodeLens AI Review") || !pulls.isArray() || pulls.isEmpty()) return new Result("ignored", null);
        long appId = number(root, "check_run", "app", "id");
        if (!config.githubAppId().isBlank() && appId > 0 && !config.githubAppId().equals(Long.toString(appId))) return new Result("ignored_foreign_check", null);
        JsonNode pullNode = pulls.get(0);
        int pull = pullNode.path("number").asInt(); String base = text(pullNode, "base", "sha"); String head = text(pullNode, "head", "sha");
        if (!head.equals(text(root, "check_run", "head_sha"))) return new Result("ignored_stale_check", null);
        long installation = number(root, "installation", "id"); long repository = number(root, "repository", "id");
        String owner = text(root, "repository", "owner", "login"); String repo = text(root, "repository", "name");
        Result result = queue(repository, pull, base, head, installation, owner, repo, "rerun", "rerun:" + deliveryId, "rerun_queued", "rerun_already_queued");
        if (result.runId() != null && result.status().equals("rerun_queued")) {
            store.savePublication(new Models.Publication(result.runId(), head, number(root, "check_run", "id"), null));
        }
        return result;
    }

    private Result issueComment(JsonNode root) {
        if (!text(root, "action").equals("created") || !root.path("issue").has("pull_request")) return new Result("ignored_comment", null);
        Matcher match = FEEDBACK.matcher(text(root, "comment", "body").trim());
        if (!match.matches()) return new Result("ignored_comment", null);
        boolean recorded = store.saveFindingFeedback(new JdbcStore.FindingFeedbackInput(
                number(root, "repository", "id"), (int) number(root, "issue", "number"), match.group(1).toLowerCase(),
                match.group(2).equalsIgnoreCase("false-positive") ? "false_positive" : "helpful",
                text(root, "comment", "user", "login"), number(root, "comment", "id")));
        return new Result(recorded ? "feedback_recorded" : "feedback_not_found", null);
    }

    private Result queue(long repository, int pull, String base, String head, long installation, String owner, String repo,
                         String trigger, String requestKey, String createdStatus, String existingStatus) {
        if (repository < 1 || pull < 1 || installation < 1 || blank(owner) || blank(repo) || blank(base) || blank(head)) return new Result("ignored", null);
        JdbcStore.CreatedRun created = store.createOrGetAndEnqueue(new JdbcStore.CreateReviewInput(repository, pull, base, head,
                Models.PIPELINE_VERSION, Models.DEFAULT_CONFIG_HASH, trigger, requestKey),
                new Models.ReviewJob("pending", installation, owner, repo, pull, base, head));
        return new Result(created.created() ? createdStatus : existingStatus, created.run().id());
    }

    private static ResponseEntity<Map<String, String>> response(HttpStatus status, String value, String runId) {
        return ResponseEntity.status(status).body(runId == null ? Map.of(status.isError() ? "error" : "status", value)
                : Map.of("status", value, "reviewRunId", runId));
    }
    private static String text(JsonNode node, String... path) { for (String part : path) node = node.path(part); return node.asText(""); }
    private static long number(JsonNode node, String... path) { for (String part : path) node = node.path(part); return node.asLong(); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String truncate(String value, int max) { value = value == null ? "unknown error" : value; return value.substring(0, Math.min(max, value.length())); }
    private static String clientAddress(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        return forwarded == null || forwarded.isBlank() ? request.getRemoteAddr() : forwarded.split(",", 2)[0].trim();
    }
    private record Result(String status, String runId) {}
    private static final class FixedWindowLimiter {
        private final int max; private final Map<String, Window> clients = new ConcurrentHashMap<>();
        private FixedWindowLimiter(int max) { this.max = Math.max(1, max); }
        private boolean allow(String key) {
            long minute = System.currentTimeMillis() / 60_000;
            Window value = clients.compute(key, (ignored, old) -> old == null || old.minute != minute ? new Window(minute, 1) : new Window(minute, old.count + 1));
            if (clients.size() > 10_000) clients.entrySet().removeIf(item -> item.getValue().minute < minute - 1);
            return value.count <= max;
        }
        private record Window(long minute, int count) {}
    }
}
