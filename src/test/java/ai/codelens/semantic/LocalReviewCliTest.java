package ai.codelens.semantic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class LocalReviewCliTest {
    @TempDir Path root;

    @Test void freezesBothCommitsAndLeavesDirtyCheckoutUntouched() throws Exception {
        git("init"); git("config", "user.name", "Local Test"); git("config", "user.email", "test@example.invalid");
        Path source = Files.createDirectories(root.resolve("src/main/java"));
        Files.writeString(source.resolve("Service.java"), "class Service { int value() { return 1; } }");
        Files.writeString(source.resolve("Caller.java"), "class Caller { int run() { return new Service().value(); } }");
        Files.writeString(root.resolve(".env"), "DO_NOT_INCLUDE_LOCAL_CONFIGURATION=private");
        git("add", "."); git("commit", "-m", "base"); String base = git("rev-parse", "HEAD").trim();
        Files.writeString(source.resolve("Service.java"), "class Service { int value() { return 2; } }");
        git("commit", "-am", "head"); String head = git("rev-parse", "HEAD").trim();
        Files.writeString(source.resolve("Service.java"), "dirty work belongs to the user");
        Map<String, Object> result = LocalReviewCli.analyze(root, base, head);
        var baseIndex = (SemanticModels.Index) result.get("base");
        var headIndex = (SemanticModels.Index) result.get("head");
        assertEquals(base, baseIndex.commitSha()); assertEquals(head, headIndex.commitSha());
        assertEquals(2, headIndex.coverage().indexedFiles());
        assertTrue(headIndex.relationships().stream().anyMatch(edge -> edge.typeResolved() && edge.type() == SemanticModels.RelationType.CALLS));
        var files = (List<?>) result.get("files");
        assertEquals(4, files.size());
        assertFalse(files.toString().contains("DO_NOT_INCLUDE"));
        assertTrue(files.toString().contains("return 1")); assertTrue(files.toString().contains("return 2"));
        assertFalse(files.toString().contains("dirty work"));
        assertEquals("dirty work belongs to the user", Files.readString(source.resolve("Service.java")));
    }

    @Test void rejectsRefsThatAreNotFrozenShas() {
        assertThrows(IllegalArgumentException.class, () -> LocalReviewCli.analyze(root, "HEAD", "HEAD^"));
    }

    private String git(String... args) throws Exception {
        var command = new java.util.ArrayList<>(List.of("git", "-C", root.toString())); command.addAll(List.of(args));
        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        assertTrue(process.waitFor(10, TimeUnit.SECONDS));
        var output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), output); return output;
    }
}
