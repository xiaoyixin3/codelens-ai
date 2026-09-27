package ai.codelens.semantic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SemanticGoldPacketCliTest {
    @TempDir Path temporary;

    @Test
    void sealsAContextAddressedPacketWithoutPredictionFields() throws Exception {
        Path repository = temporary.resolve("repository");
        write(repository, "pom.xml", "<project><modelVersion>4.0.0</modelVersion></project>");
        write(repository, "README.md", "full context");
        write(repository, "src/main/java/example/ReviewMe.java", eligibleSource());
        git(repository, "init");
        git(repository, "config", "user.email", "test@example.invalid");
        git(repository, "config", "user.name", "Packet Test");
        git(repository, "add", ".");
        git(repository, "commit", "-m", "fixture");
        String sha = git(repository, "rev-parse", "HEAD").trim();
        Path output = temporary.resolve("packets");

        Path packet = SemanticGoldPacketCli.run(new String[]{
                "--repository-root", repository.toString(),
                "--repository", "example/project",
                "--commit", sha,
                "--target-prefix", "java:method:example.",
                "--target-prefix", "java:constructor:example.",
                "--output-root", output.toString()
        });

        assertTrue(Files.isRegularFile(packet.resolve("gold-packet.json")));
        assertTrue(Files.isRegularFile(packet.resolve("scope-selection.json")));
        assertTrue(Files.isRegularFile(packet.resolve("REVIEWER-INSTRUCTIONS.md")));
        assertTrue(Files.isRegularFile(packet.resolve("repository-context.zip")));
        assertTrue(Files.isRegularFile(packet.resolve("CONTEXT-ARCHIVE.sha256")));
        assertTrue(Files.isRegularFile(packet.resolve("reviewer-submission-template.json")));
        try (java.util.zip.ZipFile archive = new java.util.zip.ZipFile(packet.resolve("repository-context.zip").toFile())) {
            assertEquals(3, archive.size());
            assertFalse(archive.stream().anyMatch(entry -> entry.getName().startsWith(".git/")));
        }
        String json = Files.readString(packet.resolve("gold-packet.json"));
        assertFalse(json.contains("adapterTargets"));
        assertFalse(json.contains("oracleTargets"));
        assertFalse(json.contains("toolAgreement"));
        JsonNode tree = new ObjectMapper().readTree(json);
        assertFalse(tree.get("predictionsVisible").asBoolean());
        assertEquals(3, tree.get("contextFileCount").asInt());
        assertEquals(2, tree.get("targetPrefixes").size());
        assertThrows(IllegalStateException.class, () -> SemanticGoldPacketCli.run(new String[]{
                "--repository-root", repository.toString(),
                "--repository", "example/project",
                "--commit", sha,
                "--target-prefix", "java:method:example.",
                "--target-prefix", "java:constructor:example.",
                "--output-root", output.toString()
        }));
    }

    @Test
    void refusesDirtyOrMismatchedRepositories() throws Exception {
        Path repository = temporary.resolve("dirty-repository");
        write(repository, "pom.xml", "<project><modelVersion>4.0.0</modelVersion></project>");
        write(repository, "src/main/java/example/ReviewMe.java", eligibleSource());
        git(repository, "init");
        git(repository, "config", "user.email", "test@example.invalid");
        git(repository, "config", "user.name", "Packet Test");
        git(repository, "add", ".");
        git(repository, "commit", "-m", "fixture");
        String sha = git(repository, "rev-parse", "HEAD").trim();
        write(repository, "UNTRACKED.txt", "dirty");

        assertThrows(IllegalArgumentException.class, () -> SemanticGoldPacketCli.run(new String[]{
                "--repository-root", repository.toString(),
                "--repository", "example/project",
                "--commit", sha,
                "--target-prefix", "java:method:example.",
                "--output-root", temporary.resolve("packets-dirty").toString()
        }));
    }

    private static String eligibleSource() {
        StringBuilder source = new StringBuilder("package example;\npublic final class ReviewMe {\n")
                .append("  public void run() { Helper.one(); Helper.two(); new Helper(); }\n");
        for (int line = 0; line < 65; line++) source.append("  // review context ").append(line).append('\n');
        return source.append("  static final class Helper { static void one() {} static void two() {} }\n}\n").toString();
    }

    private static void write(Path repository, String relative, String content) throws Exception {
        Path path = repository.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    private static String git(Path repository, String... arguments) throws Exception {
        Files.createDirectories(repository);
        java.util.List<String> command = new java.util.ArrayList<>(java.util.List.of("git", "-C", repository.toString()));
        command.addAll(java.util.List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
        return output;
    }
}
