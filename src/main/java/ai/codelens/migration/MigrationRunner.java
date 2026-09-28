package ai.codelens.migration;

import ai.codelens.config.RuntimeConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
@Profile("migrate")
public class MigrationRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(MigrationRunner.class);
    private static final long MIGRATION_LOCK_ID = 4_349_336_388_659_987_785L;
    private final JdbcTemplate jdbc;
    private final RuntimeConfig config;
    private final ConfigurableApplicationContext context;

    public MigrationRunner(JdbcTemplate jdbc, RuntimeConfig config, ConfigurableApplicationContext context) {
        this.jdbc = jdbc;
        this.config = config;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        MigrationManifest manifest = MigrationManifest.load(Path.of(config.migrationsDir()));
        if (jdbc.getDataSource() == null) throw new IllegalStateException("migration data source is unavailable");
        try (Connection lockConnection = jdbc.getDataSource().getConnection();
             PreparedStatement lock = lockConnection.prepareStatement("SELECT pg_advisory_lock(?)")) {
            lock.setLong(1, MIGRATION_LOCK_ID);
            lock.execute();
            try {
                migrate(manifest);
            } finally {
                try (PreparedStatement unlock = lockConnection.prepareStatement("SELECT pg_advisory_unlock(?)")) {
                    unlock.setLong(1, MIGRATION_LOCK_ID);
                    unlock.execute();
                }
            }
        }
        log.info("database migrations completed");
        int code = SpringExit.exit(context);
        if (code != 0) throw new IllegalStateException("migration application exited with " + code);
    }

    private void migrate(MigrationManifest manifest) {
        jdbc.execute("CREATE TABLE IF NOT EXISTS schema_migrations (name text PRIMARY KEY, checksum text, applied_at timestamptz NOT NULL DEFAULT now())");
        jdbc.execute("ALTER TABLE schema_migrations ADD COLUMN IF NOT EXISTS checksum text");
        Map<String, String> expected = new LinkedHashMap<>();
        manifest.entries().forEach(entry -> expected.put(entry.name(), entry.checksum()));
        Map<String, String> applied = new LinkedHashMap<>();
        jdbc.query("SELECT name, checksum FROM schema_migrations ORDER BY name", result -> {
            applied.put(result.getString("name"), result.getString("checksum"));
        });
        for (Map.Entry<String, String> entry : applied.entrySet()) {
            String checksum = expected.get(entry.getKey());
            if (checksum == null) throw new IllegalStateException("database contains an unknown migration: " + entry.getKey());
            if (entry.getValue() == null) {
                jdbc.update("UPDATE schema_migrations SET checksum=? WHERE name=? AND checksum IS NULL", checksum, entry.getKey());
                log.info("migration checksum baselined name={}", entry.getKey());
            } else if (!checksum.equals(entry.getValue())) {
                throw new IllegalStateException("applied migration checksum mismatch: " + entry.getKey());
            }
        }
        for (MigrationManifest.Entry entry : manifest.entries()) {
            if (applied.containsKey(entry.name())) continue;
            TransactionTemplate transaction = new TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()));
            transaction.executeWithoutResult(status -> {
                jdbc.execute(entry.sql());
                jdbc.update("INSERT INTO schema_migrations(name, checksum) VALUES(?, ?)", entry.name(), entry.checksum());
            });
            log.info("migration applied name={}", entry.name());
        }
        MigrationSchemaVerifier.Status status = MigrationSchemaVerifier.inspect(jdbc, manifest, true);
        if (!status.ready()) throw new IllegalStateException("database schema verification failed: " + status.reason());
    }

    static final class SpringExit {
        static int exit(ConfigurableApplicationContext context) {
            int code = org.springframework.boot.SpringApplication.exit(context, () -> 0);
            context.close();
            return code;
        }
    }
}
