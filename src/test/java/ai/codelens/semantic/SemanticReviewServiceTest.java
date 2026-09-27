package ai.codelens.semantic;

import ai.codelens.contracts.Models;
import ai.codelens.workspace.GitHubRepositoryWorkspace;
import ai.codelens.workspace.RepositoryArchiveSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SemanticReviewServiceTest {
    @TempDir Path temporary;
    private static final String BASE = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String HEAD = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    @Test
    void discoversUnchangedCallersAndTestsThroughTheProductionS1Path() throws Exception {
        Map<String, String> base = repository("return 1;");
        Map<String, String> head = repository("return 2;");
        Map<String, byte[]> archives = Map.of(BASE, zip("owner-repo-base", base), HEAD, zip("owner-repo-head", head));
        Path workspaceRoot = temporary.resolve("workspaces");
        SemanticReviewService service = service(true, source(archives), workspaceRoot);
        Models.PullRequest pull = pull();

        SemanticReviewService.Result result = service.analyze(99, job(), pull);

        assertTrue(result.attempted());
        assertTrue(result.applied());
        assertEquals("semantic", result.coverage().analysisLevel());
        assertEquals("S1", result.coverage().executionLevel());
        assertEquals(0, result.headCoverage().failedFiles());
        assertTrue(result.impact().impactedSymbols() >= 2, () -> result.impact().toString());
        assertTrue(result.impact().topPaths().stream().anyMatch(path -> path.impactedName().contains("Caller")));
        assertTrue(result.impact().topPaths().stream().anyMatch(path -> path.impactedName().contains("TargetTest")));
        try (var children = Files.list(workspaceRoot)) {
            assertEquals(0, children.count(), "ephemeral repository contents must be deleted after indexing");
        }
    }

    @Test
    void staysOffByDefaultAndFailsClosedToFallback() throws Exception {
        AtomicInteger downloads = new AtomicInteger();
        RepositoryArchiveSource unused = (installation, owner, repository, sha, destination, maxBytes) -> downloads.incrementAndGet();
        SemanticReviewService disabled = service(false, unused, temporary.resolve("disabled"));
        SemanticReviewService.Result skipped = disabled.analyze(99, job(), pull());
        assertFalse(skipped.attempted());
        assertFalse(skipped.applied());
        assertEquals(0, downloads.get());

        GitHubRepositoryWorkspace workspace = new GitHubRepositoryWorkspace(unused, temporary.resolve("not-allowlisted"),
                1024, 1024, 10);
        SemanticIndexService indexes = new SemanticIndexService(new JavaSemanticAdapter(),
                new FileSemanticSnapshotStore(temporary.resolve("not-allowlisted-snapshots"),
                        new ObjectMapper().findAndRegisterModules()), new BuildModelDetector());
        SemanticReviewService notAllowlisted = new SemanticReviewService(true, java.util.Set.of(), workspace, indexes,
                (reviewRunId, repositoryId, base, head, impact, coverage) -> { });
        assertFalse(notAllowlisted.analyze(99, job(), pull()).attempted());
        assertEquals(0, downloads.get());

        RepositoryArchiveSource failing = (installation, owner, repository, sha, destination, maxBytes) -> {
            throw new IllegalStateException("untrusted remote detail must not be published");
        };
        SemanticReviewService enabled = service(true, failing, temporary.resolve("failing"));
        SemanticReviewService.Result fallback = enabled.analyze(99, job(), pull());
        assertTrue(fallback.attempted());
        assertFalse(fallback.applied());
        assertEquals("semantic_materialization_failed", fallback.reason());
        assertFalse(fallback.reason().contains("untrusted"));

        Map<String, byte[]> archives = Map.of(
                BASE, zip("owner-repo-base", repository("return 1;")),
                HEAD, zip("owner-repo-head", repository("return 2;")));
        SemanticReviewService auditFailure = service(true, source(archives), temporary.resolve("audit-failure"),
                (reviewRunId, repositoryId, base, head, impact, coverage) -> {
                    throw new IllegalStateException("database unavailable");
                });
        SemanticReviewService.Result auditFallback = auditFailure.analyze(99, job(), pull());
        assertFalse(auditFallback.applied());
        assertEquals("semantic_index_failed", auditFallback.reason());
        assertEquals("S1", auditFallback.executionLevel());
    }

    private SemanticReviewService service(boolean enabled, RepositoryArchiveSource source, Path workspaceRoot) {
        return service(enabled, source, workspaceRoot,
                (reviewRunId, repositoryId, base, head, impact, coverage) -> { });
    }

    private SemanticReviewService service(boolean enabled, RepositoryArchiveSource source, Path workspaceRoot,
                                          SemanticReviewAuditStore audits) {
        GitHubRepositoryWorkspace workspace = new GitHubRepositoryWorkspace(source, workspaceRoot,
                1024 * 1024, 4 * 1024 * 1024, 100);
        SemanticIndexService indexes = new SemanticIndexService(new JavaSemanticAdapter(),
                new FileSemanticSnapshotStore(temporary.resolve("snapshots-" + workspaceRoot.getFileName()),
                        new ObjectMapper().findAndRegisterModules()), new BuildModelDetector());
        return new SemanticReviewService(enabled, java.util.Set.of("owner/repo"), workspace, indexes, audits);
    }

    private static Models.ReviewJob job() {
        return new Models.ReviewJob("run", 7, "owner", "repo", 3, BASE, HEAD);
    }

    private static Models.PullRequest pull() {
        return new Models.PullRequest(3, "Change target", "", BASE, HEAD, List.of(
                new Models.ChangedFile("src/main/java/example/Target.java", "modified", 1, 1,
                        "@@ -3 +3 @@\n-  public int value() { return 1; }\n+  public int value() { return 2; }", "")));
    }

    private static Map<String, String> repository(String targetBody) {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("pom.xml", "<project><modelVersion>4.0.0</modelVersion></project>");
        files.put("src/main/java/example/Target.java", """
                package example;
                public final class Target {
                    public int value() { %s }
                }
                """.formatted(targetBody));
        files.put("src/main/java/example/Caller.java", """
                package example;
                public final class Caller {
                    public int call() { return new Target().value(); }
                }
                """);
        files.put("src/test/java/example/TargetTest.java", """
                package example;
                public final class TargetTest {
                    public int verify() { return new Target().value(); }
                }
                """);
        return files;
    }

    private static RepositoryArchiveSource source(Map<String, byte[]> archives) {
        return (installation, owner, repository, sha, destination, maxBytes) -> {
            byte[] value = archives.get(sha);
            if (value == null || value.length > maxBytes) throw new IllegalStateException("archive unavailable");
            try {
                Files.createDirectories(destination.getParent());
                Files.write(destination, value);
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        };
    }

    private static byte[] zip(String root, Map<String, String> files) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, String> file : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(root + "/" + file.getKey()));
                zip.write(file.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
}
