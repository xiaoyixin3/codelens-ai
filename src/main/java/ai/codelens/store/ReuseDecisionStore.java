package ai.codelens.store;

import ai.codelens.semantic.ReuseDecisionService;
import ai.codelens.semantic.SemanticReusePlanner;
import ai.codelens.semantic.LocalPatchPreview;
import ai.codelens.semantic.SemanticModels;
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
public class ReuseDecisionStore {
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

    /** One bounded read snapshot. Never hold it while parsing source or generating a preview. */
    public LocalPatchPreview.Context previewContext(long installationId, String reviewRunId,
                                                    String decisionId, int revision, List<String> paths) {
        if(installationId<1 || revision<1 || paths==null || paths.isEmpty() || paths.size()>3) {
            throw new IllegalArgumentException("preview_identity_or_file_limit");
        }
        UUID.fromString(reviewRunId); UUID.fromString(decisionId); paths.forEach(LocalPatchPreview::safePath);
        var read=new TransactionTemplate(transactions.getTransactionManager());
        read.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        read.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        read.setReadOnly(true); read.setTimeout(5);
        return read.execute(status->{
            jdbc.execute("SET LOCAL statement_timeout='4s'");
            var view=get(installationId,reviewRunId); var stored=view.currentDecision();
            if(stored==null || !stored.id().equals(decisionId) || stored.revision()!=revision) {
                throw new DecisionConflictException("approved_decision_changed_or_missing");
            }
            var decision=LocalPatchPreview.validated(view); var provenance=decision.provenance();
            if(paths.stream().anyMatch(path->!decision.selectedOption().expectedFiles().contains(path))) {
                throw new IllegalArgumentException("preview_file_outside_approved_option");
            }
            var row=jdbc.queryForMap("""
                    SELECT run.base_sha,run.head_sha,head.id::text AS snapshot_id,
                      base.commit_sha AS indexed_base,head.commit_sha AS indexed_head,
                      base.adapter_version AS base_adapter,head.adapter_version AS head_adapter,
                      base.build_model_hash AS base_model,head.build_model_hash AS head_model
                    FROM review_runs run
                    JOIN github_repositories repository ON repository.id=run.github_repository_id
                    JOIN github_installations installation ON installation.id=repository.installation_id
                    JOIN semantic_review_analyses analysis ON analysis.review_run_id=run.id
                    JOIN repository_snapshots base ON base.id=analysis.base_snapshot_id AND base.status='ready'
                    JOIN repository_snapshots head ON head.id=analysis.head_snapshot_id AND head.status='ready'
                    WHERE run.id=?::uuid AND repository.installation_id=? AND repository.selected=true
                      AND installation.active=true AND run.status='completed'
                      AND base.github_repository_id=repository.id AND head.github_repository_id=repository.id
                      AND NOT EXISTS (SELECT 1 FROM review_runs newer WHERE newer.github_repository_id=run.github_repository_id
                        AND newer.pull_number=run.pull_number AND (newer.created_at,newer.id)>(run.created_at,run.id))
                    """,reviewRunId,installationId);
            if(!provenance.baseSha().equals(row.get("base_sha")) || !provenance.headSha().equals(row.get("head_sha"))
                    || !provenance.baseSha().equals(row.get("indexed_base")) || !provenance.headSha().equals(row.get("indexed_head"))
                    || !provenance.adapterVersion().equals(row.get("base_adapter")) || !provenance.adapterVersion().equals(row.get("head_adapter"))
                    || !provenance.baseBuildModelHash().equals(row.get("base_model")) || !provenance.headBuildModelHash().equals(row.get("head_model"))) {
                throw new DecisionConflictException("preview_index_provenance_changed");
            }
            List<LocalPatchPreview.FileContext> files=new java.util.ArrayList<>();
            for(String path:paths) {
                String hash=jdbc.queryForObject("""
                        SELECT content_hash FROM semantic_index_files WHERE snapshot_id=?::uuid AND path=?
                        AND status='indexed' AND language='java'
                        """,String.class,row.get("snapshot_id"),path);
                if(hash==null || !hash.matches("[0-9a-f]{64}")) throw new DecisionConflictException("preview_indexed_source_required");
                var symbols=jdbc.query("""
                        SELECT stable_key,kind,qualified_name,signature,path,start_line,end_line,test_source,type_resolved
                        FROM semantic_symbols WHERE snapshot_id=?::uuid AND path=? ORDER BY start_line,stable_key LIMIT 201
                        """,(result,ignored)->new SemanticModels.Symbol(result.getString(1),SemanticModels.SymbolKind.valueOf(result.getString(2)),
                        result.getString(3),result.getString(4),result.getString(5),result.getInt(6),result.getInt(7),result.getBoolean(8),result.getBoolean(9)),row.get("snapshot_id"),path);
                if(symbols.size()>200) throw new IllegalArgumentException("preview_symbol_context_limit");
                files.add(new LocalPatchPreview.FileContext(path,hash,symbols));
            }
            return new LocalPatchPreview.Context(reviewRunId,provenance.baseSha(),provenance.headSha(),view,files);
        });
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
