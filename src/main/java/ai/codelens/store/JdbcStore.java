package ai.codelens.store;

import ai.codelens.contracts.Models;
import ai.codelens.intelligence.CodeIntelligenceService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcStore {
    public record CreateReviewInput(
            long repositoryId, int pullNumber, String baseSha, String headSha,
            String pipelineVersion, String configHash, String trigger, String requestKey
    ) {}
    public record FindingFeedbackInput(
            long repositoryId, int pullNumber, String fingerprintPrefix,
            String verdict, String actorLogin, long sourceCommentId
    ) {}
    public record CreatedRun(Models.ReviewRun run, boolean created) {}
    public record LlmCall(
            String id, String reviewRunId, String provider, String model, String task,
            String promptHash, String status, int inputChars, int outputChars,
            Integer inputTokens, Integer outputTokens, int durationMs, Integer httpStatus,
            String errorCode, String errorDetail, Instant createdAt
    ) {}

    private static final RowMapper<Models.ReviewRun> RUN_MAPPER = (rs, ignored) -> new Models.ReviewRun(
            rs.getString("id"), rs.getLong("github_repository_id"), rs.getInt("pull_number"),
            rs.getString("base_sha"), rs.getString("head_sha"), rs.getString("status"),
            rs.getString("pipeline_version"), rs.getString("config_hash"), rs.getString("trigger"),
            rs.getString("request_key"), rs.getString("summary")
    );
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;

    public JdbcStore(JdbcTemplate jdbc, DataSource dataSource, ObjectMapper json) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        this.json = json;
    }

    public void ping() { jdbc.queryForObject("SELECT 1", Integer.class); }

    public boolean claimDelivery(String deliveryId, String event, String action, byte[] body) {
        String digest;
        try { digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body)); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
        return jdbc.update("""
                INSERT INTO webhook_deliveries (delivery_id,event,action,payload_hash,signature_valid)
                VALUES (?,?,NULLIF(?,''),?,true) ON CONFLICT (delivery_id) DO NOTHING
                """, deliveryId, event, action == null ? "" : action, digest) == 1;
    }

    public void markDeliveryProcessed(String deliveryId, String detail) {
        boolean failed = detail != null && !detail.isBlank();
        jdbc.update("UPDATE webhook_deliveries SET status=?,error_detail=?,processed_at=now() WHERE delivery_id=?",
                failed ? "failed" : "processed", failed ? detail : null, deliveryId);
    }

    public CreatedRun createOrGetAndEnqueue(CreateReviewInput raw, Models.ReviewJob originalJob) {
        return transactions.execute(status -> {
            String trigger = blankDefault(raw.trigger(), "webhook");
            String requestKey = blankDefault(raw.requestKey(), "automatic");
            jdbc.update("""
                    INSERT INTO github_installations (id,account_login,active) VALUES (?,?,true)
                    ON CONFLICT (id) DO UPDATE SET account_login=EXCLUDED.account_login,active=true,updated_at=now()
                    """, originalJob.installationId(), originalJob.owner());
            jdbc.update("DELETE FROM repository_model_policies WHERE github_repository_id=? AND installation_id<>?",
                    raw.repositoryId(), originalJob.installationId());
            jdbc.update("""
                    INSERT INTO github_repositories (id,installation_id,owner_login,name,selected,last_seen_at)
                    VALUES (?,?,?,?,true,now()) ON CONFLICT (id) DO UPDATE SET
                    installation_id=EXCLUDED.installation_id,owner_login=EXCLUDED.owner_login,name=EXCLUDED.name,
                    selected=true,last_seen_at=now(),updated_at=now()
                    """, raw.repositoryId(), originalJob.installationId(), originalJob.owner(), originalJob.repo());

            String id = UUID.randomUUID().toString();
            int inserted = jdbc.update("""
                    INSERT INTO review_runs (id,github_repository_id,pull_number,base_sha,head_sha,status,
                    pipeline_version,config_hash,trigger,request_key)
                    VALUES (?::uuid,?,?,?,?,'queued',?,?,?,?)
                    ON CONFLICT (github_repository_id,pull_number,head_sha,pipeline_version,request_key) DO NOTHING
                    """, id, raw.repositoryId(), raw.pullNumber(), raw.baseSha(), raw.headSha(),
                    raw.pipelineVersion(), raw.configHash(), trigger, requestKey);
            Models.ReviewRun run = inserted == 1 ? getReviewRun(id) : jdbc.queryForObject("""
                    SELECT id::text,github_repository_id,pull_number,base_sha,head_sha,status,pipeline_version,
                    config_hash,trigger,request_key,summary::text FROM review_runs
                    WHERE github_repository_id=? AND pull_number=? AND head_sha=? AND pipeline_version=? AND request_key=? LIMIT 1
                    """, RUN_MAPPER, raw.repositoryId(), raw.pullNumber(), raw.headSha(), raw.pipelineVersion(), requestKey);
            if (inserted == 1) {
                Models.ReviewJob job = new Models.ReviewJob(run.id(), originalJob.installationId(), originalJob.owner(),
                        originalJob.repo(), originalJob.pullNumber(), originalJob.baseSha(), originalJob.headSha());
                if (!job.valid()) throw new IllegalArgumentException("invalid review job");
                jdbc.update("INSERT INTO review_jobs (id,review_run_id,payload) VALUES (?::uuid,?::uuid,?::jsonb)",
                        UUID.randomUUID().toString(), run.id(), toJson(job));
            }
            return new CreatedRun(run, inserted == 1);
        });
    }

    public Models.ReviewRun getReviewRun(String id) {
        return jdbc.queryForObject("""
                SELECT id::text,github_repository_id,pull_number,base_sha,head_sha,status,pipeline_version,
                config_hash,trigger,request_key,summary::text FROM review_runs WHERE id=?::uuid
                """, RUN_MAPPER, id);
    }

    public void updateReviewRun(String id, String status, String summary, String errorCode, String errorDetail) {
        jdbc.update("""
                UPDATE review_runs SET status=?,summary=?::jsonb,error_code=NULLIF(?,''),error_detail=NULLIF(?,''),
                started_at=CASE WHEN ?='in_progress' THEN COALESCE(started_at,now()) ELSE started_at END,
                completed_at=CASE WHEN ? IN ('completed','failed','stale','skipped') THEN now() ELSE completed_at END,
                updated_at=now() WHERE id=?::uuid
                """, status, summary, nullToEmpty(errorCode), nullToEmpty(errorDetail), status, status, id);
    }

    public void updateReviewRunConfig(String id, String hash) {
        jdbc.update("UPDATE review_runs SET config_hash=?,updated_at=now() WHERE id=?::uuid", hash, id);
    }

    public void savePolicy(long repositoryId, Models.RepositoryPolicy policy) {
        transactions.executeWithoutResult(status -> {
            jdbc.update("DELETE FROM project_rules WHERE github_repository_id=? AND source_commit_sha=?",
                    repositoryId, policy.sourceCommitSha());
            for (Models.PolicyRule rule : policy.rules()) {
                if (!rule.enabled()) continue;
                jdbc.update("""
                        INSERT INTO project_rules (id,github_repository_id,source,rule_key,content,scope_glob,severity,
                        enabled,source_commit_sha,config_hash) VALUES (?::uuid,?,'config',?,?,NULLIF(?,''),NULLIF(?,''),true,?,?)
                        """, UUID.randomUUID().toString(), repositoryId, rule.id(), rule.description(),
                        nullToEmpty(rule.scope()), nullToEmpty(rule.severity()), policy.sourceCommitSha(), policy.hash());
            }
        });
    }

    public void saveIntelligence(String reviewRunId, long repositoryId, String baseSha, CodeIntelligenceService.Result result) {
        transactions.executeWithoutResult(status -> {
            String baseSnapshotId = saveSnapshot(repositoryId, baseSha, result.base());
            String headSnapshotId = saveSnapshot(repositoryId, baseSha, result.head());
            String analysisId = UUID.randomUUID().toString();
            jdbc.update("""
                    INSERT INTO impact_analyses (id,review_run_id,base_snapshot_id,head_snapshot_id,max_depth,
                    blast_radius_score,risk_level,coverage) VALUES (?::uuid,?::uuid,?::uuid,?::uuid,2,?,?,?::jsonb)
                    ON CONFLICT (review_run_id) DO UPDATE SET base_snapshot_id=EXCLUDED.base_snapshot_id,
                    head_snapshot_id=EXCLUDED.head_snapshot_id,blast_radius_score=EXCLUDED.blast_radius_score,
                    risk_level=EXCLUDED.risk_level,coverage=EXCLUDED.coverage,updated_at=now() RETURNING id
                    """, analysisId, reviewRunId, baseSnapshotId, headSnapshotId, result.summary().score(),
                    result.summary().level(), toJson(Map.of("warning", result.summary().coverageWarning())));
            String storedAnalysis = jdbc.queryForObject("SELECT id::text FROM impact_analyses WHERE review_run_id=?::uuid", String.class, reviewRunId);
            jdbc.update("DELETE FROM symbol_changes WHERE impact_analysis_id=?::uuid", storedAnalysis);
            jdbc.update("DELETE FROM impact_paths WHERE impact_analysis_id=?::uuid", storedAnalysis);
            for (CodeIntelligenceService.Change change : result.changes()) {
                jdbc.update("""
                        INSERT INTO symbol_changes (id,impact_analysis_id,change_type,kind,qualified_name,before_stable_key,
                        after_stable_key,before_path,after_path,body_changed,signature_changed)
                        VALUES (?::uuid,?::uuid,?,?,?,?,?,?,?,?,?)
                        """, UUID.randomUUID().toString(), storedAnalysis, change.type(), change.kind(), change.qualifiedName(),
                        change.before() == null ? null : change.before().stableKey(), change.after() == null ? null : change.after().stableKey(),
                        change.before() == null ? null : change.before().path(), change.after() == null ? null : change.after().path(),
                        change.bodyChanged(), change.signatureChanged());
            }
            for (CodeIntelligenceService.Impact impact : result.paths()) {
                jdbc.update("""
                        INSERT INTO impact_paths (id,impact_analysis_id,changed_stable_key,impacted_stable_key,impacted_name,
                        impacted_path,impacted_kind,depth,score,path,evidence) VALUES
                        (?::uuid,?::uuid,?,?,?,?,?,?,?,?::jsonb,?::jsonb)
                        """, UUID.randomUUID().toString(), storedAnalysis, impact.changedStableKey(), impact.impacted().stableKey(),
                        impact.impacted().qualifiedName(), impact.impacted().path(), impact.impacted().kind(), impact.depth(), impact.score(),
                        toJson(impact.path()), toJson(impact.evidence()));
            }
        });
    }

    private String saveSnapshot(long repositoryId, String baseSha, CodeIntelligenceService.Snapshot snapshot) {
        jdbc.update("""
                INSERT INTO code_snapshots (id,github_repository_id,commit_sha,base_sha,parser_version,scope_hash,status,scope,coverage,completed_at)
                VALUES (?::uuid,?,?,?,?,?,'ready','pull_request_delta',?::jsonb,now())
                ON CONFLICT (github_repository_id,commit_sha,parser_version,scope_hash) DO UPDATE SET
                status='ready',coverage=EXCLUDED.coverage,completed_at=now(),updated_at=now()
                """, snapshot.id(), repositoryId, snapshot.commitSha(), baseSha, CodeIntelligenceService.PARSER_VERSION,
                snapshot.scopeHash(), toJson(Map.of("files", snapshot.files().size())));
        String storedId = jdbc.queryForObject("""
                SELECT id::text FROM code_snapshots WHERE github_repository_id=? AND commit_sha=? AND parser_version=? AND scope_hash=?
                """, String.class, repositoryId, snapshot.commitSha(), CodeIntelligenceService.PARSER_VERSION, snapshot.scopeHash());
        if (!storedId.equals(snapshot.id())) return storedId;
        for (CodeIntelligenceService.IndexedFile file : snapshot.files()) {
            jdbc.update("""
                    INSERT INTO indexed_files (snapshot_id,path,language,content_hash,status,skip_reason)
                    VALUES (?::uuid,?,?,NULLIF(?,''),?,NULLIF(?,'')) ON CONFLICT (snapshot_id,path) DO UPDATE SET
                    language=EXCLUDED.language,content_hash=EXCLUDED.content_hash,status=EXCLUDED.status,skip_reason=EXCLUDED.skip_reason
                    """, storedId, file.path(), file.language(), file.contentHash(), file.status(), file.skipReason());
        }
        for (CodeIntelligenceService.Symbol symbol : snapshot.symbols()) {
            jdbc.update("""
                    INSERT INTO code_symbols (id,snapshot_id,stable_key,path,kind,name,qualified_name,start_line,end_line,
                    signature,content_hash,exported,metadata) VALUES (?::uuid,?::uuid,?,?,?,?,?,?,?,?,?,?,?::jsonb)
                    ON CONFLICT (snapshot_id,stable_key) DO NOTHING
                    """, UUID.randomUUID().toString(), storedId, symbol.stableKey(), symbol.path(), symbol.kind(), symbol.name(),
                    symbol.qualifiedName(), symbol.startLine(), symbol.endLine(), symbol.signature(), symbol.contentHash(), symbol.exported(),
                    toJson(Map.of("parser", CodeIntelligenceService.PARSER_VERSION)));
        }
        for (CodeIntelligenceService.Edge edge : snapshot.edges()) {
            jdbc.update("""
                    INSERT INTO code_edges (id,snapshot_id,from_stable_key,to_stable_key,type,confidence,source_path,source_line)
                    VALUES (?::uuid,?::uuid,?,?,?,?,?,?) ON CONFLICT DO NOTHING
                    """, UUID.randomUUID().toString(), storedId, edge.fromStableKey(), edge.toStableKey(), edge.type(), edge.confidence(),
                    edge.sourcePath(), edge.sourceLine());
        }
        return storedId;
    }

    public void savePublication(Models.Publication publication) {
        jdbc.update("""
                INSERT INTO publications (id,review_run_id,head_sha,check_run_id,summary_comment_id)
                VALUES (?::uuid,?::uuid,?,?,?) ON CONFLICT (review_run_id) DO UPDATE SET
                head_sha=EXCLUDED.head_sha,check_run_id=COALESCE(EXCLUDED.check_run_id,publications.check_run_id),
                summary_comment_id=COALESCE(EXCLUDED.summary_comment_id,publications.summary_comment_id),updated_at=now()
                """, UUID.randomUUID().toString(), publication.reviewRunId(), publication.headSha(),
                publication.checkRunId(), publication.summaryCommentId());
    }

    public Optional<Models.Publication> getPublication(String reviewRunId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT review_run_id::text,head_sha,check_run_id,summary_comment_id FROM publications WHERE review_run_id=?::uuid
                    """, (rs, ignored) -> new Models.Publication(rs.getString(1), rs.getString(2), nullableLong(rs, 3), nullableLong(rs, 4)), reviewRunId));
        } catch (EmptyResultDataAccessException ignored) { return Optional.empty(); }
    }

    public boolean saveFindingFeedback(FindingFeedbackInput input) {
        return jdbc.update("""
                WITH matched AS (
                  SELECT f.id FROM findings f JOIN review_runs r ON r.id=f.review_run_id
                  WHERE r.github_repository_id=? AND r.pull_number=? AND f.fingerprint LIKE ? AND f.status='verified'
                  ORDER BY r.created_at DESC LIMIT 1
                ) INSERT INTO finding_feedback (id,finding_id,verdict,actor_login,source_comment_id)
                SELECT ?::uuid,matched.id,?,?,? FROM matched ON CONFLICT (source_comment_id) DO NOTHING
                """, input.repositoryId(), input.pullNumber(), input.fingerprintPrefix() + "%",
                UUID.randomUUID().toString(), input.verdict(), input.actorLogin(), input.sourceCommentId()) == 1;
    }

    public void saveFindings(String reviewRunId, List<Models.Finding> findings) {
        transactions.executeWithoutResult(status -> {
            for (Models.Finding finding : findings) {
                String findingId = UUID.randomUUID().toString();
                List<String> ids = jdbc.query("""
                        INSERT INTO findings (id,review_run_id,fingerprint,source,rule_id,category,severity,confidence,
                        title,claim,suggestion,verification,status,rejection_reason,published)
                        VALUES (?::uuid,?::uuid,?,?,NULLIF(?,''),?,?,?,?,?,?,?,?,NULL,?)
                        ON CONFLICT (review_run_id,fingerprint) DO UPDATE SET confidence=EXCLUDED.confidence,
                        status=EXCLUDED.status,published=EXCLUDED.published RETURNING id::text
                        """, (rs, ignored) -> rs.getString(1), findingId, reviewRunId, finding.fingerprint(), finding.source(),
                        nullToEmpty(finding.ruleId()), finding.category(), finding.severity(), finding.confidence(), finding.title(),
                        finding.claim(), finding.suggestion(), finding.verification(), finding.status(),
                        finding.publishable() && "verified".equals(finding.status()));
                findingId = ids.get(0);
                if (finding.evidence() != null) {
                    jdbc.update("DELETE FROM finding_evidence WHERE finding_id=?::uuid", findingId);
                    Models.FindingEvidence evidence = finding.evidence();
                    jdbc.update("""
                            INSERT INTO finding_evidence (id,finding_id,path,start_line,end_line,side,excerpt_hash,evidence_type)
                            VALUES (?::uuid,?::uuid,?,?,?,?,?,?)
                            """, UUID.randomUUID().toString(), findingId, evidence.path(), evidence.startLine(), evidence.endLine(),
                            evidence.side(), evidence.excerptHash(), evidence.evidenceType());
                }
            }
        });
    }

    public Optional<Models.ClaimedJob> claimJob() {
        return transactions.execute(status -> {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT id::text,payload::text,attempts FROM review_jobs
                    WHERE status IN ('queued','retry') AND available_at<=now()
                    ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT 1
                    """);
            if (rows.isEmpty()) return Optional.empty();
            Map<String, Object> row = rows.get(0);
            String id = row.get("id").toString();
            Models.ReviewJob payload = fromJson(row.get("payload").toString(), Models.ReviewJob.class);
            jdbc.update("UPDATE review_jobs SET status='processing',locked_at=now(),updated_at=now() WHERE id=?::uuid", id);
            return Optional.of(new Models.ClaimedJob(id, payload, ((Number) row.get("attempts")).intValue()));
        });
    }

    public void completeJob(String id) {
        jdbc.update("UPDATE review_jobs SET status='completed',locked_at=NULL,updated_at=now() WHERE id=?::uuid", id);
    }

    public void recoverStaleJobs() {
        jdbc.update("""
                UPDATE review_jobs SET status='retry',locked_at=NULL,available_at=now(),last_error='worker lease expired',updated_at=now()
                WHERE status='processing' AND locked_at < now()-interval '15 minutes'
                """);
    }

    public boolean retryJob(String id, int attempts, String detail) {
        int next = attempts + 1;
        boolean terminal = next >= 3;
        int delaySeconds = terminal ? 0 : (1 << attempts) * 2;
        jdbc.update("""
                UPDATE review_jobs SET status=?,attempts=?,last_error=?,available_at=now()+(? || ' seconds')::interval,
                locked_at=NULL,updated_at=now() WHERE id=?::uuid
                """, terminal ? "failed" : "retry", next, detail, delaySeconds, id);
        return terminal;
    }

    public void recordLlmCall(LlmCall call) {
        jdbc.update("""
                INSERT INTO llm_calls (id,review_run_id,provider,model,task,prompt_hash,status,input_chars,output_chars,
                input_tokens,output_tokens,duration_ms,http_status,error_code,error_detail,created_at)
                VALUES (?::uuid,NULLIF(?,'')::uuid,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, call.id(), call.reviewRunId(), call.provider(), call.model(), call.task(), call.promptHash(), call.status(),
                call.inputChars(), call.outputChars(), call.inputTokens(), call.outputTokens(), call.durationMs(), call.httpStatus(),
                nullToEmpty(call.errorCode()), nullToEmpty(call.errorDetail()), call.createdAt());
    }

    private String toJson(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new IllegalStateException(exception); }
    }

    private <T> T fromJson(String value, Class<T> type) {
        try { return json.readValue(value, type); }
        catch (JsonProcessingException exception) { throw new IllegalStateException(exception); }
    }

    private static Long nullableLong(ResultSet rs, int index) throws SQLException {
        long value = rs.getLong(index);
        return rs.wasNull() ? null : value;
    }
    private static String blankDefault(String value, String fallback) { return value == null || value.isBlank() ? fallback : value; }
    private static String nullToEmpty(String value) { return value == null ? "" : value; }
}
