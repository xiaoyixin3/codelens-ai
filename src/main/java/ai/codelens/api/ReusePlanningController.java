package ai.codelens.api;

import ai.codelens.semantic.ReuseDecisionService;
import ai.codelens.store.ReuseDecisionStore;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/** Explicit, installation-scoped approval boundary for production reuse planning. */
@RestController
@Profile("api")
@RequestMapping("/api/v2/reviews/{reviewRunId}/reuse-planning")
public final class ReusePlanningController {
    private final ReuseDecisionStore store;
    private final AdminRequestAuthorizer authorizer;

    public ReusePlanningController(ReuseDecisionStore store, AdminRequestAuthorizer authorizer) {
        this.store = store;
        this.authorizer = authorizer;
    }

    @GetMapping
    public ResponseEntity<?> get(@PathVariable String reviewRunId, @RequestHeader Map<String, String> headers) {
        var auth = authorizer.authorize(headers);
        if (auth.error() != null) return auth.error();
        if (!uuid(reviewRunId)) return invalid("reviewRunId must be a UUID");
        try { return ResponseEntity.ok(store.get(auth.installationId(), reviewRunId)); }
        catch (RuntimeException exception) { return storeError(exception); }
    }

    @PostMapping
    public ResponseEntity<?> decide(@PathVariable String reviewRunId,
                                    @RequestBody ReuseDecisionService.Submission submission,
                                    @RequestHeader Map<String, String> headers) {
        var auth = authorizer.authorize(headers);
        if (auth.error() != null) return auth.error();
        if (!uuid(reviewRunId)) return invalid("reviewRunId must be a UUID");
        try {
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(store.save(auth.installationId(), reviewRunId, submission, auth.actor()));
        } catch (RuntimeException exception) { return storeError(exception); }
    }

    private static boolean uuid(String value) {
        try { UUID.fromString(value); return true; }
        catch (RuntimeException exception) { return false; }
    }

    private static ResponseEntity<Map<String, String>> invalid(String detail) {
        return ResponseEntity.unprocessableEntity().body(Map.of("error", "invalid_reuse_decision", "detail", detail));
    }

    private static ResponseEntity<?> storeError(RuntimeException failure) {
        if (failure instanceof EmptyResultDataAccessException) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "reuse_investigation_not_found"));
        }
        if (failure instanceof IllegalArgumentException) return invalid(failure.getMessage());
        if (failure instanceof ReuseDecisionStore.DecisionConflictException
                || failure instanceof DataIntegrityViolationException) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "reuse_decision_conflict"));
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "internal_error"));
    }
}
