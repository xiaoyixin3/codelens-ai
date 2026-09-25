package ai.codelens.store;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class ProviderStore {
    public record ProviderConnection(
            String id, long installationId, String name, String providerKind, String baseUrl,
            @JsonIgnore String credentialCiphertext, @JsonIgnore int credentialKeyVersion,
            String credentialFingerprint, String defaultModel, boolean enabled, int timeoutSeconds,
            int maxRetries, String lastTestStatus, String lastTestDetail, Instant lastTestedAt,
            Instant createdAt, Instant updatedAt) {}
    public record CreateInput(String id, long installationId, String name, String providerKind, String baseUrl,
                              String ciphertext, int keyVersion, String fingerprint, String model, boolean enabled,
                              int timeoutSeconds, int maxRetries, String actor) {}
    public record UpdateInput(long installationId, String id, String name, String baseUrl, String model,
                              boolean enabled, int timeoutSeconds, int maxRetries, String actor) {}

    private static final String COLUMNS = """
            id::text,installation_id,name,provider_kind,base_url,credential_ciphertext,
            credential_key_version,credential_fingerprint,default_model,enabled,timeout_seconds,max_retries,
            last_test_status,COALESCE(last_test_detail,''),last_tested_at,created_at,updated_at
            """;
    private static final RowMapper<ProviderConnection> MAPPER = (rs, ignored) -> new ProviderConnection(
            rs.getString(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
            rs.getInt(7), rs.getString(8), rs.getString(9), rs.getBoolean(10), rs.getInt(11), rs.getInt(12),
            rs.getString(13), rs.getString(14), rs.getTimestamp(15) == null ? null : rs.getTimestamp(15).toInstant(),
            rs.getTimestamp(16).toInstant(), rs.getTimestamp(17).toInstant());
    private final JdbcTemplate jdbc; private final TransactionTemplate transactions; private final ObjectMapper json;

    public ProviderStore(JdbcTemplate jdbc, DataSource dataSource, ObjectMapper json) {
        this.jdbc = jdbc; this.transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource)); this.json = json;
    }

    public List<ProviderConnection> list(long installationId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM provider_connections WHERE installation_id=? ORDER BY name,id", MAPPER, installationId);
    }
    public ProviderConnection get(long installationId, String id) {
        return jdbc.queryForObject("SELECT " + COLUMNS + " FROM provider_connections WHERE installation_id=? AND id=?::uuid", MAPPER, installationId, id);
    }
    public ProviderConnection create(CreateInput input) {
        return transactions.execute(status -> {
            jdbc.update("""
                    INSERT INTO github_installations (id,account_login,active) VALUES (?,?,true)
                    ON CONFLICT (id) DO UPDATE SET active=true,updated_at=now()
                    """, input.installationId(), "installation:" + input.installationId());
            ProviderConnection result = jdbc.queryForObject("""
                    INSERT INTO provider_connections (id,installation_id,name,provider_kind,base_url,credential_ciphertext,
                    credential_key_version,credential_fingerprint,default_model,enabled,timeout_seconds,max_retries)
                    VALUES (?::uuid,?,?,?,?,?,?,?,?,?,?,?) RETURNING
                    """ + COLUMNS, MAPPER, input.id(), input.installationId(), input.name(), input.providerKind(), input.baseUrl(),
                    input.ciphertext(), input.keyVersion(), input.fingerprint(), input.model(), input.enabled(), input.timeoutSeconds(), input.maxRetries());
            audit(input.installationId(), input.id(), "created", input.actor(), "{\"providerKind\":\"" + safe(input.providerKind()) + "\"}");
            return result;
        });
    }
    public ProviderConnection update(UpdateInput input) {
        return transactions.execute(status -> {
            ProviderConnection result = jdbc.queryForObject("""
                    UPDATE provider_connections SET name=?,base_url=?,default_model=?,enabled=?,timeout_seconds=?,max_retries=?,
                    last_test_status='untested',last_test_detail=NULL,last_tested_at=NULL,updated_at=now()
                    WHERE installation_id=? AND id=?::uuid RETURNING
                    """ + COLUMNS, MAPPER, input.name(), input.baseUrl(), input.model(), input.enabled(), input.timeoutSeconds(),
                    input.maxRetries(), input.installationId(), input.id());
            audit(input.installationId(), input.id(), "updated", input.actor(), "{\"enabled\":" + input.enabled() + "}");
            return result;
        });
    }
    public ProviderConnection rotate(long installationId, String id, String ciphertext, String fingerprint, int keyVersion, String actor) {
        return transactions.execute(status -> {
            ProviderConnection result = jdbc.queryForObject("""
                    UPDATE provider_connections SET credential_ciphertext=?,credential_key_version=?,credential_fingerprint=?,
                    last_test_status='untested',last_test_detail=NULL,last_tested_at=NULL,updated_at=now()
                    WHERE installation_id=? AND id=?::uuid RETURNING
                    """ + COLUMNS, MAPPER, ciphertext, keyVersion, fingerprint, installationId, id);
            audit(installationId, id, "rotated", actor, "{\"credentialFingerprint\":\"" + safe(fingerprint) + "\"}");
            return result;
        });
    }
    public ProviderConnection recordTest(long installationId, String id, String testStatus, String detail, String actor) {
        return transactions.execute(status -> {
            ProviderConnection result = jdbc.queryForObject("""
                    UPDATE provider_connections SET last_test_status=?,last_test_detail=NULLIF(?,''),last_tested_at=now(),updated_at=now()
                    WHERE installation_id=? AND id=?::uuid RETURNING
                    """ + COLUMNS, MAPPER, testStatus, detail, installationId, id);
            audit(installationId, id, "tested", actor, "{\"status\":\"" + safe(testStatus) + "\"}");
            return result;
        });
    }
    public void delete(long installationId, String id, String actor) {
        transactions.executeWithoutResult(status -> {
            int deleted = jdbc.update("DELETE FROM provider_connections WHERE installation_id=? AND id=?::uuid", installationId, id);
            if (deleted == 0) throw new org.springframework.dao.EmptyResultDataAccessException(1);
            audit(installationId, id, "deleted", actor, "{}");
        });
    }
    private void audit(long installationId, String connectionId, String action, String actor, String detail) {
        jdbc.update("""
                INSERT INTO provider_connection_audit (id,installation_id,connection_id,action,actor,detail)
                VALUES (?::uuid,?,?::uuid,?,?,?::jsonb)
                """, UUID.randomUUID().toString(), installationId, connectionId, action, actor, detail);
    }
    private static String safe(String value) { return value.replace("\\", "\\\\").replace("\"", "\\\""); }
}
