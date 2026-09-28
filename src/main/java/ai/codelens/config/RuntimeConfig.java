package ai.codelens.config;

import java.net.URI;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

public record RuntimeConfig(
        String environment,
        String mode,
        String databaseUrl,
        String githubAppId,
        String githubPrivateKey,
        String webhookSecret,
        int webhookRateLimit,
        boolean trustProxy,
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
        long semanticMaxFileBytes,
        String semanticDependencyCache,
        int semanticMaxDependencyJars,
        long semanticMaxDependencyJarBytes
) {
    public RuntimeConfig {
        semanticRepositories = semanticRepositories == null ? Set.of() : Set.copyOf(semanticRepositories);
    }

    public static RuntimeConfig fromEnvironment() {
        RuntimeConfig config = new RuntimeConfig(
                env("CODELENS_ENVIRONMENT", "development").trim().toLowerCase(Locale.ROOT),
                System.getProperty("codelens.mode", env("CODELENS_MODE", "api")),
                env("DATABASE_URL", "postgres://codelens:codelens@localhost:5432/codelens"),
                env("GITHUB_APP_ID", ""),
                env("GITHUB_PRIVATE_KEY", "").replace("\\n", "\n"),
                env("GITHUB_WEBHOOK_SECRET", "development-webhook-secret"),
                integer("WEBHOOK_RATE_LIMIT_MAX", 300),
                Boolean.parseBoolean(env("CODELENS_TRUST_PROXY", "false")),
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
                longValue("CODELENS_SEMANTIC_MAX_FILE_BYTES", 2L * 1024 * 1024),
                env("CODELENS_SEMANTIC_DEPENDENCY_CACHE", ""),
                integer("CODELENS_SEMANTIC_MAX_DEPENDENCY_JARS", 512),
                longValue("CODELENS_SEMANTIC_MAX_DEPENDENCY_JAR_BYTES", 128L * 1024 * 1024)
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

    public boolean production() { return environment.equals("production"); }

    public void validate() {
        if (!Set.of("development", "test", "production").contains(environment)) {
            throw new IllegalArgumentException("CODELENS_ENVIRONMENT must be development, test, or production");
        }
        if (!Set.of("api", "worker", "migrate").contains(mode.trim().toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("CODELENS_MODE must be api, worker, or migrate");
        }
        if (webhookSecret.length() < 16) throw new IllegalArgumentException("GITHUB_WEBHOOK_SECRET must contain at least 16 characters");
        if (maxChangedFiles < 1 || maxPatchChars < 1 || maxInlineComments < 0 || maxIndexFileBytes < 1 || workerConcurrency < 1
                || semanticWorkspaceRoot.isBlank() || semanticMaxArchiveBytes < 1 || semanticMaxExtractedBytes < 1
                || semanticMaxEntries < 1 || semanticMaxFiles < 1 || semanticMaxFileBytes < 1
                || semanticMaxDependencyJars < 1 || semanticMaxDependencyJarBytes < 1) {
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
        if (semanticEnabled && semanticRepositories.isEmpty()) {
            throw new IllegalArgumentException("CODELENS_SEMANTIC_REPOSITORIES is required when semantic analysis is enabled");
        }
        if (production()) validateProduction();
    }

    private void validateProduction() {
        if (databasePassword().length() < 16 || databasePassword().equals("codelens")
                || databasePassword().contains("replace-with")) {
            throw new IllegalArgumentException("production database credentials must use a non-default password of at least 16 characters");
        }
        String normalizedMode = mode.trim().toLowerCase(Locale.ROOT);
        if (normalizedMode.equals("api") && (webhookSecret.length() < 32
                || webhookSecret.equals("development-webhook-secret") || webhookSecret.contains("replace-with"))) {
            throw new IllegalArgumentException("production API requires a high-entropy GITHUB_WEBHOOK_SECRET of at least 32 characters");
        }
        if (normalizedMode.equals("worker")) {
            if (!githubAppId.matches("[1-9][0-9]*")) {
                throw new IllegalArgumentException("production worker requires a numeric GITHUB_APP_ID");
            }
            if (!githubPrivateKey.startsWith("-----BEGIN ") || !githubPrivateKey.contains("PRIVATE KEY-----")
                    || !githubPrivateKey.contains("-----END ")) {
                throw new IllegalArgumentException("production worker requires a PEM GITHUB_PRIVATE_KEY");
            }
        }
        if (!semanticDependencyCache.isBlank() && !java.nio.file.Path.of(semanticDependencyCache).isAbsolute()) {
            throw new IllegalArgumentException("production semantic dependency cache path must be absolute");
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
