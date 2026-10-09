package ai.codelens.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitHubRepositoryWorkspaceTest {
    @TempDir Path temporary;
    private static final String BASE = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String HEAD = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    @Test
    void materializesAuthenticatedZipballsAndDeletesOnlyItsRunDirectory() throws Exception {
        Map<String, byte[]> archives = Map.of(
                BASE, zip(Map.of("owner-repo-base/version.txt", "base")),
                HEAD, zip(Map.of("owner-repo-head/version.txt", "head"))
        );
        Path root = Files.createDirectories(temporary.resolve("workspaces"));
        Files.writeString(root.resolve("keep.txt"), "keep");
        GitHubRepositoryWorkspace materializer = workspace(archives, root);
        Path runRoot;
        try (RepositoryWorkspace.MaterializedWorkspace result = materializer.materialize(7, "owner", "repo", BASE, HEAD)) {
            runRoot = result.base().getParent();
            assertEquals("S1", result.executionLevel());
            assertEquals("base", Files.readString(result.base().resolve("version.txt")));
            assertEquals("head", Files.readString(result.head().resolve("version.txt")));
            assertTrue(Files.isDirectory(result.artifacts()));
            assertTrue(Files.isDirectory(result.patches()));
        }
        assertFalse(Files.exists(runRoot));
        assertEquals("keep", Files.readString(root.resolve("keep.txt")));
    }

    @Test
    void rejectsTraversalAndNonImmutableInputs() throws Exception {
        byte[] unsafe = zip(Map.of("owner-repo/../../escape.txt", "escape"));
        GitHubRepositoryWorkspace materializer = workspace(Map.of(BASE, unsafe, HEAD, unsafe), temporary.resolve("workspaces"));
        assertThrows(IllegalStateException.class,
                () -> materializer.materialize(7, "owner", "repo", BASE, HEAD));
        assertThrows(IllegalArgumentException.class,
                () -> materializer.materialize(7, "owner", "repo", "HEAD", HEAD));
        assertFalse(Files.exists(temporary.resolve("escape.txt")));
    }

    @Test
    void enforcesCompressedAndExtractedLimits() throws Exception {
        byte[] archive = zip(Map.of("owner-repo/value.txt", "0123456789"));
        RepositoryArchiveSource source = (installation, owner, repository, sha, destination, maxBytes) -> {
            if (archive.length > maxBytes) throw new IllegalStateException("compressed limit");
            try { Files.write(destination, archive); } catch (Exception exception) { throw new IllegalStateException(exception); }
        };
        GitHubRepositoryWorkspace compressed = new GitHubRepositoryWorkspace(source,
                temporary.resolve("compressed"), 1, 100, 10);
        assertThrows(IllegalStateException.class, () -> compressed.materialize(7, "owner", "repo", BASE, HEAD));
        GitHubRepositoryWorkspace extracted = new GitHubRepositoryWorkspace(source,
                temporary.resolve("extracted"), archive.length + 1L, 5, 10);
        assertThrows(IllegalStateException.class, () -> extracted.materialize(7, "owner", "repo", BASE, HEAD));
    }

    private static GitHubRepositoryWorkspace workspace(Map<String, byte[]> archives, Path root) {
        RepositoryArchiveSource source = (installation, owner, repository, sha, destination, maxBytes) -> {
            byte[] value = archives.get(sha);
            if (value == null || value.length > maxBytes) throw new IllegalStateException("archive unavailable");
            try {
                Files.createDirectories(destination.getParent());
                Files.write(destination, value);
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        };
        return new GitHubRepositoryWorkspace(source, root, 1024 * 1024, 1024 * 1024, 100);
    }

    private static byte[] zip(Map<String, String> entries) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, String> entry : new LinkedHashMap<>(entries).entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
}
