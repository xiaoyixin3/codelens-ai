package ai.codelens.config;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RuntimeConfigTest {
    @Test
    void acceptsACompleteProductionApiConfiguration() {
        RuntimeConfig config = config("production", "api",
                "postgres://codelens:a-very-long-production-password@database:5432/codelens",
                "", "", "a-production-webhook-secret-with-entropy", false, Set.of(), "");

        assertDoesNotThrow(config::validate);
    }

    @Test
    void rejectsDevelopmentSecretsAndWeakDatabaseCredentialsInProduction() {
        RuntimeConfig config = config("production", "api",
                "postgres://codelens:codelens@database:5432/codelens",
                "", "", "replace-with-a-high-entropy-secret", false, Set.of(), "");

        assertThrows(IllegalArgumentException.class, config::validate);
    }

    @Test
    void rejectsPlaceholderProductionSecretsEvenWhenTheyAreLong() {
        RuntimeConfig placeholderDatabase = config("production", "api",
                "postgres://codelens:replace-with-a-strong-database-password@database:5432/codelens",
                "", "", "a-production-webhook-secret-with-entropy", false, Set.of(), "");
        RuntimeConfig placeholderWebhook = config("production", "api",
                "postgres://codelens:a-very-long-production-password@database:5432/codelens",
                "", "", "replace-with-a-high-entropy-secret", false, Set.of(), "");

        assertThrows(IllegalArgumentException.class, placeholderDatabase::validate);
        assertThrows(IllegalArgumentException.class, placeholderWebhook::validate);
    }

    @Test
    void rejectsAProductionWorkerWithoutGitHubAppCredentials() {
        RuntimeConfig config = config("production", "worker",
                "postgres://codelens:a-very-long-production-password@database:5432/codelens",
                "", "", "unused-but-long-enough", false, Set.of(), "");

        assertThrows(IllegalArgumentException.class, config::validate);
    }

    @Test
    void rejectsSemanticActivationWithoutARepositoryAllowlist() {
        RuntimeConfig config = config("development", "worker",
                "postgres://codelens:codelens@localhost:5432/codelens",
                "123", "key", "development-webhook-secret", true, Set.of(), "");

        assertThrows(IllegalArgumentException.class, config::validate);
    }

    @Test
    void rejectsUnknownRuntimeModes() {
        assertThrows(IllegalArgumentException.class, () -> config("development", "surprise",
                "postgres://codelens:codelens@localhost:5432/codelens",
                "", "", "development-webhook-secret", false, Set.of(), "").validate());
    }

    private static RuntimeConfig config(
            String environment,
            String mode,
            String database,
            String appId,
            String privateKey,
            String webhookSecret,
            boolean semanticEnabled,
            Set<String> repositories,
            String dependencyCache
    ) {
        return new RuntimeConfig(environment, mode, database, appId, privateKey,
                webhookSecret, 300, false, 100, 120_000, 8, 500_000,
                "", "", "", "", "", "", 4, 250_000, 2, "infra/migrations", "", "", false,
                semanticEnabled, repositories, ".codelens-workspaces/semantic", 512L * 1024 * 1024,
                2L * 1024 * 1024 * 1024, 200_000, 50_000, 2L * 1024 * 1024,
                dependencyCache, 512, 128L * 1024 * 1024);
    }
}
