package ai.codelens.config;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RuntimeConfigTest {
    @Test void freezingRequiresExplicitActivationAndAValidSeparateKey() {
        assertDoesNotThrow(() -> RuntimeConfig.validateFrozenPublications("false", ""));
        assertThrows(IllegalArgumentException.class, () -> RuntimeConfig.validateFrozenPublications("true", ""));
        assertThrows(IllegalArgumentException.class, () -> RuntimeConfig.validateFrozenPublications("yes", ""));
        assertDoesNotThrow(() -> RuntimeConfig.validateFrozenPublications("true", java.util.Base64.getEncoder().encodeToString(new byte[32])));
    }
    @Test
    void acceptsProductionReadOnlyInspectionWithWorkerIdentityRequirements() throws Exception {
        // Generate a throwaway key in memory; never commit a PEM or log its value.
        var generator = java.security.KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        String label = "PRIVATE KEY";
        String privateKey = "-----BEGIN %s-----\n%s\n-----END %s-----".formatted(label,
                java.util.Base64.getEncoder().encodeToString(generator.generateKeyPair().getPrivate().getEncoded()), label);
        var config = config("production", "publication-inspect",
                "postgres://codelens:a-very-long-production-password@database:5432/codelens",
                "123", privateKey,
                "unused-but-long-enough", false, Set.of(), "");
        assertDoesNotThrow(config::validate);
    }

    @Test
    void productionInspectionCannotSkipAuthenticatedAppIdentity() {
        var config = config("production", "publication-inspect",
                "postgres://codelens:a-very-long-production-password@database:5432/codelens",
                "", "", "unused-but-long-enough", false, Set.of(), "");
        assertThrows(IllegalArgumentException.class, config::validate);
    }
    @Test
    void publicTrialRejectsEmptyMalformedOrAmbiguousConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> RuntimeConfig.validatePublicTrial("true", ""));
        assertThrows(IllegalArgumentException.class, () -> RuntimeConfig.validatePublicTrial("true", "*/*"));
        assertThrows(IllegalArgumentException.class, () -> RuntimeConfig.validatePublicTrial("yes", "owner/repo"));
        assertDoesNotThrow(() -> RuntimeConfig.validatePublicTrial("true", "Owner/Repo, lab/java-demo"));
        assertDoesNotThrow(() -> RuntimeConfig.validatePublicTrial("false", ""));
    }

    @Test
    void publicTrialAllowlistRequiresAnExactRepositoryAndFailsClosed() {
        org.junit.jupiter.api.Assertions.assertTrue(RuntimeConfig.trialRepositoryAllowed(true, Set.of("owner/repo"), "OWNER", "Repo"));
        org.junit.jupiter.api.Assertions.assertFalse(RuntimeConfig.trialRepositoryAllowed(true, Set.of("owner/repo"), "owner", "other"));
        org.junit.jupiter.api.Assertions.assertFalse(RuntimeConfig.trialRepositoryAllowed(true, Set.of(), "owner", "repo"));
        org.junit.jupiter.api.Assertions.assertTrue(RuntimeConfig.trialRepositoryAllowed(false, Set.of(), "owner", "repo"));
    }
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
