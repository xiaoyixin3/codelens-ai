package ai.codelens.semantic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SemanticHoldoutScopeSelectorTest {
    @TempDir Path repository;

    @Test
    void selectsCompactMainSourcesDeterministicallyWithoutSemanticOutput() throws Exception {
        write("pom.xml", "<project><modelVersion>4.0.0</modelVersion></project>");
        write("src/main/java/example/Alpha.java", eligibleSource("Alpha"));
        write("src/main/java/example/Beta.java", eligibleSource("Beta"));
        write("src/test/java/example/AlphaTest.java", eligibleSource("AlphaTest"));
        write("src/main/java/generated/Generated.java", eligibleSource("Generated"));
        write("src/main/java/example/Tiny.java", "package example; public final class Tiny {}\n");
        BuildModel model = new BuildModelDetector().detect(repository);
        SemanticHoldoutScopeSelector selector = new SemanticHoldoutScopeSelector();

        SemanticHoldoutScopeSelector.Selection first = selector.select(repository, "example/project",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", model, 2);
        SemanticHoldoutScopeSelector.Selection second = selector.select(repository, "example/project",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", model, 2);

        assertEquals(SemanticHoldoutScopeSelector.POLICY_VERSION, first.policyVersion());
        assertEquals(first, second);
        assertEquals(2, first.eligibleFiles());
        assertEquals(2, first.selected().size());
        assertTrue(first.sourcePaths().contains("src/main/java/example/Alpha.java"));
        assertTrue(first.sourcePaths().contains("src/main/java/example/Beta.java"));
        assertFalse(first.sourcePaths().stream().anyMatch(path -> path.contains("Test") || path.contains("generated")));
        assertTrue(first.selected().stream().allMatch(candidate -> candidate.lexicalCallSites() >= 3));
    }

    @Test
    void failsClosedWhenTheRequestedScopeCannotBeFilled() throws Exception {
        write("pom.xml", "<project><modelVersion>4.0.0</modelVersion></project>");
        write("src/main/java/example/Tiny.java", "package example; public final class Tiny {}\n");
        BuildModel model = new BuildModelDetector().detect(repository);

        assertThrows(IllegalArgumentException.class, () -> new SemanticHoldoutScopeSelector().select(
                repository, "example/project", "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", model, 1));
    }

    private static String eligibleSource(String type) {
        StringBuilder source = new StringBuilder("package example;\npublic final class ").append(type).append(" {\n");
        source.append("  public void run() { Helper.one(); Helper.two(); new Helper(); }\n");
        for (int line = 0; line < 65; line++) source.append("  // context ").append(line).append('\n');
        return source.append("  static final class Helper { static void one() {} static void two() {} }\n}\n").toString();
    }

    private void write(String relative, String content) throws Exception {
        Path path = repository.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }
}
