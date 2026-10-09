package ai.codelens.store;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.UUID;

/** Internal persistence foundation only; no bean, endpoint, authorization or recovery action. */
public final class PublicationRecoveryAuditStore {
    public record Attempt(UUID id, UUID runId, String actorHash, String evidenceHash) {
        public Attempt {
            if (id == null || runId == null || actorHash == null || evidenceHash == null
                    || !actorHash.matches("[0-9a-f]{64}") || !evidenceHash.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Audit attempt requires bounded identity/evidence digests");
            }
        }
    }
    public enum Denial {
        NOT_AUTHORIZED, EXPIRED_APPROVAL, STATE_CHANGED, REMOTE_UNVERIFIED, UNSUPPORTED_PROTOCOL
    }
    public enum Outcome { NOT_FOUND, PENDING_INVESTIGATION, DENIED, COMMITTED_LOCAL_STRUCTURE_MATCHES, COMMITTED_LOCAL_STRUCTURE_CHANGED }
    /** Metadata only, one database statement snapshot; never authorization or proof of current GitHub state. */
    public record Investigation(UUID attemptId, UUID runId, Outcome outcome, String reason,
                                String requestedAt, String terminalAt,
                                boolean automaticRecoveryAllowed, boolean approvalReusable) {}
    private static final String LOCAL_CONFIRMATION = """
            FROM review_runs r JOIN review_jobs j ON j.review_run_id=r.id
              JOIN publications p ON p.review_run_id=r.id AND p.head_sha=r.head_sha
            WHERE r.id=%s AND r.status='completed' AND j.status='completed'
              AND j.lease_expires_at IS NULL AND j.locked_at IS NULL
              AND p.check_run_id=(SELECT remote_id FROM check_publication_effects WHERE review_run_id=r.id AND operation='check_start')
              AND p.check_run_id=(SELECT remote_id FROM check_publication_effects WHERE review_run_id=r.id AND operation='check_result')
              AND p.summary_comment_id=(SELECT remote_id FROM check_publication_effects WHERE review_run_id=r.id AND operation='summary_comment')
              AND NOT EXISTS (SELECT 1 FROM summary_publication_slots s WHERE s.active_run_id=r.id)
              AND (SELECT count(*) FROM check_publication_effects e WHERE e.review_run_id=r.id
                AND e.state='confirmed' AND e.remote_id IS NOT NULL)=3
            """;
    private final JdbcTemplate jdbc;
    private final DataSource dataSource;
    private final TransactionTemplate independent;
    private final TransactionTemplate inspection;

    public PublicationRecoveryAuditStore(JdbcTemplate jdbc, DataSource dataSource) {
        if (jdbc.getDataSource() != dataSource) throw new IllegalArgumentException("Audit requires the same data source");
        this.jdbc = jdbc; this.dataSource = dataSource;
        independent = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        independent.setTimeout(5);
        inspection = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        inspection.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        inspection.setReadOnly(true);
        inspection.setTimeout(5);
    }

    /** The future authorization layer must supply a verified principal digest, never a typed name. */
    public void requested(Attempt attempt) {
        independent.executeWithoutResult(status -> requireOne(jdbc.update("""
                INSERT INTO publication_recovery_audit(attempt_id,review_run_id,stage,actor_hash,evidence_hash,reason)
                VALUES (?,?,'requested',?,?,'none')
                """, attempt.id(), attempt.runId(), attempt.actorHash(), attempt.evidenceHash())));
    }

    public void requireDataSource(DataSource expected) {
        if (dataSource != expected) throw new IllegalArgumentException("Recovery audit requires the business data source");
    }

    /** Own-actor lookup only. Missing, deleted and another actor's attempt are deliberately indistinguishable. */
    public Investigation investigate(UUID attemptId, String verifiedActorHash) {
        if (attemptId == null || verifiedActorHash == null || !verifiedActorHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Bounded investigation identity required");
        }
        String query = """
                SELECT q.review_run_id,q.created_at AS requested_at,t.created_at AS terminal_at,t.stage,t.reason,
                  EXISTS(SELECT 1 %s) AS locally_complete
                FROM publication_recovery_audit q
                LEFT JOIN publication_recovery_audit t ON t.attempt_id=q.attempt_id AND t.stage IN ('denied','committed')
                  AND t.review_run_id=q.review_run_id AND t.actor_hash=q.actor_hash AND t.evidence_hash=q.evidence_hash
                WHERE q.attempt_id=? AND q.stage='requested' AND q.actor_hash=?
                """.formatted(LOCAL_CONFIRMATION.formatted("q.review_run_id"));
        return inspection.execute(status -> jdbc.query(query, (rs, ignored) -> {
            String stage=rs.getString("stage");
            Outcome outcome=stage==null ? Outcome.PENDING_INVESTIGATION : "denied".equals(stage) ? Outcome.DENIED
                    : rs.getBoolean("locally_complete") ? Outcome.COMMITTED_LOCAL_STRUCTURE_MATCHES : Outcome.COMMITTED_LOCAL_STRUCTURE_CHANGED;
            var terminal=rs.getTimestamp("terminal_at");
            return new Investigation(attemptId,rs.getObject("review_run_id",UUID.class),outcome,
                    stage==null ? "none" : rs.getString("reason"),rs.getTimestamp("requested_at").toInstant().toString(),
                    terminal==null ? null : terminal.toInstant().toString(),false,false);
        },attemptId,verifiedActorHash).stream().findFirst().orElseGet(() ->
                new Investigation(attemptId,null,Outcome.NOT_FOUND,"none",null,null,false,false)));
    }

    /** Independently durable even when the caller's business transaction rolls back. */
    public void denied(Attempt attempt, Denial denial) {
        if (denial == null) throw new IllegalArgumentException("Bounded denial reason required");
        independent.executeWithoutResult(status -> append(attempt, "denied", denial.name().toLowerCase(java.util.Locale.ROOT)));
    }

    /** No independent commit: caller must already have confirmed the local state in this transaction. */
    public void committedWithinTransaction(Attempt attempt) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.hasResource(dataSource)) {
            throw new IllegalStateException("Successful audit requires the business transaction");
        }
        Integer eligible = jdbc.queryForObject("SELECT count(*) " + LOCAL_CONFIRMATION.formatted("?"), Integer.class, attempt.runId());
        if (eligible == null || eligible != 1) throw new IllegalStateException("Local confirmation is incomplete");
        append(attempt, "committed", "none");
    }

    private void append(Attempt attempt, String stage, String reason) {
        requireOne(jdbc.update("""
                INSERT INTO publication_recovery_audit(attempt_id,review_run_id,stage,actor_hash,evidence_hash,reason)
                SELECT attempt_id,review_run_id,?,actor_hash,evidence_hash,? FROM publication_recovery_audit
                WHERE attempt_id=? AND stage='requested' AND review_run_id=? AND actor_hash=? AND evidence_hash=?
                """, stage, reason, attempt.id(), attempt.runId(), attempt.actorHash(), attempt.evidenceHash()));
    }
    private static void requireOne(int count) {
        if (count != 1) throw new IllegalStateException("Audit attempt is absent or conflicts");
    }
}
