package ai.codelens.semantic;

import ai.codelens.workspace.LocalGitRepositoryWorkspace;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Read-only local entry; shares the production Java adapter and immutable Git workspace. */
public final class LocalReviewCli {
    private LocalReviewCli() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("Usage: repository base-full-sha head-full-sha");
        System.out.println(new ObjectMapper().findAndRegisterModules().writeValueAsString(
                analyze(Path.of(args[0]), args[1], args[2])));
    }

    static Map<String, Object> analyze(Path repository, String baseSha, String headSha) throws Exception {
        Path temporary = Files.createTempDirectory("codelens-local-review-");
        try (var workspace = new LocalGitRepositoryWorkspace(temporary).materialize(repository, baseSha, headSha)) {
            var adapter = new JavaSemanticAdapter(2000, 1_000_000);
            var detector = new BuildModelDetector();
            var base = adapter.index(workspace.base(), baseSha, detector.detect(workspace.base()));
            var head = adapter.index(workspace.head(), headSha, detector.detect(workspace.head()));
            List<Map<String, Object>> files = new ArrayList<>();
            long bytes = 0;
            for (String revision : List.of("base", "head")) {
                Path root = revision.equals("base") ? workspace.base() : workspace.head();
                var index = revision.equals("base") ? base : head;
                var paths = new java.util.TreeSet<String>();
                index.files().forEach(file -> paths.add(file.path()));
                try (var walk = Files.walk(root)) {
                    walk.filter(Files::isRegularFile).filter(file -> {
                        String name = file.getFileName().toString();
                        return List.of("pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts").contains(name);
                    }).forEach(file -> paths.add(root.relativize(file).toString().replace('\\', '/')));
                }
                for (String relative : paths) {
                    Path file = root.resolve(relative).normalize();
                    if (!file.startsWith(root) || !Files.isRegularFile(file) || Files.size(file) > 1_000_000) continue;
                    bytes += Files.size(file);
                    if (files.size() >= 2000 || bytes > 25L * 1024 * 1024) {
                        throw new IllegalArgumentException("Local context exceeds 2000 files / 25 MiB; choose a smaller repository.");
                    }
                    boolean java = relative.endsWith(".java");
                    String role = java ? (relative.matches("(?i).*(/test/|/tests/|Test\\.java$).*") ? "test" : "source") : "build";
                    files.add(Map.of("path", relative, "revision", revision, "role", role,
                            "language", java ? "Java" : "Build", "content", Files.readString(file, StandardCharsets.UTF_8)));
                }
            }
            return Map.of("base", base, "head", head, "files", files);
        } finally {
            // The workspace closes and removes only its random child. Never delete the user's checkout.
            Files.deleteIfExists(temporary);
        }
    }
}
