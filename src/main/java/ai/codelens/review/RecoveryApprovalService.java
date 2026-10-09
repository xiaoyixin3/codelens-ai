package ai.codelens.review;

import ai.codelens.contracts.Models;
import ai.codelens.credentials.CredentialVault;
import ai.codelens.store.PublicationRecoveryAuditStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.UUID;

/** Five-minute, one-attempt authorization foundation. Never performs recovery or remote work. */
public final class RecoveryApprovalService {
    public record Binding(UUID runId, UUID jobId, long installationId, long repositoryId, int pullNumber,
                          String owner, String repository, String baseSha, String headSha, String pipeline,
                          String outputHash, String startHash, String resultHash, String summaryHash,
                          long checkId, long commentId, long leaseGeneration, UUID summarySlotRunId,
                          Long localCheckId, Long localCommentId) {
        public Binding {
            if (runId == null || jobId == null || summarySlotRunId == null || !runId.equals(summarySlotRunId)
                    || installationId < 1 || repositoryId < 1 || pullNumber < 1 || checkId < 1 || commentId < 1 || leaseGeneration < 1
                    || !safeName(owner) || !safeName(repository) || !revision(baseSha) || !revision(headSha)
                    || !Models.PIPELINE_VERSION.equals(pipeline) || !hash(outputHash) || !hash(startHash)
                    || !hash(resultHash) || !hash(summaryHash)
                    || (localCheckId != null && localCheckId != checkId)
                    || (localCommentId != null && localCommentId != commentId)) throw new IllegalArgumentException("Invalid recovery binding");
        }
        private static boolean safeName(String value) { return value != null && value.matches("[a-zA-Z0-9_.-]{1,100}"); }
        private static boolean revision(String value) { return value != null && value.matches("([0-9a-f]{40}|[0-9a-f]{64})"); }
        private static boolean hash(String value) { return value != null && value.matches("[0-9a-f]{64}"); }
    }
    public record Claims(int version, String action, UUID approvalId, UUID runId, String actorHash, String bindingHash,
                         long issuedAtMillis, long expiresAtMillis) {}
    public static final class IssuedApproval {
        private final String bearer;
        private final long expiresAt;
        private final UUID attemptId;
        private IssuedApproval(String bearer, long expiresAt, UUID attemptId) { this.bearer = bearer; this.expiresAt = expiresAt; this.attemptId=attemptId; }
        public String bearer() { return bearer; } // Sensitive: future protected transport only, never logs/CLI args.
        public long expiresAtMillis() { return expiresAt; }
        public UUID attemptId() { return attemptId; } // Non-secret investigation handle, not authority.
        @Override public String toString() { return "[sensitive recovery approval]"; }
    }
    public static final class VerifiedApproval {
        private final RecoveryApprovalService issuer;
        private final Claims claims;
        private VerifiedApproval(RecoveryApprovalService issuer, Claims claims) { this.issuer = issuer; this.claims = claims; }
        @Override public String toString() { return "[verified recovery approval]"; }
    }
    private static final long TTL_MILLIS = 300_000;
    private final RecoveryOperatorAuthenticator operators;
    private final CredentialVault vault;
    private final ObjectMapper json;
    private final Clock clock;

    public RecoveryApprovalService(RecoveryOperatorAuthenticator operators, String separateApprovalKey, ObjectMapper json) {
        this(operators, separateApprovalKey, json, Clock.systemUTC());
    }
    RecoveryApprovalService(RecoveryOperatorAuthenticator operators, String key, ObjectMapper json, Clock clock) {
        if (operators == null || json == null || clock == null) throw new IllegalArgumentException("Approval dependencies required");
        operators.requireSeparateKey(key);
        this.operators = operators; this.vault = new CredentialVault(key); this.json = json; this.clock = clock;
    }
    public IssuedApproval issue(RecoveryOperatorAuthenticator.Principal principal, Binding binding) {
        operators.requireOwned(principal);
        try {
            long now = clock.millis();
            Claims claims = new Claims(1, "local_confirmation", UUID.randomUUID(), binding.runId(), principal.actorHash(), bindingHash(binding), now, Math.addExact(now, TTL_MILLIS));
            return new IssuedApproval(vault.seal(json.writeValueAsString(claims), context(binding.runId(), principal.actorHash())), claims.expiresAtMillis(),claims.approvalId());
        } catch (Exception unavailable) { throw RecoveryOperatorAuthenticator.rejected(); }
    }
    public void requirePrincipal(RecoveryOperatorAuthenticator.Principal principal) {
        operators.requireOwned(principal);
    }
    public VerifiedApproval verify(RecoveryOperatorAuthenticator.Principal principal, Binding expected, String bearer) {
        operators.requireOwned(principal);
        try {
            if (bearer == null || bearer.length() > 8192) throw RecoveryOperatorAuthenticator.rejected();
            Claims claims = json.readValue(vault.open(bearer, context(expected.runId(), principal.actorHash())), Claims.class);
            requireCurrent(claims);
            if (claims.version() != 1 || !"local_confirmation".equals(claims.action()) || claims.approvalId() == null || !expected.runId().equals(claims.runId())
                    || !principal.actorHash().equals(claims.actorHash()) || !bindingHash(expected).equals(claims.bindingHash())) {
                throw RecoveryOperatorAuthenticator.rejected();
            }
            return new VerifiedApproval(this, claims);
        } catch (Exception unavailable) { throw RecoveryOperatorAuthenticator.rejected(); }
    }
    /** Independent durable reservation burns the approval even after business failure/crash. */
    public PublicationRecoveryAuditStore.Attempt reserveAttempt(VerifiedApproval approval, PublicationRecoveryAuditStore audit) {
        if (approval == null || approval.issuer != this || audit == null) throw RecoveryOperatorAuthenticator.rejected();
        requireCurrent(approval.claims);
        var attempt = new PublicationRecoveryAuditStore.Attempt(approval.claims.approvalId(), approval.claims.runId(),
                approval.claims.actorHash(), approval.claims.bindingHash());
        audit.requested(attempt); // Unique attempt ID is shared across replicas; never ignore insert failure.
        requireCurrent(approval.claims);
        return attempt;
    }
    private void requireCurrent(Claims claims) {
        long now = clock.millis();
        if (claims.issuedAtMillis() > now || claims.expiresAtMillis() <= now
                || claims.expiresAtMillis() <= claims.issuedAtMillis()
                || Math.subtractExact(claims.expiresAtMillis(), claims.issuedAtMillis()) > TTL_MILLIS) throw RecoveryOperatorAuthenticator.rejected();
    }
    private String bindingHash(Binding binding) throws Exception {
        return RecoveryOperatorAuthenticator.digest("publication-recovery-binding-v1:" + json.writeValueAsString(binding));
    }
    private static String context(UUID run, String actor) { return "publication-recovery-approval:local-confirmation:v1:run:" + run + ":actor:" + actor; }
    @Override public String toString() { return "[protected recovery approval service]"; }
}
