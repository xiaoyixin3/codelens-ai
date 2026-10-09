package ai.codelens.review;

import ai.codelens.config.RuntimeConfig;
import ai.codelens.contracts.Models;
import ai.codelens.github.GitHubClient;
import ai.codelens.store.JdbcStore;
import ai.codelens.store.PublicationRecoveryAuditStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;

/** Internal, deliberately not a Spring bean or public entry point. No remote mutation or regeneration. */
public final class PublicationRecoveryService {
    private final JdbcStore store;
    private final PublicationInspector inspector;
    private final CheckPublisher fingerprints;
    private final RecoveryApprovalService approvals;
    private final PublicationRecoveryAuditStore audit;
    private final RuntimeConfig config;
    private final ObjectMapper json;

    public PublicationRecoveryService(JdbcStore store, GitHubClient github, ObjectMapper json, RuntimeConfig config,
                                      RecoveryApprovalService approvals, PublicationRecoveryAuditStore audit) {
        this.store=store; this.json=json; this.config=config; this.approvals=approvals; this.audit=audit;
        store.requireRecoveryAuditDataSource(audit);
        this.inspector=new PublicationInspector(store, github, json, config);
        this.fingerprints=new CheckPublisher(store, github, json);
    }

    public RecoveryApprovalService.IssuedApproval prepare(String runId, RecoveryOperatorAuthenticator.Principal principal) {
        approvals.requirePrincipal(principal);
        var binding = inspectedBinding(runId);
        validateFrozen(binding);
        return approvals.issue(principal, binding);
    }

    /** Read-only investigation, independent of approval expiry; never consumes or replays an approval. */
    public PublicationRecoveryAuditStore.Investigation investigate(java.util.UUID attemptId,
                                                                  RecoveryOperatorAuthenticator.Principal principal) {
        approvals.requirePrincipal(principal);
        return audit.investigate(attemptId,principal.actorHash());
    }

    public void recover(String runId, RecoveryOperatorAuthenticator.Principal principal, String bearer) {
        approvals.requirePrincipal(principal);
        // Never reserve an attempt against user-supplied report JSON or a stale CLI report.
        var initial=inspectedBinding(runId);
        var verified=approvals.verify(principal, initial, bearer);
        var attempt=approvals.reserveAttempt(verified, audit); // Duplicate reservation fails outside the denial handler.
        try {
            var fresh=inspectedBinding(runId); // Network reads are all outside the PR transaction.
            approvals.verify(principal, fresh, bearer);
            store.withReviewPrLock(fresh.repositoryId(), fresh.pullNumber(), () -> {
                var locked=store.recoveryBinding(runId, fresh.checkId(), fresh.commentId(), true);
                if (!locked.equals(fresh)) throw new IllegalStateException("Recovery state changed");
                var output=validateFrozen(locked);
                approvals.verify(principal, locked, bearer); // Check expiry again after lock wait.
                store.confirmRecoveryLocally(locked, output.summaryJson(), audit, attempt);
                return null;
            });
        } catch (SecurityException rejected) {
            audit.denied(attempt, PublicationRecoveryAuditStore.Denial.NOT_AUTHORIZED);
            throw rejected;
        } catch (IllegalStateException changed) {
            audit.denied(attempt, PublicationRecoveryAuditStore.Denial.STATE_CHANGED);
            throw changed;
        } catch (ai.codelens.github.PublicationUncertainException unverified) {
            audit.denied(attempt, PublicationRecoveryAuditStore.Denial.REMOTE_UNVERIFIED);
            throw unverified;
        } catch (IllegalArgumentException changed) {
            audit.denied(attempt, PublicationRecoveryAuditStore.Denial.STATE_CHANGED);
            throw changed;
        }
        // Database/transport exceptions can include unknown commit outcomes: leave requested for investigation,
        // never fabricate a denial/success and never retry the approval or send anything to GitHub.
    }

    private RecoveryApprovalService.Binding inspectedBinding(String runId) {
        var report=inspector.inspect(runId);
        if (!"candidate_for_authorized_local_confirmation".equals(report.recovery().status())) {
            throw new IllegalStateException("Remote evidence or recovery state is not eligible");
        }
        long check=remoteId(report, "check_result"), comment=remoteId(report, "summary_comment");
        return store.recoveryBinding(runId, check, comment, false);
    }

    private static long remoteId(PublicationInspector.Report report, String operation) {
        return report.operations().stream().filter(o -> operation.equals(o.operation())
                && "verified_remote".equals(o.result()) && o.remoteId()!=null && o.remoteId()>0)
                .findFirst().orElseThrow(() -> new IllegalStateException("Remote evidence is missing")).remoteId();
    }

    private FrozenReviewOutput validateFrozen(RecoveryApprovalService.Binding binding) {
        if (!config.frozenPublicationsEnabled() || (config.publicTrial()
                && !config.permitsTrialRepository(binding.owner(), binding.repository()))) {
            throw new IllegalStateException("Frozen recovery is outside configured scope");
        }
        var job=new Models.ReviewJob(binding.runId().toString(), binding.installationId(), binding.owner(),
                binding.repository(), binding.pullNumber(), binding.baseSha(), binding.headSha());
        var stored=store.getFrozenReviewOutput(job.reviewRunId()).orElseThrow(() -> new IllegalStateException("Frozen output missing"));
        var output=FrozenReviewOutput.open(stored, job, binding.repositoryId(), config.publicTrial(), config.publicationKey(), json);
        String identity=fingerprints.identity(job);
        if (!stored.payloadHash().equals(binding.outputHash()) || output.checkId()!=binding.checkId()
                || !binding.startHash().equals(fingerprints.fingerprint(List.of("check-start-v1", identity)))
                || !binding.resultHash().equals(fingerprints.fingerprint(List.of("check-result-v1", identity, output.checkId(),
                        output.conclusion(), output.title(), output.markdown(), output.annotations())))
                || !binding.summaryHash().equals(fingerprints.fingerprint(List.of("summary-comment-v1", identity, output.markdown())))) {
            throw new IllegalStateException("Frozen output conflicts with approved evidence");
        }
        return output;
    }
}
