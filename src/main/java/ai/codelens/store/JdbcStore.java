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
import java.util.function.Supplier;

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
    public record DeliveryInput(String id, String event, String action, byte[] body) {}
    public record DeliveryResult(String status, String runId) {}
    public record ProcessedDelivery(boolean duplicate, DeliveryResult result) {}
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

    /** Only verified input; handler must do local database work, never network calls. */
    public ProcessedDelivery processDelivery(DeliveryInput input, Supplier<DeliveryResult> handler) {
        String hash = deliveryHash(input.body());
        String action = input.action() == null ? "" : input.action();
        return transactions.execute(transaction -> {
            deliveryTimeouts();
            jdbc.update("""
                    INSERT INTO webhook_deliveries (delivery_id,event,action,payload_hash,signature_valid)
                    VALUES (?,?,NULLIF(?,''),?,true) ON CONFLICT (delivery_id) DO NOTHING
                    """, input.id(), input.event(), action, hash);
            Map<String, Object> row = jdbc.queryForMap("""
                    SELECT event,COALESCE(action,'') AS action,payload_hash,status,result_status,review_run_id::text
                    FROM webhook_deliveries WHERE delivery_id=? FOR UPDATE
                    """, input.id());
            if (!input.event().equals(row.get("event")) || !action.equals(row.get("action"))
                    || !hash.equals(row.get("payload_hash"))) throw new DeliveryConflictException();
            if (row.get("status").equals("processed")) return new ProcessedDelivery(true,
                    new DeliveryResult((String) row.get("result_status"), (String) row.get("review_run_id")));
            if (!java.util.Set.of("received", "failed").contains(row.get("status"))) {
                throw new IllegalStateException("Unsupported webhook delivery state");
            }
            DeliveryResult result = handler.get();
            if (result == null || result.status() == null || result.status().isBlank()) {
                throw new IllegalArgumentException("Webhook processing must return an outcome");
            }
            jdbc.update("""
                    UPDATE webhook_deliveries SET status='processed',error_detail=NULL,processed_at=clock_timestamp(),
                    result_status=?,review_run_id=NULLIF(?,'')::uuid WHERE delivery_id=?
                    """, result.status(), result.runId(), input.id());
            return new ProcessedDelivery(false, result);
        });
    }

    /** Best-effort audit after rollback; never downgrade a concurrent committed success. */
    public void recordDeliveryFailure(DeliveryInput input, String redactedDetail) {
        transactions.executeWithoutResult(transaction -> {
            deliveryTimeouts();
            jdbc.update("""
                INSERT INTO webhook_deliveries (delivery_id,event,action,payload_hash,signature_valid,status,error_detail)
                VALUES (?,?,NULLIF(?,''),?,true,'failed',?)
                ON CONFLICT (delivery_id) DO UPDATE SET status='failed',error_detail=EXCLUDED.error_detail,
                processed_at=NULL,result_status=NULL,review_run_id=NULL
                WHERE webhook_deliveries.status IN ('received','failed')
                AND webhook_deliveries.payload_hash=EXCLUDED.payload_hash
                AND webhook_deliveries.event=EXCLUDED.event
                AND COALESCE(webhook_deliveries.action,'')=COALESCE(EXCLUDED.action,'')
                """, input.id(), input.event(), input.action() == null ? "" : input.action(),
                deliveryHash(input.body()), redactedDetail);
        });
    }

    private void deliveryTimeouts() {
        jdbc.execute("SET LOCAL lock_timeout='5s'");
        jdbc.execute("SET LOCAL statement_timeout='10s'");
    }

    private static String deliveryHash(byte[] body) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body)); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    public CreatedRun createOrGetAndEnqueue(CreateReviewInput raw, Models.ReviewJob originalJob) {
        if (raw.pullNumber() != originalJob.pullNumber() || !raw.baseSha().equals(originalJob.baseSha())
                || !raw.headSha().equals(originalJob.headSha()) || !originalJob.valid()) {
            throw new IllegalArgumentException("Review input and job revision must match");
        }
        return withReviewPrLock(raw.repositoryId(), raw.pullNumber(), () -> {
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
                    pipeline_version,config_hash,trigger,request_key,enqueue_config_hash,created_at,updated_at)
                    VALUES (?::uuid,?,?,?,?,'queued',?,?,?,?,?,clock_timestamp(),clock_timestamp())
                    ON CONFLICT (github_repository_id,pull_number,base_sha,head_sha,enqueue_config_hash,pipeline_version,request_key) DO NOTHING
                    """, id, raw.repositoryId(), raw.pullNumber(), raw.baseSha(), raw.headSha(),
                    raw.pipelineVersion(), raw.configHash(), trigger, requestKey, raw.configHash());
            Models.ReviewRun run = inserted == 1 ? getReviewRun(id) : jdbc.queryForObject("""
                    SELECT id::text,github_repository_id,pull_number,base_sha,head_sha,status,pipeline_version,
                    config_hash,trigger,request_key,summary::text FROM review_runs
                    WHERE github_repository_id=? AND pull_number=? AND base_sha=? AND head_sha=?
                    AND enqueue_config_hash=? AND pipeline_version=? AND request_key=? LIMIT 1
                    """, RUN_MAPPER, raw.repositoryId(), raw.pullNumber(), raw.baseSha(), raw.headSha(),
                    raw.configHash(), raw.pipelineVersion(), requestKey);
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

    private static final int REVIEW_PR_LOCK_NAMESPACE = 0x434c5052;

    /** Shared lock order: PR mutex first, then run/job/journal rows. No remote work in action. */
    public <T> T withReviewPrLock(long repositoryId, int pullNumber, Supplier<T> action) {
        if (repositoryId < 1 || pullNumber < 1 || action == null) throw new IllegalArgumentException("Valid review PR scope required");
        int key = reviewPrLockKey(repositoryId, pullNumber);
        return transactions.execute(status -> {
            requirePrReadCommitted();
            deliveryTimeouts();
            jdbc.query("SELECT pg_advisory_xact_lock(?,?)", (org.springframework.jdbc.core.RowCallbackHandler) rs -> {},
                    REVIEW_PR_LOCK_NAMESPACE, key);
            return action.get();
        });
    }

    /** Future recovery must invoke this under the same PR lock; inspection alone is not sufficient. */
    public void requireLatestReviewRunLocked(String runId, long repositoryId, int pullNumber) {
        if (repositoryId < 1 || pullNumber < 1 || runId == null) throw new IllegalArgumentException("Valid review PR scope required");
        UUID.fromString(runId);
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()
                || !org.springframework.transaction.support.TransactionSynchronizationManager.hasResource(jdbc.getDataSource())) {
            throw new IllegalStateException("Latest-run verification requires the PR transaction");
        }
        Integer held = jdbc.queryForObject("""
                SELECT count(*) FROM pg_locks WHERE locktype='advisory' AND pid=pg_backend_pid()
                  AND classid::bigint=? AND objid::bigint=? AND objsubid=2 AND granted AND mode='ExclusiveLock'
                """, Integer.class, REVIEW_PR_LOCK_NAMESPACE, Integer.toUnsignedLong(reviewPrLockKey(repositoryId, pullNumber)));
        if (held == null || held != 1) throw new IllegalStateException("Matching PR mutex is not held");
        requirePrReadCommitted();
        Integer latest = jdbc.queryForObject("""
                SELECT count(*) FROM review_runs r WHERE r.id=?::uuid AND r.github_repository_id=? AND r.pull_number=?
                  AND NOT EXISTS (SELECT 1 FROM review_runs n WHERE n.github_repository_id=r.github_repository_id
                    AND n.pull_number=r.pull_number AND (n.created_at,n.id)>(r.created_at,r.id))
                """, Integer.class, runId, repositoryId, pullNumber);
        if (latest == null || latest != 1) throw new IllegalStateException("Review run is missing, mismatched or superseded");
    }

    private static int reviewPrLockKey(long repositoryId, int pullNumber) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    ("codelens-review-pr-v1:" + repositoryId + ":" + pullNumber).getBytes(StandardCharsets.UTF_8));
            return java.nio.ByteBuffer.wrap(digest).getInt();
        } catch (java.security.NoSuchAlgorithmException unavailable) { throw new IllegalStateException(unavailable); }
    }

    private void requirePrReadCommitted() {
        if (!"read committed".equals(jdbc.queryForObject("SHOW transaction_isolation", String.class))) {
            throw new IllegalStateException("PR admission requires READ COMMITTED isolation");
        }
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
                    risk_level=EXCLUDED.risk_level,coverage=EXCLUDED.coverage,updated_at=now()
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

    /** Webhook seed only: a retry must not overwrite a Worker's newer publication. */
    public void seedPublication(Models.Publication publication) {
        jdbc.update("""
                INSERT INTO publications (id,review_run_id,head_sha,check_run_id,summary_comment_id)
                VALUES (?::uuid,?::uuid,?,?,?) ON CONFLICT (review_run_id) DO NOTHING
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

    public record CheckPublicationEffect(String requestHash, String state, Long remoteId) {}

    public void requireRecoveryAuditDataSource(PublicationRecoveryAuditStore audit) {
        audit.requireDataSource(jdbc.getDataSource());
    }

    /** Internal recovery snapshot. Remote IDs must come from fresh authenticated read-only inspection. */
    public ai.codelens.review.RecoveryApprovalService.Binding recoveryBinding(String runId, long checkId, long commentId, boolean lock) {
        if (lock) {
            var run = getReviewRun(runId);
            requireLatestReviewRunLocked(runId, run.repositoryId(), run.pullNumber());
            jdbc.query("SELECT r.id FROM review_runs r JOIN review_jobs j ON j.review_run_id=r.id WHERE r.id=?::uuid FOR UPDATE OF r,j",
                    (org.springframework.jdbc.core.RowCallbackHandler) rs -> {}, runId);
            for (String table : List.of("frozen_review_outputs", "check_publication_effects", "publications")) {
                jdbc.query("SELECT review_run_id FROM " + table + " WHERE review_run_id=?::uuid FOR UPDATE",
                        (org.springframework.jdbc.core.RowCallbackHandler) rs -> {}, runId);
            }
            jdbc.query("SELECT active_run_id FROM summary_publication_slots WHERE active_run_id=?::uuid FOR UPDATE",
                    (org.springframework.jdbc.core.RowCallbackHandler) rs -> {}, runId);
        }
        return jdbc.query("""
                SELECT r.id,r.github_repository_id,r.pull_number,r.base_sha,r.head_sha,r.pipeline_version,
                  j.id AS job_id,j.payload::text,j.lease_generation,f.payload_hash,
                  a.request_hash AS start_hash,b.request_hash AS result_hash,c.request_hash AS summary_hash,
                  s.active_run_id,p.check_run_id,p.summary_comment_id
                FROM review_runs r JOIN review_jobs j ON j.review_run_id=r.id
                JOIN frozen_review_outputs f ON f.review_run_id=r.id
                JOIN check_publication_effects a ON a.review_run_id=r.id AND a.operation='check_start'
                JOIN check_publication_effects b ON b.review_run_id=r.id AND b.operation='check_result'
                JOIN check_publication_effects c ON c.review_run_id=r.id AND c.operation='summary_comment'
                JOIN summary_publication_slots s ON s.active_run_id=r.id AND s.repository_id=r.github_repository_id
                  AND s.pull_number=r.pull_number AND s.installation_id=(j.payload->>'installationId')::bigint
                LEFT JOIN publications p ON p.review_run_id=r.id
                WHERE r.id=?::uuid AND r.status='failed' AND r.error_code='PUBLICATION_UNCERTAIN'
                  AND j.status='failed' AND j.locked_at IS NULL AND j.lease_expires_at IS NULL
                  AND f.installation_id=s.installation_id AND f.schema_version=1
                  AND a.state='confirmed' AND a.remote_id=?
                  AND (b.remote_id IS NULL OR b.remote_id=?) AND (c.remote_id IS NULL OR c.remote_id=?)
                  AND (p.review_run_id IS NULL OR p.head_sha=r.head_sha)
                """, (rs, ignored) -> {
                    Models.ReviewJob job = fromJson(rs.getString("payload"), Models.ReviewJob.class);
                    if (!job.valid() || !runId.equals(job.reviewRunId()) || job.pullNumber()!=rs.getInt("pull_number")
                            || !job.baseSha().equals(rs.getString("base_sha")) || !job.headSha().equals(rs.getString("head_sha"))) {
                        throw new IllegalStateException("Recovery job identity changed");
                    }
                    return new ai.codelens.review.RecoveryApprovalService.Binding(UUID.fromString(runId),
                            rs.getObject("job_id", UUID.class), job.installationId(), rs.getLong("github_repository_id"), job.pullNumber(),
                            job.owner(), job.repo(), job.baseSha(), job.headSha(), rs.getString("pipeline_version"),
                            rs.getString("payload_hash"), rs.getString("start_hash"), rs.getString("result_hash"), rs.getString("summary_hash"),
                            checkId, commentId, rs.getLong("lease_generation"), rs.getObject("active_run_id", UUID.class),
                            nullableLong(rs, rs.findColumn("check_run_id")), nullableLong(rs, rs.findColumn("summary_comment_id")));
                }, runId, checkId, checkId, commentId).stream().findFirst().orElseThrow(() -> new IllegalStateException("Recovery state changed"));
    }

    /** Requires PR mutex, matching locked snapshot, and caller-verified approval/frozen output. Never enqueues. */
    public void confirmRecoveryLocally(ai.codelens.review.RecoveryApprovalService.Binding expected, String summaryJson,
                                      PublicationRecoveryAuditStore audit, PublicationRecoveryAuditStore.Attempt attempt) {
        audit.requireDataSource(jdbc.getDataSource());
        String runId = expected.runId().toString();
        if (!expected.runId().equals(attempt.runId())
                || !expected.equals(recoveryBinding(runId, expected.checkId(), expected.commentId(), true))) {
            throw new IllegalStateException("Recovery state changed");
        }
        confirmCheckPublication(runId, "check_start", expected.startHash(), expected.checkId());
        confirmCheckPublication(runId, "check_result", expected.resultHash(), expected.checkId());
        confirmCheckPublication(runId, "summary_comment", expected.summaryHash(), expected.commentId());
        savePublication(new Models.Publication(runId, expected.headSha(), expected.checkId(), expected.commentId()));
        updateReviewRun(runId, "completed", summaryJson, "", "");
        int completed = jdbc.update("""
                UPDATE review_jobs SET status='completed',lease_generation=lease_generation+1,
                  locked_at=NULL,lease_expires_at=NULL,last_error=NULL,updated_at=clock_timestamp()
                WHERE id=? AND review_run_id=? AND status='failed' AND lease_generation=?
                  AND locked_at IS NULL AND lease_expires_at IS NULL
                """, expected.jobId(), expected.runId(), expected.leaseGeneration());
        int released = jdbc.update("""
                DELETE FROM summary_publication_slots WHERE repository_id=? AND installation_id=? AND pull_number=? AND active_run_id=?
                """, expected.repositoryId(), expected.installationId(), expected.pullNumber(), expected.runId());
        if (completed != 1 || released != 1) throw new IllegalStateException("Recovery state changed");
        audit.committedWithinTransaction(attempt);
    }

    public record PublicationInspectionInput(Models.ReviewJob job, String queueStatus, String errorCode,
                                             boolean ownsSummarySlot, boolean latestRun) {}

    public PublicationInspectionInput getPublicationInspectionInput(String runId) {
        return jdbc.queryForObject("""
                SELECT j.payload::text,j.status,COALESCE(r.error_code,''),
                EXISTS(SELECT 1 FROM summary_publication_slots s WHERE s.active_run_id=r.id),
                NOT EXISTS(SELECT 1 FROM review_runs n WHERE n.github_repository_id=r.github_repository_id
                  AND n.pull_number=r.pull_number AND (n.created_at,n.id)>(r.created_at,r.id))
                FROM review_jobs j JOIN review_runs r ON r.id=j.review_run_id WHERE r.id=?::uuid
                """, (rs, ignored) -> new PublicationInspectionInput(fromJson(rs.getString(1), Models.ReviewJob.class),
                rs.getString(2), rs.getString(3), rs.getBoolean(4), rs.getBoolean(5)), runId);
    }

    public Optional<CheckPublicationEffect> getCheckPublicationEffect(String runId, String operation) {
        return jdbc.query("""
                SELECT request_hash,state,remote_id FROM check_publication_effects
                WHERE review_run_id=?::uuid AND operation=?
                """, (rs, ignored) -> new CheckPublicationEffect(rs.getString(1), rs.getString(2), nullableLong(rs, 3)),
                runId, operation).stream().findFirst();
    }

    public record FrozenOutput(long installationId, int schemaVersion, String payloadHash, String encryptedPayload) {}

    public Optional<FrozenOutput> getFrozenReviewOutput(String runId) {
        return jdbc.query("""
                SELECT installation_id,schema_version,payload_hash,encrypted_payload FROM frozen_review_outputs
                WHERE review_run_id=?::uuid
                """, (rs, ignored) -> new FrozenOutput(rs.getLong(1), rs.getInt(2), rs.getString(3), rs.getString(4)),
                runId).stream().findFirst();
    }

    /** Call inside withJobLease; first ciphertext wins, never update or retain raw output. */
    public void freezeReviewOutput(Models.ReviewJob job, long repositoryId, String hash, String encrypted) {
        jdbc.update("""
                INSERT INTO frozen_review_outputs(review_run_id,installation_id,schema_version,payload_hash,encrypted_payload)
                SELECT r.id,?,1,?,? FROM review_runs r JOIN review_jobs j ON j.review_run_id=r.id
                WHERE r.id=?::uuid AND r.github_repository_id=? AND r.pull_number=?
                AND r.base_sha=? AND r.head_sha=? AND r.status='in_progress'
                AND j.payload->>'installationId'=? AND j.payload->>'owner'=? AND j.payload->>'repo'=?
                ON CONFLICT (review_run_id) DO NOTHING
                """, job.installationId(), hash, encrypted, job.reviewRunId(), repositoryId, job.pullNumber(),
                job.baseSha(), job.headSha(), Long.toString(job.installationId()), job.owner(), job.repo());
        FrozenOutput stored = jdbc.query("""
                SELECT f.installation_id,f.schema_version,f.payload_hash,f.encrypted_payload
                FROM frozen_review_outputs f JOIN review_runs r ON r.id=f.review_run_id
                JOIN review_jobs j ON j.review_run_id=r.id
                WHERE r.id=?::uuid AND r.github_repository_id=? AND r.pull_number=?
                AND r.base_sha=? AND r.head_sha=? AND r.status='in_progress'
                AND j.payload->>'installationId'=? AND j.payload->>'owner'=? AND j.payload->>'repo'=?
                """, (rs, ignored) -> new FrozenOutput(rs.getLong(1), rs.getInt(2), rs.getString(3), rs.getString(4)),
                job.reviewRunId(), repositoryId, job.pullNumber(), job.baseSha(), job.headSha(),
                Long.toString(job.installationId()), job.owner(), job.repo()).stream().findFirst().orElseThrow(() ->
                new ai.codelens.github.PublicationUncertainException("ARTIFACT", "Frozen output identity does not match its run", null));
        if (stored.installationId() != job.installationId() || stored.schemaVersion() != 1 || !stored.payloadHash().equals(hash)) {
            throw new ai.codelens.github.PublicationUncertainException("ARTIFACT", "Frozen output already exists with different content", null);
        }
    }

    /** Called inside a fenced short transaction, before sending any remote mutation. */
    public void beginCheckPublication(String runId, String operation, String hash) {
        int inserted = jdbc.update("""
                INSERT INTO check_publication_effects(review_run_id,operation,request_hash,state)
                VALUES (?::uuid,?,?,'sent') ON CONFLICT DO NOTHING
                """, runId, operation, hash);
        if (inserted != 1) throw new ai.codelens.github.PublicationUncertainException("JOURNAL", operation,
                new IllegalStateException("Publication intent already exists; reconcile before sending"));
    }

    public void claimSummaryPublication(Models.ReviewJob job) {
        int acquired = jdbc.update("""
                INSERT INTO summary_publication_slots(repository_id,installation_id,pull_number,active_run_id)
                SELECT r.github_repository_id,?,r.pull_number,r.id FROM review_runs r WHERE r.id=?::uuid
                AND NOT EXISTS (SELECT 1 FROM review_runs newer WHERE newer.github_repository_id=r.github_repository_id
                  AND newer.pull_number=r.pull_number AND (newer.created_at,newer.id)>(r.created_at,r.id))
                ON CONFLICT (repository_id,installation_id,pull_number) DO UPDATE SET active_run_id=EXCLUDED.active_run_id
                WHERE summary_publication_slots.active_run_id=EXCLUDED.active_run_id
                """, job.installationId(), job.reviewRunId());
        if (acquired != 1) throw new ai.codelens.github.PublicationUncertainException("RESERVE", "summary-comment",
                new IllegalStateException("A newer review or another pending summary owns this PR"));
    }

    public void beginSummaryPublication(String runId, String hash, Long target) {
        int inserted = jdbc.update("""
                INSERT INTO check_publication_effects(review_run_id,operation,request_hash,state,remote_id)
                VALUES (?::uuid,'summary_comment',?,'sent',?) ON CONFLICT DO NOTHING
                """, runId, hash, target);
        if (inserted != 1) throw new ai.codelens.github.PublicationUncertainException("JOURNAL", "summary-comment", null);
    }

    public void releaseSummaryPublication(String runId) {
        jdbc.update("DELETE FROM summary_publication_slots WHERE active_run_id=?::uuid", runId);
    }

    public void confirmCheckPublication(String runId, String operation, String hash, long remoteId) {
        int updated = jdbc.update("""
                UPDATE check_publication_effects SET state='confirmed',remote_id=?,updated_at=clock_timestamp()
                WHERE review_run_id=?::uuid AND operation=? AND request_hash=?
                AND (remote_id IS NULL OR remote_id=?)
                """, remoteId, runId, operation, hash, remoteId);
        if (updated != 1) throw new ai.codelens.github.PublicationUncertainException("JOURNAL", operation,
                new IllegalStateException("Publication confirmation conflicts with stored intent"));
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
                    SELECT id::text,payload::text,attempts,lease_generation FROM review_jobs
                    WHERE status IN ('queued','retry') AND available_at<=clock_timestamp() AND attempts<3
                    ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT 1
                    """);
            if (rows.isEmpty()) return Optional.empty();
            Map<String, Object> row = rows.get(0);
            String id = row.get("id").toString();
            Models.ReviewJob payload = fromJson(row.get("payload").toString(), Models.ReviewJob.class);
            long generation = ((Number) row.get("lease_generation")).longValue() + 1;
            jdbc.update("""
                    UPDATE review_jobs SET status='processing',locked_at=clock_timestamp(),updated_at=clock_timestamp(),
                    lease_generation=?,lease_expires_at=clock_timestamp()+interval '120 seconds' WHERE id=?::uuid
                    """, generation, id);
            return Optional.of(new Models.ClaimedJob(id, payload, ((Number) row.get("attempts")).intValue(), generation));
        });
    }

    public void completeJob(Models.ClaimedJob job) {
        requireUpdated(jdbc.update("""
                UPDATE review_jobs SET status='completed',locked_at=NULL,lease_expires_at=NULL,updated_at=clock_timestamp()
                WHERE id=?::uuid AND review_run_id=?::uuid AND lease_generation=? AND status='processing'
                AND lease_expires_at>clock_timestamp()
                """, job.id(), job.payload().reviewRunId(), job.leaseGeneration()));
    }

    public boolean hasJobLease(Models.ClaimedJob job) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM review_jobs WHERE id=?::uuid AND review_run_id=?::uuid
                AND lease_generation=? AND status='processing' AND lease_expires_at>clock_timestamp())
                """, Boolean.class, job.id(), job.payload().reviewRunId(), job.leaseGeneration()));
    }

    public boolean renewJobLease(Models.ClaimedJob job) {
        return jdbc.update("""
                UPDATE review_jobs SET lease_expires_at=clock_timestamp()+interval '120 seconds',updated_at=clock_timestamp()
                WHERE id=?::uuid AND review_run_id=?::uuid AND lease_generation=? AND status='processing'
                AND lease_expires_at>clock_timestamp()
                """, job.id(), job.payload().reviewRunId(), job.leaseGeneration()) == 1;
    }

    public void withJobLease(Models.ClaimedJob job, Runnable action) {
        transactions.executeWithoutResult(status -> {
            List<String> owned = jdbc.queryForList("""
                    SELECT id::text FROM review_jobs WHERE id=?::uuid AND review_run_id=?::uuid AND lease_generation=?
                    AND status='processing' AND lease_expires_at>clock_timestamp() FOR UPDATE
                    """, String.class, job.id(), job.payload().reviewRunId(), job.leaseGeneration());
            if (owned.isEmpty()) throw new LeaseLostException();
            action.run();
            // Do not commit writes if a slow local transaction outlived its lease.
            if (!hasJobLease(job)) throw new LeaseLostException();
        });
    }

    public int recoverStaleJobs() {
        return transactions.execute(status -> {
            List<Map<String, Object>> expired = jdbc.queryForList("""
                    WITH expired AS (
                      SELECT id FROM review_jobs WHERE status='processing' AND lease_expires_at<=clock_timestamp()
                      ORDER BY lease_expires_at,id FOR UPDATE SKIP LOCKED LIMIT 100
                    )
                    UPDATE review_jobs j SET status=CASE WHEN j.attempts+1>=3 THEN 'failed' ELSE 'retry' END,
                    attempts=j.attempts+1,locked_at=NULL,lease_expires_at=NULL,available_at=clock_timestamp(),
                    last_error='worker lease expired',updated_at=clock_timestamp()
                    FROM expired e WHERE j.id=e.id RETURNING j.review_run_id::text,j.status
                    """);
            for (Map<String, Object> row : expired) {
                if (row.get("status").equals("failed")) updateReviewRun(row.get("review_run_id").toString(),
                        "failed", null, "WORKER_LEASE_EXPIRED", "Worker lease expired after three attempts.");
            }
            return expired.size();
        });
    }

    public void stopUncertainPublication(Models.ClaimedJob job, String detail) {
        transactions.executeWithoutResult(status -> {
            jdbc.execute("SET LOCAL lock_timeout='5s'");
            jdbc.execute("SET LOCAL statement_timeout='10s'");
            requireUpdated(jdbc.update("""
                    UPDATE review_jobs SET status='failed',last_error=?,locked_at=NULL,lease_expires_at=NULL,
                    updated_at=clock_timestamp() WHERE id=?::uuid AND review_run_id=?::uuid
                    AND lease_generation=? AND status='processing' AND lease_expires_at>clock_timestamp()
                    """, detail, job.id(), job.payload().reviewRunId(), job.leaseGeneration()));
            updateReviewRun(job.payload().reviewRunId(), "failed", null, "PUBLICATION_UNCERTAIN", detail);
        });
    }

    public boolean retryJob(Models.ClaimedJob job, String detail) {
        int next = job.attempts() + 1;
        boolean terminal = next >= 3;
        int delaySeconds = terminal ? 0 : (1 << job.attempts()) * 2;
        return transactions.execute(status -> {
            requireUpdated(jdbc.update("""
                    UPDATE review_jobs SET status=?,attempts=?,last_error=?,available_at=clock_timestamp()+(? || ' seconds')::interval,
                    locked_at=NULL,lease_expires_at=NULL,updated_at=clock_timestamp() WHERE id=?::uuid AND review_run_id=?::uuid
                    AND lease_generation=? AND status='processing' AND lease_expires_at>clock_timestamp() AND attempts=?
                    """, terminal ? "failed" : "retry", next, detail, delaySeconds, job.id(),
                    job.payload().reviewRunId(), job.leaseGeneration(), job.attempts()));
            if (terminal) updateReviewRun(job.payload().reviewRunId(), "failed", null, "WORKER_FAILED", detail);
            return terminal;
        });
    }

    private static void requireUpdated(int count) { if (count != 1) throw new LeaseLostException(); }

    public void recordLlmCall(LlmCall call) {
        jdbc.update("""
                INSERT INTO llm_calls (id,review_run_id,provider,model,task,prompt_hash,status,input_chars,output_chars,
                input_tokens,output_tokens,duration_ms,http_status,error_code,error_detail,created_at)
                VALUES (?::uuid,NULLIF(?,'')::uuid,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, call.id(), call.reviewRunId(), call.provider(), call.model(), call.task(), call.promptHash(), call.status(),
                call.inputChars(), call.outputChars(), call.inputTokens(), call.outputTokens(), call.durationMs(), call.httpStatus(),
                nullToEmpty(call.errorCode()), nullToEmpty(call.errorDetail()), java.sql.Timestamp.from(call.createdAt()));
    }

    /** Charge each physical send BEFORE HTTP. Crashes/unknown outcomes never refund this reservation. */
    public void reserveLlmCall(LlmCall call, String contextPlan, int maxCalls, int maxBytes) {
        if (maxCalls < 1 || maxBytes < 1 || call.inputChars() < 1 || !call.status().equals("reserved")
                || !call.promptHash().matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid model reservation");
        com.fasterxml.jackson.databind.JsonNode plan;
        try { plan = json.readTree(contextPlan); }
        catch (Exception invalid) { throw new IllegalArgumentException("Invalid model context metadata"); }
        if (plan == null || !plan.isObject() || plan.path("schemaVersion").asInt()!=1
                || plan.path("requestBytes").asInt()!=call.inputChars()
                || !plan.path("requestHash").asText().equals(call.promptHash())
                || !plan.path("task").asText().equals(call.task())) throw new IllegalArgumentException("Model plan identity mismatch");
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Model reservation cannot be nested in a business transaction");
        }
        var reservation = new TransactionTemplate(transactions.getTransactionManager());
        reservation.setTimeout(5);
        reservation.executeWithoutResult(status -> {
            jdbc.execute("SET LOCAL lock_timeout='3s'"); jdbc.execute("SET LOCAL statement_timeout='4s'");
            Map<String,Object> run = jdbc.queryForMap("SELECT status,pipeline_version,base_sha,head_sha,config_hash FROM review_runs WHERE id=?::uuid FOR UPDATE", call.reviewRunId());
            if (!"in_progress".equals(run.get("status")) || !Models.PIPELINE_VERSION.equals(run.get("pipeline_version"))) {
                throw new IllegalStateException("Model run is not active under the current protocol");
            }
            if (!plan.path("baseSha").asText().equals(run.get("base_sha")) || !plan.path("headSha").asText().equals(run.get("head_sha"))
                    || !plan.path("policyHash").asText().equals(run.get("config_hash"))) throw new IllegalStateException("Model context snapshot changed");
            Map<String,Object> spent = jdbc.queryForMap("""
                    SELECT count(*) AS calls, COALESCE(sum(p.request_bytes::bigint),0) AS bytes,
                      count(*) FILTER (WHERE c.id IS NULL OR c.status='reserved') AS pending,
                      (SELECT count(*) FROM llm_calls legacy LEFT JOIN llm_request_plans planned ON planned.call_id=legacy.id
                        WHERE legacy.review_run_id=?::uuid AND planned.call_id IS NULL) AS unplanned,
                      count(*) FILTER (WHERE p.max_calls<>? OR p.max_input_bytes<>?) AS changed
                    FROM llm_request_plans p LEFT JOIN llm_calls c ON c.id=p.call_id
                    WHERE p.review_run_id=?::uuid
                    """, call.reviewRunId(),maxCalls,maxBytes,call.reviewRunId());
            if (((Number) spent.get("unplanned")).longValue() != 0 || ((Number) spent.get("changed")).longValue() != 0
                    || ((Number) spent.get("pending")).longValue() != 0
                    || ((Number) spent.get("calls")).longValue() >= maxCalls
                    || ((Number) spent.get("bytes")).longValue() + call.inputChars() > maxBytes) {
                throw new IllegalStateException("Model run budget or snapshot refused");
            }
            recordLlmCall(call);
            jdbc.update("""
                    INSERT INTO llm_request_plans(call_id,review_run_id,request_bytes,max_calls,max_input_bytes,context_plan)
                    VALUES (?::uuid,?::uuid,?,?,?,?::jsonb)
                    """,call.id(),call.reviewRunId(),call.inputChars(),maxCalls,maxBytes,contextPlan);
        });
    }

    public void finishLlmCall(LlmCall call) {
        if (!java.util.Set.of("succeeded","failed").contains(call.status())) throw new IllegalArgumentException("Invalid model outcome");
        int updated = jdbc.update("""
                UPDATE llm_calls SET status=?,output_chars=?,input_tokens=?,output_tokens=?,duration_ms=?,http_status=?,
                  error_code=?,error_detail=? WHERE id=?::uuid AND review_run_id=?::uuid AND status='reserved'
                  AND prompt_hash=? AND input_chars=?
                """,call.status(),call.outputChars(),call.inputTokens(),call.outputTokens(),call.durationMs(),call.httpStatus(),
                nullToEmpty(call.errorCode()),nullToEmpty(call.errorDetail()),call.id(),call.reviewRunId(),call.promptHash(),call.inputChars());
        if (updated != 1) throw new IllegalStateException("Model reservation outcome was not recorded");
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
