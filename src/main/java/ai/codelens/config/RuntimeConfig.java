package ai.codelens.config;

import java.net.URI;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

public record RuntimeConfig(
        String mode,
        String databaseUrl,
        String githubAppId,
        String githubPrivateKey,
        String webhookSecret,
        int webhookRateLimit,
        int maxChangedFiles,
        int maxPatchChars,
        int maxInlineComments,
        int maxIndexFileBytes,
        String llmBaseUrl,
        String llmApiKey,
        String llmModel,
        String llmFallbackBaseUrl,
        String llmFallbackApiKey,
        String llmFallbackModel,
        int llmMaxCalls,
        int llmMaxInputChars,
        int workerConcurrency,
        String migrationsDir,
        String modelAdminToken,
        String credentialKey,
        boolean allowPrivateModels,
        boolean semanticEnabled,
        Set<String> semanticRepositories,
        String semanticWorkspaceRoot,
        long semanticMaxArchiveBytes,
        long semanticMaxExtractedBytes,
        int semanticMaxEntries,
        int semanticMaxFiles,
        long semanticMaxFileBytes
) {
    public RuntimeConfig {
        semanticRepositories = semanticRepositories == null ? Set.of() : Set.copyOf(semanticRepositories);
    }

    public static RuntimeConfig fromEnvironment() {
        RuntimeConfig config = new RuntimeConfig(
                System.getProperty("codelens.mode", env("CODELENS_MODE", "api")),
                env("DATABASE_URL", "postgres://codelens:codelens@localhost:5432/codelens"),
                env("GITHUB_APP_ID", ""),
                env("GITHUB_PRIVATE_KEY", "").replace("\\n", "\n"),
                env("GITHUB_WEBHOOK_SECRET", "development-webhook-secret"),
                integer("WEBHOOK_RATE_LIMIT_MAX", 300),
                integer("MAX_CHANGED_FILES", 100),
                integer("MAX_PATCH_CHARS", 120_000),
                integer("MAX_INLINE_COMMENTS", 8),
                integer("MAX_INDEX_FILE_BYTES", 500_000),
                trimSlash(env("LLM_BASE_URL", "")), env("LLM_API_KEY", ""), env("LLM_MODEL", ""),
                trimSlash(env("LLM_FALLBACK_BASE_URL", "")), env("LLM_FALLBACK_API_KEY", ""), env("LLM_FALLBACK_MODEL", ""),
                integer("LLM_MAX_CALLS_PER_RUN", 4), integer("LLM_MAX_INPUT_CHARS_PER_RUN", 250_000),
                integer("WORKER_CONCURRENCY", 2), env("MIGRATIONS_DIR", "infra/migrations"),
                env("CODELENS_MODEL_ADMIN_TOKEN", ""), env("CODELENS_CREDENTIAL_KEY", ""),
                Boolean.parseBoolean(env("CODELENS_ALLOW_PRIVATE_MODEL_ENDPOINTS", "false")),
                Boolean.parseBoolean(env("CODELENS_SEMANTIC_ENABLED", "false")),
                csvSet(env("CODELENS_SEMANTIC_REPOSITORIES", "")),
                env("CODELENS_SEMANTIC_WORKSPACE_ROOT", ".codelens-workspaces/semantic"),
                longValue("CODELENS_SEMANTIC_MAX_ARCHIVE_BYTES", 512L * 1024 * 1024),
                longValue("CODELENS_SEMANTIC_MAX_EXTRACTED_BYTES", 2L * 1024 * 1024 * 1024),
                integer("CODELENS_SEMANTIC_MAX_ENTRIES", 200_000),
                integer("CODELENS_SEMANTIC_MAX_FILES", 50_000),
                longValue("CODELENS_SEMANTIC_MAX_FILE_BYTES", 2L * 1024 * 1024)
        );
        config.validate();
        return config;
    }

    public String jdbcUrl() {
        if (databaseUrl.startsWith("jdbc:")) return databaseUrl;
        URI uri = URI.create(databaseUrl);
        String query = uri.getQuery() == null ? "" : "?" + uri.getQuery();
        return "jdbc:postgresql://" + uri.getHost() + ":" + (uri.getPort() < 0 ? 5432 : uri.getPort()) + uri.getPath() + query;
    }

    public String databaseUser() {
        if (databaseUrl.startsWith("jdbc:")) return env("CODELENS_DB_USER", "codelens");
        String userInfo = URI.create(databaseUrl).getUserInfo();
        return userInfo == null ? "codelens" : userInfo.split(":", 2)[0];
    }

    public String databasePassword() {
        if (databaseUrl.startsWith("jdbc:")) return env("CODELENS_DB_PASSWORD", "codelens");
        String userInfo = URI.create(databaseUrl).getUserInfo();
        return userInfo != null && userInfo.contains(":") ? userInfo.split(":", 2)[1] : "";
    }

    private void validate() {
        if (webhookSecret.length() < 16) throw new IllegalArgumentException("GITHUB_WEBHOOK_SECRET must contain at least 16 characters");
        if (maxChangedFiles < 1 || maxPatchChars < 1 || maxInlineComments < 0 || maxIndexFileBytes < 1 || workerConcurrency < 1
                || semanticWorkspaceRoot.isBlank() || semanticMaxArchiveBytes < 1 || semanticMaxExtractedBytes < 1
                || semanticMaxEntries < 1 || semanticMaxFiles < 1 || semanticMaxFileBytes < 1) {
            throw new IllegalArgumentException("numeric limits are invalid");
        }
        requireTogether("LLM", llmBaseUrl, llmApiKey, llmModel);
        requireTogether("LLM_FALLBACK", llmFallbackBaseUrl, llmFallbackApiKey, llmFallbackModel);
        if (modelAdminToken.isBlank() != credentialKey.isBlank()) {
            throw new IllegalArgumentException("CODELENS_MODEL_ADMIN_TOKEN and CODELENS_CREDENTIAL_KEY must be configured together");
        }
        if (!modelAdminToken.isBlank() && modelAdminToken.length() < 32) {
            throw new IllegalArgumentException("CODELENS_MODEL_ADMIN_TOKEN must contain at least 32 characters");
        }
        if (semanticRepositories.stream().anyMatch(value -> !value.matches("[^/\\s]+/[^/\\s]+"))) {
            throw new IllegalArgumentException("CODELENS_SEMANTIC_REPOSITORIES must contain owner/repository entries");
        }
    }

    private static void requireTogether(String prefix, String... values) {
        int configured = 0;
        for (String value : values) if (!value.isBlank()) configured++;
        if (configured != 0 && configured != values.length) throw new IllegalArgumentException(prefix + " settings must be configured together");
    }

    private static String env(String name, String fallback) { return DotEnv.get(name, fallback); }
    private static int integer(String name, int fallback) {
        try { return Integer.parseInt(env(name, Integer.toString(fallback))); }
        catch (NumberFormatException ignored) { return fallback; }
    }
    private static long longValue(String name, long fallback) {
        try { return Long.parseLong(env(name, Long.toString(fallback))); }
        catch (NumberFormatException ignored) { return fallback; }
    }
    private static Set<String> csvSet(String value) {
        if (value == null || value.isBlank()) return Set.of();
        return Arrays.stream(value.split(","))
                .map(String::trim).filter(item -> !item.isBlank())
                .map(item -> item.toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
    private static String trimSlash(String value) { return value.replaceFirst("/+$", ""); }
}
