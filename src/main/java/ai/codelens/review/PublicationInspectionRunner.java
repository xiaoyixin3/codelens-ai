package ai.codelens.review;

import ai.codelens.github.GitHubClient;
import ai.codelens.config.RuntimeConfig;
import ai.codelens.migration.MigrationSchemaVerifier;
import ai.codelens.store.JdbcStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("publication-inspect")
public final class PublicationInspectionRunner implements ApplicationRunner {
    private final PublicationInspector inspector;
    private final ObjectMapper json;
    private final MigrationSchemaVerifier schema;
    private final ConfigurableApplicationContext context;

    public PublicationInspectionRunner(JdbcStore store, GitHubClient github, ObjectMapper json,
                                       MigrationSchemaVerifier schema, ConfigurableApplicationContext context, RuntimeConfig config) {
        this.inspector = new PublicationInspector(store, github, json, config); this.json = json;
        this.schema = schema; this.context = context;
    }

    public static void requireReadOnlyProfiles(String[] profiles) {
        if (profiles.length != 1 || !profiles[0].equals("publication-inspect")) {
            throw new IllegalArgumentException("Publication inspection cannot run alongside another profile");
        }
    }

    public static String runId(ApplicationArguments args) {
        var values = args.getOptionValues("run-id");
        if (!args.getNonOptionArgs().isEmpty() || args.getOptionNames().size() != 1
                || values == null || values.size() != 1
                || !values.get(0).matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")) {
            throw new IllegalArgumentException("Use only --run-id=<review-run UUID>; this command has no write options");
        }
        return values.get(0).toLowerCase(java.util.Locale.ROOT);
    }

    @Override public void run(ApplicationArguments args) throws Exception {
        try {
            String run = runId(args);
            schema.requireReady();
            System.out.println(json.writeValueAsString(inspector.inspect(run)));
        } finally { context.close(); }
    }
}
