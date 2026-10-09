package ai.codelens.semantic;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Component
@Profile("worker")
public final class JdbcSemanticSnapshotStore implements SemanticSnapshotStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;

    public JdbcSemanticSnapshotStore(JdbcTemplate jdbc, DataSource dataSource, ObjectMapper json) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        this.json = json;
    }

    @Override
    public Optional<SemanticModels.Index> load(Key key) {
        long repositoryId = repositoryId(key);
        try {
            SnapshotRow snapshot = jdbc.queryForObject("""
                    SELECT id::text,coverage::text FROM repository_snapshots
                    WHERE github_repository_id=? AND commit_sha=? AND adapter_version=? AND build_model_hash=? AND status='ready'
                    """, (result, ignored) -> new SnapshotRow(result.getString(1), coverage(result.getString(2))),
                    repositoryId, key.commitSha(), key.adapterVersion(), key.buildModelHash());
            if (snapshot == null) return Optional.empty();

            List<SemanticModels.FileStatus> files = jdbc.query("""
                    SELECT path,status,COALESCE(skip_reason,''),COALESCE(content_hash,''),reused
                    FROM semantic_index_files WHERE snapshot_id=?::uuid ORDER BY path
                    """, (result, ignored) -> new SemanticModels.FileStatus(
                    result.getString(1), result.getString(2), result.getString(3), result.getString(4), result.getBoolean(5)), snapshot.id());
            List<SemanticModels.Symbol> symbols = jdbc.query("""
                    SELECT stable_key,kind,qualified_name,signature,path,start_line,end_line,test_source,type_resolved
                    FROM semantic_symbols WHERE snapshot_id=?::uuid ORDER BY stable_key
                    """, (result, ignored) -> new SemanticModels.Symbol(
                    result.getString(1), SemanticModels.SymbolKind.valueOf(result.getString(2)), result.getString(3),
                    result.getString(4), result.getString(5), result.getInt(6), result.getInt(7),
                    result.getBoolean(8), result.getBoolean(9)), snapshot.id());
            List<SemanticModels.Relationship> relationships = jdbc.query("""
                    SELECT from_stable_key,to_stable_key,relationship_type,source_path,source_line,confidence,type_resolved
                    FROM semantic_relationships WHERE snapshot_id=?::uuid
                    ORDER BY from_stable_key,relationship_type,to_stable_key,source_path,source_line
                    """, (result, ignored) -> new SemanticModels.Relationship(
                    result.getString(1), result.getString(2), SemanticModels.RelationType.valueOf(result.getString(3)),
                    result.getString(4), result.getInt(5), result.getDouble(6), result.getBoolean(7)), snapshot.id());
            return Optional.of(new SemanticModels.Index(key.commitSha(), key.adapterVersion(), key.buildModelHash(),
                    files, symbols, relationships, snapshot.coverage()));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    @Override
    public void save(Key key, SemanticModels.Index index) {
        requireMatchingProvenance(key, index);
        long repositoryId = repositoryId(key);
        transactions.executeWithoutResult(ignored -> {
            String proposedId = UUID.randomUUID().toString();
            String snapshotId = jdbc.queryForObject("""
                    INSERT INTO repository_snapshots (
                      id,github_repository_id,commit_sha,adapter_version,build_model_hash,status,
                      analysis_level,execution_level,coverage,degradation_reasons,reused_file_count,completed_at
                    ) VALUES (?::uuid,?,?,?,?, 'ready',?,?,?::jsonb,?::jsonb,?,now())
                    ON CONFLICT (github_repository_id,commit_sha,adapter_version,build_model_hash) DO UPDATE SET
                      status='ready',analysis_level=EXCLUDED.analysis_level,execution_level=EXCLUDED.execution_level,
                      coverage=EXCLUDED.coverage,degradation_reasons=EXCLUDED.degradation_reasons,
                      reused_file_count=EXCLUDED.reused_file_count,error_detail=NULL,completed_at=now(),updated_at=now()
                    RETURNING id::text
                    """, String.class, proposedId, repositoryId, key.commitSha(), key.adapterVersion(), key.buildModelHash(),
                    index.coverage().level().name().toLowerCase(java.util.Locale.ROOT), "S1",
                    toJson(index.coverage()), toJson(index.coverage().degradationReasons()), index.coverage().reusedFiles());
            if (snapshotId == null) throw new IllegalStateException("Snapshot insert returned no id");

            jdbc.update("DELETE FROM semantic_relationships WHERE snapshot_id=?::uuid", snapshotId);
            jdbc.update("DELETE FROM semantic_symbols WHERE snapshot_id=?::uuid", snapshotId);
            jdbc.update("DELETE FROM semantic_index_files WHERE snapshot_id=?::uuid", snapshotId);

            Map<String, Boolean> testSources = new HashMap<>();
            index.symbols().forEach(symbol -> testSources.merge(symbol.path(), symbol.testSource(), Boolean::logicalOr));
            for (SemanticModels.FileStatus file : index.files()) {
                jdbc.update("""
                        INSERT INTO semantic_index_files (
                          snapshot_id,path,language,content_hash,status,skip_reason,test_source,reused
                        ) VALUES (?::uuid,?,'java',NULLIF(?,''),?,NULLIF(?,''),?,?)
                        """, snapshotId, file.path(), file.contentHash(), file.status(), file.reason(),
                        testSources.getOrDefault(file.path(), false), file.reused());
            }
            for (SemanticModels.Symbol symbol : index.symbols()) {
                jdbc.update("""
                        INSERT INTO semantic_symbols (
                          id,snapshot_id,stable_key,kind,qualified_name,signature,path,start_line,end_line,
                          test_source,type_resolved,metadata
                        ) VALUES (?::uuid,?::uuid,?,?,?,?,?,?,?,?,?,?::jsonb)
                        """, UUID.randomUUID().toString(), snapshotId, symbol.stableKey(), symbol.kind().name(),
                        symbol.qualifiedName(), symbol.signature(), symbol.path(), symbol.startLine(), symbol.endLine(),
                        symbol.testSource(), symbol.typeResolved(), toJson(Map.of("adapter", key.adapterVersion())));
            }
            for (SemanticModels.Relationship relationship : index.relationships()) {
                jdbc.update("""
                        INSERT INTO semantic_relationships (
                          id,snapshot_id,from_stable_key,to_stable_key,relationship_type,source_path,source_line,
                          confidence,type_resolved,adapter_version
                        ) VALUES (?::uuid,?::uuid,?,?,?,?,?,?,?,?)
                        """, UUID.randomUUID().toString(), snapshotId, relationship.fromStableKey(),
                        relationship.toStableKey(), relationship.type().name(), relationship.sourcePath(),
                        relationship.sourceLine(), relationship.confidence(), relationship.typeResolved(), key.adapterVersion());
            }
        });
    }

    private static long repositoryId(Key key) {
        try {
            long value = Long.parseLong(key.repositoryKey());
            if (value <= 0) throw new NumberFormatException("not positive");
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("JDBC semantic snapshots require a positive numeric GitHub repository id", exception);
        }
    }

    private static void requireMatchingProvenance(Key key, SemanticModels.Index index) {
        if (!key.commitSha().equals(index.commitSha())
                || !key.adapterVersion().equals(index.adapterVersion())
                || !key.buildModelHash().equals(index.buildModelHash())) {
            throw new IllegalArgumentException("Snapshot key does not match index provenance");
        }
    }

    private SemanticModels.Coverage coverage(String value) {
        try {
            return json.readValue(value, SemanticModels.Coverage.class);
        } catch (Exception exception) {
            throw new IllegalStateException("Stored semantic coverage is invalid", exception);
        }
    }

    private String toJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to serialize semantic snapshot", exception);
        }
    }

    private record SnapshotRow(String id, SemanticModels.Coverage coverage) {}
}
