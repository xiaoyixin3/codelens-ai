package ai.codelens.semantic;

import ai.codelens.contracts.Models;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;

@Component
@Profile("worker")
public final class JdbcSemanticReviewAuditStore implements SemanticReviewAuditStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;

    public JdbcSemanticReviewAuditStore(JdbcTemplate jdbc, DataSource dataSource, ObjectMapper json) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        this.json = json;
    }

    @Override
    public void save(String reviewRunId, long repositoryId, SemanticModels.Index base, SemanticModels.Index head,
                     Models.ImpactSummary impact, Models.Coverage coverage) {
        if (reviewRunId == null || reviewRunId.isBlank() || repositoryId <= 0
                || !base.adapterVersion().equals(head.adapterVersion())) {
            throw new IllegalArgumentException("Semantic review audit provenance is invalid");
        }
        transactions.executeWithoutResult(ignored -> {
            String baseSnapshot = snapshot(repositoryId, base);
            String headSnapshot = snapshot(repositoryId, head);
            jdbc.update("""
                    INSERT INTO semantic_review_analyses (
                      review_run_id,base_snapshot_id,head_snapshot_id,adapter_version,
                      analysis_level,execution_level,impact,coverage
                    ) VALUES (?::uuid,?::uuid,?::uuid,?,?,?,?::jsonb,?::jsonb)
                    ON CONFLICT (review_run_id) DO UPDATE SET
                      base_snapshot_id=EXCLUDED.base_snapshot_id,
                      head_snapshot_id=EXCLUDED.head_snapshot_id,
                      adapter_version=EXCLUDED.adapter_version,
                      analysis_level=EXCLUDED.analysis_level,
                      execution_level=EXCLUDED.execution_level,
                      impact=EXCLUDED.impact,
                      coverage=EXCLUDED.coverage,
                      updated_at=now()
                    """, reviewRunId, baseSnapshot, headSnapshot, head.adapterVersion(),
                    coverage.analysisLevel(), coverage.executionLevel(), toJson(impact), toJson(coverage));
        });
    }

    private String snapshot(long repositoryId, SemanticModels.Index index) {
        String id = jdbc.queryForObject("""
                SELECT id::text FROM repository_snapshots
                WHERE github_repository_id=? AND commit_sha=? AND adapter_version=? AND build_model_hash=? AND status='ready'
                """, String.class, repositoryId, index.commitSha(), index.adapterVersion(), index.buildModelHash());
        if (id == null) throw new IllegalStateException("Semantic snapshot provenance is missing");
        return id;
    }

    private String toJson(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception exception) { throw new IllegalStateException("Unable to serialize semantic review audit", exception); }
    }
}
