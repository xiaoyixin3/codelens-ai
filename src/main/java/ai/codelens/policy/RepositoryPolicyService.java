package ai.codelens.policy;

import ai.codelens.config.RuntimeConfig;
import ai.codelens.contracts.Models;
import ai.codelens.github.GitHubClient;
import ai.codelens.security.Redactor;
import ai.codelens.store.JdbcStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Component
@Profile("worker")
public class RepositoryPolicyService {
    private final GitHubClient github;
    private final JdbcStore store;
    private final RuntimeConfig config;
    private final ObjectMapper json;

    public RepositoryPolicyService(GitHubClient github, JdbcStore store, RuntimeConfig config, ObjectMapper json) {
        this.github = github;
        this.store = store;
        this.config = config;
        this.json = json;
    }

    public Models.RepositoryPolicy load(long repositoryId, long installationId, String owner, String repo, String headSha) {
        Parsed parsed = Parsed.defaults(config.maxInlineComments());
        List<String> warnings = new ArrayList<>();
        try {
            parsed = parse(github.getFileContent(installationId, owner, repo, ".codelens.yml", headSha));
        } catch (RuntimeException exception) {
            if (!isNotFound(exception)) warnings.add(".codelens.yml could not be loaded; defaults were used.");
        }
        String guidance = "";
        try {
            String source = github.getFileContent(installationId, owner, repo, "CODELENS.md", headSha);
            guidance = Redactor.redact(source.substring(0, Math.min(source.length(), 20_000)));
        } catch (RuntimeException exception) {
            if (!isNotFound(exception)) warnings.add("CODELENS.md could not be loaded.");
        }
        List<Models.PolicyRule> rules = new ArrayList<>(parsed.rules);
        for (String raw : guidance.split("\\R")) {
            String line = raw.trim();
            if (!(line.startsWith("- ") || line.startsWith("* "))) continue;
            String content = line.substring(2).trim();
            if (content.isBlank()) continue;
            rules.add(new Models.PolicyRule("codelens-md-" + digest(content).substring(0, 12), "", "", Redactor.redact(content), true));
            if (rules.size() >= 100) break;
        }
        String hash = digest(toJson(Map.of("file", parsed.toMap(), "guidance", guidance, "rules", rules)));
        Models.RepositoryPolicy policy = new Models.RepositoryPolicy(hash, headSha, parsed.language, parsed.blocking,
                parsed.maxInlineComments, Map.copyOf(parsed.minimumConfidence), List.copyOf(parsed.include),
                List.copyOf(parsed.exclude), List.copyOf(rules), guidance, List.copyOf(warnings));
        store.savePolicy(repositoryId, policy);
        return policy;
    }

    @SuppressWarnings("unchecked")
    Parsed parse(String source) {
        if (source.length() > 100_000) throw new IllegalArgumentException(".codelens.yml exceeds 100 KB");
        LoaderOptions options = new LoaderOptions();
        options.setCodePointLimit(100_000);
        Object loaded = new Yaml(options).load(source);
        Map<String, Object> root = loaded instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
        int version = integer(root.get("version"), 1);
        if (version != 1) throw new IllegalArgumentException("unsupported policy version " + version);
        Map<String, Object> review = root.get("review") instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
        String language = string(review.get("language"), "en");
        if (!language.equals("en") && !language.equals("zh")) throw new IllegalArgumentException("review.language must be en or zh");
        int maxComments = integer(review.get("maxInlineComments"), config.maxInlineComments());
        if (maxComments < 0 || maxComments > 50) throw new IllegalArgumentException("review.maxInlineComments must be between 0 and 50");
        Map<String, Double> confidence = new LinkedHashMap<>();
        if (review.get("minimumConfidence") instanceof Map<?, ?> values) {
            values.forEach((key, value) -> {
                String severity = key.toString();
                if (!List.of("critical", "high", "medium", "low").contains(severity)) throw new IllegalArgumentException("unsupported severity " + severity);
                double number = Double.parseDouble(value.toString());
                if (number < 0 || number > 1) throw new IllegalArgumentException("confidence must be between 0 and 1");
                confidence.put(severity, number);
            });
        }
        List<String> include = strings(root.get("include"));
        if (include.isEmpty()) include = List.of("**/*");
        List<String> exclude = strings(root.get("exclude"));
        List<Models.PolicyRule> rules = new ArrayList<>();
        if (root.get("rules") instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> value)) continue;
                String id = string(value.get("id"), string(value.get("key"), ""));
                String description = string(value.get("description"), string(value.get("content"), ""));
                if (id.isBlank() || description.isBlank()) throw new IllegalArgumentException("policy rules require id/key and description/content");
                rules.add(new Models.PolicyRule(id, string(value.get("scope"), ""), string(value.get("severity"), ""),
                        Redactor.redact(description), !Boolean.FALSE.equals(value.get("enabled"))));
            }
        }
        return new Parsed(language, Boolean.TRUE.equals(review.get("blocking")), maxComments, confidence, include, exclude, rules);
    }

    public List<Models.ChangedFile> filterFiles(List<Models.ChangedFile> files, Models.RepositoryPolicy policy) {
        return files.stream().filter(file -> included(file.path(), policy)).toList();
    }

    public boolean included(String path, Models.RepositoryPolicy policy) {
        if (path == null || path.isBlank() || path.startsWith("/") || path.contains("../") || path.contains("\\")
                || path.indexOf('\0') >= 0 || path.matches("^[A-Za-z]:.*")) return false;
        boolean included = policy.include().stream().anyMatch(pattern -> glob(pattern, path));
        return included && policy.exclude().stream().noneMatch(pattern -> glob(pattern, path));
    }

    static boolean glob(String glob, String value) {
        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*') {
                if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                    i++;
                    if (i + 1 < glob.length() && glob.charAt(i + 1) == '/') { i++; regex.append("(?:.*/)?"); }
                    else regex.append(".*");
                } else regex.append("[^/]*");
            } else if (c == '?') regex.append("[^/]");
            else regex.append(Pattern.quote(String.valueOf(c)));
        }
        return value.matches(regex.append('$').toString());
    }

    private String toJson(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }
    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }
    private static boolean isNotFound(RuntimeException exception) { return exception.getMessage() != null && exception.getMessage().contains("status 404"); }
    private static String string(Object value, String fallback) { return value == null || value.toString().isBlank() ? fallback : value.toString(); }
    private static int integer(Object value, int fallback) { return value == null ? fallback : Integer.parseInt(value.toString()); }
    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().map(Object::toString).toList();
    }

    record Parsed(String language, boolean blocking, int maxInlineComments, Map<String, Double> minimumConfidence,
                  List<String> include, List<String> exclude, List<Models.PolicyRule> rules) {
        static Parsed defaults(int max) { return new Parsed("en", false, max, Map.of(), List.of("**/*"), List.of(), List.of()); }
        Map<String, Object> toMap() { return Map.of("language", language, "blocking", blocking, "maxInlineComments", maxInlineComments,
                "minimumConfidence", minimumConfidence, "include", include, "exclude", exclude, "rules", rules); }
    }
}
