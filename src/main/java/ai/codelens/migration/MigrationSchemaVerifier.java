package ai.codelens.migration;

import ai.codelens.config.RuntimeConfig;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
@Profile({"api", "worker", "publication-inspect"})
public class MigrationSchemaVerifier {
    private final JdbcTemplate jdbc;
    private final MigrationManifest manifest;

    public MigrationSchemaVerifier(JdbcTemplate jdbc, RuntimeConfig config) {
        this.jdbc = jdbc;
        this.manifest = MigrationManifest.load(Path.of(config.migrationsDir()));
    }

    public Status status() {
        return inspect(jdbc, manifest, true);
    }

    public void requireReady() {
        Status status = status();
        if (!status.ready()) throw new IllegalStateException("database schema is not ready: " + status.reason());
    }

    static Status inspect(JdbcTemplate jdbc, MigrationManifest manifest, boolean requireAll) {
        try {
            Map<String, String> expected = new LinkedHashMap<>();
            manifest.entries().forEach(entry -> expected.put(entry.name(), entry.checksum()));
            Map<String, String> applied = new LinkedHashMap<>();
            jdbc.query("SELECT name, checksum FROM schema_migrations ORDER BY name", result -> {
                applied.put(result.getString("name"), result.getString("checksum"));
            });
            for (Map.Entry<String, String> entry : applied.entrySet()) {
                String checksum = expected.get(entry.getKey());
                if (checksum == null) return new Status(false, "unknown_migration");
                if (entry.getValue() == null || !checksum.equals(entry.getValue())) {
                    return new Status(false, "migration_checksum_mismatch");
                }
            }
            if (requireAll && applied.size() != expected.size()) return new Status(false, "migration_missing");
            return new Status(true, "ready");
        } catch (RuntimeException exception) {
            return new Status(false, "schema_unavailable");
        }
    }

    public record Status(boolean ready, String reason) {}
}
