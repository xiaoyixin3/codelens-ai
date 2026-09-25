package ai.codelens.store;

import ai.codelens.config.DotEnv;
import ai.codelens.config.RuntimeConfig;
import ai.codelens.contracts.Models;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "CODELENS_INTEGRATION_TESTS", matches = "true")
class PostgresIntegrationTest {
    @Test
    void preservesQueueIdempotencyAndProviderControlPlaneContracts() {
        DotEnv.load(".env"); RuntimeConfig config = RuntimeConfig.fromEnvironment();
        HikariConfig pool = new HikariConfig(); pool.setJdbcUrl(config.jdbcUrl());
        pool.setUsername(config.databaseUser()); pool.setPassword(config.databasePassword());
        long installation = UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
        long repository = UUID.randomUUID().getLeastSignificantBits() & Long.MAX_VALUE;
        String providerId = UUID.randomUUID().toString(); String runId = null;
        try (HikariDataSource dataSource = new HikariDataSource(pool)) {
            JdbcTemplate jdbc = new JdbcTemplate(dataSource); ObjectMapper json = new ObjectMapper();
            JdbcStore reviews = new JdbcStore(jdbc, dataSource, json); ProviderStore providers = new ProviderStore(jdbc, dataSource, json);
            Models.ReviewJob pending = new Models.ReviewJob("pending", installation, "integration", "fixture", 7, "base1234", "head1234");
            JdbcStore.CreateReviewInput input = new JdbcStore.CreateReviewInput(repository, 7, "base1234", "head1234",
                    Models.PIPELINE_VERSION, Models.DEFAULT_CONFIG_HASH, "webhook", "integration");
            JdbcStore.CreatedRun created = reviews.createOrGetAndEnqueue(input, pending); runId = created.run().id();
            assertTrue(created.created()); assertFalse(reviews.createOrGetAndEnqueue(input, pending).created());
            Models.ClaimedJob claimed = reviews.claimJob().orElseThrow();
            assertEquals(runId, claimed.payload().reviewRunId()); reviews.completeJob(claimed.id());

            ProviderStore.ProviderConnection provider = providers.create(new ProviderStore.CreateInput(providerId, installation,
                    "integration-provider", "openai_compatible", "https://example.com/v1", "ciphertext", 1,
                    "sha256:fixture", "fixture-model", true, 45, 2, "integration-test"));
            assertEquals(providerId, provider.id()); assertEquals(1, providers.list(installation).size());
            provider = providers.update(new ProviderStore.UpdateInput(installation, providerId, "updated-provider",
                    provider.baseUrl(), provider.defaultModel(), false, 30, 1, "integration-test"));
            assertFalse(provider.enabled());
            provider = providers.rotate(installation, providerId, "rotated", "sha256:rotated", 1, "integration-test");
            assertEquals("sha256:rotated", provider.credentialFingerprint());
            assertEquals("succeeded", providers.recordTest(installation, providerId, "succeeded", "", "integration-test").lastTestStatus());
            providers.delete(installation, providerId, "integration-test"); assertTrue(providers.list(installation).isEmpty());
        } finally {
            try (HikariDataSource cleanup = new HikariDataSource(pool)) {
                JdbcTemplate jdbc = new JdbcTemplate(cleanup);
                if (runId != null) jdbc.update("DELETE FROM review_runs WHERE id=?::uuid", runId);
                jdbc.update("DELETE FROM provider_connections WHERE installation_id=?", installation);
                jdbc.update("DELETE FROM github_repositories WHERE id=?", repository);
                jdbc.update("DELETE FROM github_installations WHERE id=?", installation);
            }
        }
    }
}
