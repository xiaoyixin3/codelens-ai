package ai.codelens.store;

import ai.codelens.semantic.ReuseDecisionService;
import ai.codelens.semantic.SemanticReusePlanner;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Persists append-only, provenance-bound reuse decisions and their selected option. */
@Repository
@Profile("api")
public final class ReuseDecisionStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;
    private final ReuseDecisionService validator = new ReuseDecisionService();

    public ReuseDecisionStore(JdbcTemplate jdbc, DataSource dataSource, ObjectMapper json) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        this.json = json;
    }

    public PlanningView get(long installationId, String reviewRunId) {
        SemanticReusePlanner.Investigation investigation = investigation(installationId, reviewRunId, false);
        StoredDecision decision = current(installationId, reviewRunId);
        return new PlanningView(investigation, decision == null ? 0 : decision.revision(), decision);
    }

    public StoredDecision save(long installationId, String reviewRunId,
                               ReuseDecisionService.Submission submission, String actor) {
        if (installationId < 1 || actor == null || actor.isBlank() || actor.length() > 200) {
            throw new IllegalArgumentException("installation and actor are required");
        }
        return transactions.execute(status -> {
            SemanticReusePlanner.Investigation investigation = investigation(installationId, reviewRunId, true);
            ReuseDecisionService.ValidatedDecision decision = validator.validate(submission, investigation);
            Integer currentRevision = jdbc.queryForObject(
                    "SELECT COALESCE(max(revision),0) FROM reuse_decisions WHERE review_run_id=?::uuid",
                    Integer.class, reviewRunId);
            if (currentRevision == null) throw new IllegalStateException("Unable to read reuse decision revision");
            if (submission.expectedRevision() != currentRevision) {
                throw new DecisionConflictException("reuse decision revision changed; reload before submitting");
            }
            int revision = currentRevision + 1;
            jdbc.update("UPDATE reuse_decisions SET superseded_at=now() WHERE review_run_id=?::uuid AND superseded_at IS NULL",
                    reviewRunId);
            String decisionId = UUID.randomUUID().toString();
            String optionId = UUID.randomUUID().toString();
            jdbc.update("""
                    INSERT INTO reuse_decisions (
                      id,review_run_id,investigation_id,base_sha,head_sha,adapter_version,
                      base_build_model_hash,head_build_model_hash,revision,decision,selected_candidate_id,
                      decision_payload,actor
                    ) VALUES (?::uuid,?::uuid,?,?,?,?,?,?,?,?,NULLIF(?,''),?::jsonb,?)
                    """, decisionId, reviewRunId, decision.investigationId(), decision.provenance().baseSha(),
                    decision.provenance().headSha(), decision.provenance().adapterVersion(),
                    decision.provenance().baseBuildModelHash(), decision.provenance().headBuildModelHash(),
                    revision, decision.decision(), decision.selectedCandidateId(), toJson(decision), actor);
            jdbc.update("""
                    INSERT INTO reuse_solution_options (
                      id,reuse_decision_id,strategy,candidate_id,option_payload,actor
                    ) VALUES (?::uuid,?::uuid,?,NULLIF(?,''),?::jsonb,?)
                    """, optionId, decisionId, decision.selectedOption().strategy(),
                    decision.selectedOption().candidateId(), toJson(decision.selectedOption()), actor);
            return current(installationId, reviewRunId);
        });
    }

    private SemanticReusePlanner.Investigation investigation(long installationId, String reviewRunId, boolean lock) {
        String suffix = lock ? " FOR UPDATE OF analysis" : "";
        String value = jdbc.queryForObject("""
                SELECT analysis.reuse_investigation::text
                FROM semantic_review_analyses analysis
                JOIN review_runs run ON run.id=analysis.review_run_id
                JOIN github_repositories repository ON repository.id=run.github_repository_id
                WHERE analysis.review_run_id=?::uuid AND repository.installation_id=?
                """ + suffix, String.class, reviewRunId, installationId);
        if (value == null || value.equals("{}")) throw new EmptyResultDataAccessException(1);
        return fromJson(value, SemanticReusePlanner.Investigation.class);
    }

    private StoredDecision current(long installationId, String reviewRunId) {
        List<StoredDecision> values = jdbc.query("""
                SELECT decision.id::text,decision.revision,decision.decision_payload::text,
                       option.option_payload::text,decision.actor,decision.created_at
                FROM reuse_decisions decision
                JOIN reuse_solution_options option ON option.reuse_decision_id=decision.id
                JOIN review_runs run ON run.id=decision.review_run_id
                JOIN github_repositories repository ON repository.id=run.github_repository_id
                WHERE decision.review_run_id=?::uuid AND decision.superseded_at IS NULL
                  AND repository.installation_id=?
                """, (result, ignored) -> {
            ReuseDecisionService.ValidatedDecision payload = fromJson(result.getString(3),
                    ReuseDecisionService.ValidatedDecision.class);
            ReuseDecisionService.SelectedOption option = fromJson(result.getString(4),
                    ReuseDecisionService.SelectedOption.class);
            if (!payload.selectedOption().equals(option)) {
                throw new IllegalStateException("Persisted reuse decision and solution option do not match");
            }
            return new StoredDecision(result.getString(1), result.getInt(2), payload, result.getString(5),
                    result.getTimestamp(6).toInstant());
        }, reviewRunId, installationId);
        return values.isEmpty() ? null : values.get(0);
    }

    private String toJson(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception exception) { throw new IllegalStateException("Unable to serialize reuse planning audit", exception); }
    }

    private <T> T fromJson(String value, Class<T> type) {
        try { return json.readValue(value, type); }
        catch (Exception exception) { throw new IllegalStateException("Unable to read reuse planning audit", exception); }
    }

    public record PlanningView(SemanticReusePlanner.Investigation investigation, int currentRevision,
                               StoredDecision currentDecision) {}
    public record StoredDecision(String id, int revision, ReuseDecisionService.ValidatedDecision decision,
                                 String actor, Instant createdAt) {}
    public static final class DecisionConflictException extends RuntimeException {
        public DecisionConflictException(String message) { super(message); }
    }
}
