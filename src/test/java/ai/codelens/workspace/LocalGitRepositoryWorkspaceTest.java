package ai.codelens.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalGitRepositoryWorkspaceTest {
    @TempDir Path temporary;

    @Test
    void materializesExactCommitsAndDeletesOnlyTheRunDirectory() throws Exception {
        Path repository = Files.createDirectories(temporary.resolve("repository"));
        git(repository, "init");
        git(repository, "config", "user.email", "test@example.invalid");
        git(repository, "config", "user.name", "CodeLens Test");
        Files.writeString(repository.resolve("version.txt"), "base");
        git(repository, "add", "version.txt");
        git(repository, "commit", "-m", "base");
        String baseSha = git(repository, "rev-parse", "HEAD").trim();
        Files.writeString(repository.resolve("version.txt"), "head");
        git(repository, "commit", "-am", "head");
        String headSha = git(repository, "rev-parse", "HEAD").trim();

        Path workspaceRoot = Files.createDirectories(temporary.resolve("workspaces"));
        Files.writeString(workspaceRoot.resolve("keep.txt"), "keep");
        LocalGitRepositoryWorkspace materializer = new LocalGitRepositoryWorkspace(workspaceRoot, Duration.ofSeconds(10));
        Path runRoot;
        try (RepositoryWorkspace.MaterializedWorkspace workspace = materializer.materialize(repository, baseSha, headSha)) {
            runRoot = workspace.base().getParent();
            assertEquals("S1", workspace.executionLevel());
            assertEquals("base", Files.readString(workspace.base().resolve("version.txt")));
            assertEquals("head", Files.readString(workspace.head().resolve("version.txt")));
            assertTrue(Files.isDirectory(workspace.artifacts()));
            assertTrue(Files.isDirectory(workspace.patches()));
        }

        assertFalse(Files.exists(runRoot));
        assertEquals("keep", Files.readString(workspaceRoot.resolve("keep.txt")));
    }

    @Test
    void rejectsNonImmutableOrCommandShapedRevisionsBeforeInvokingGit() {
        LocalGitRepositoryWorkspace materializer = new LocalGitRepositoryWorkspace(temporary.resolve("workspaces"));
        assertThrows(IllegalArgumentException.class,
                () -> materializer.materialize(temporary, "HEAD", "0000000000000000000000000000000000000000"));
        assertThrows(IllegalArgumentException.class,
                () -> materializer.materialize(temporary, "0000000000000000000000000000000000000000;whoami",
                        "0000000000000000000000000000000000000000"));
    }

    private static String git(Path repository, String... arguments) throws Exception {
        String[] complete = new String[arguments.length + 3];
        complete[0] = "git";
        complete[1] = "-C";
        complete[2] = repository.toString();
        System.arraycopy(arguments, 0, complete, 3, arguments.length);
        Process process = new ProcessBuilder(complete).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        if (process.waitFor() != 0) throw new IllegalStateException(output);
        return output;
    }
}
