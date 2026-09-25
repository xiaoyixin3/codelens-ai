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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

@Component
@Profile("migrate")
public class MigrationRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(MigrationRunner.class);
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
        jdbc.execute("CREATE TABLE IF NOT EXISTS schema_migrations (name text PRIMARY KEY, applied_at timestamptz NOT NULL DEFAULT now())");
        List<Path> migrations;
        try (var files = Files.list(Path.of(config.migrationsDir()))) {
            migrations = files.filter(path -> path.getFileName().toString().endsWith(".sql"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString())).toList();
        }
        for (Path path : migrations) {
            String name = path.getFileName().toString();
            Integer applied = jdbc.queryForObject("SELECT count(*) FROM schema_migrations WHERE name=?", Integer.class, name);
            if (applied != null && applied > 0) continue;
            String sql = Files.readString(path);
            TransactionTemplate transaction = new TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()));
            transaction.executeWithoutResult(status -> {
                jdbc.execute(sql);
                jdbc.update("INSERT INTO schema_migrations(name) VALUES(?)", name);
            });
            log.info("migration applied name={}", name);
        }
        log.info("database migrations completed");
        int code = SpringExit.exit(context);
        if (code != 0) throw new IllegalStateException("migration application exited with " + code);
    }

    static final class SpringExit {
        static int exit(ConfigurableApplicationContext context) {
            int code = org.springframework.boot.SpringApplication.exit(context, () -> 0);
            context.close();
            return code;
        }
    }
}
