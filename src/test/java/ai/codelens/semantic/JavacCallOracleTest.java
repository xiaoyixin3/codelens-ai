package ai.codelens.semantic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavacCallOracleTest {
    @TempDir Path repository;

    @Test
    void attributesInternalCallsWithoutExecutingBuildsOrAnnotationProcessors() throws Exception {
        Files.writeString(repository.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion></project>");
        write("src/main/java/example/Target.java", """
                package example;
                public final class Target {
                    public Target(String value) {}
                    public void execute(String value) {}
                    public void execute(int value) {}
                }
                """);
        write("src/main/java/example/Caller.java", """
                package example;
                public final class Caller {
                    public void run() {
                        Target target = new Target("value");
                        target.execute("value");
                    }
                }
                """);

        BuildModel model = new BuildModelDetector().detect(repository);
        JavacCallOracle.Result result = new JavacCallOracle().analyze(repository, model,
                Set.of("src/main/java/example/Caller.java"),
                List.of("java:method:example.", "java:constructor:example."));

        assertEquals(0, result.compilerErrors(), () -> result.diagnostics().toString());
        assertEquals(2, result.compiledSourceFiles());
        assertEquals(Set.of(
                new SemanticTruthSetEvaluator.CallFact("src/main/java/example/Caller.java", 4,
                        "java:method:example.Caller#run()", "java:constructor:example.Target#<init>(java.lang.String)"),
                new SemanticTruthSetEvaluator.CallFact("src/main/java/example/Caller.java", 5,
                        "java:method:example.Caller#run()", "java:method:example.Target#execute(java.lang.String)")
        ), Set.copyOf(result.calls()));
        assertTrue(result.diagnostics().isEmpty(), () -> result.diagnostics().toString());
        try (var files = Files.walk(repository)) {
            assertFalse(files.anyMatch(path -> path.getFileName().toString().endsWith(".class")),
                    "compiler oracle must not generate class files");
        }

        SemanticModels.Index adapter = new JavaSemanticAdapter().index(repository,
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", model);
        JavacCallOracle.Comparison comparison = new JavacCallOracle().compare(result, adapter,
                Set.of("src/main/java/example/Caller.java"),
                List.of("java:method:example.", "java:constructor:example."));
        assertEquals("silver/compiler-oracle", comparison.evidenceTier());
        assertEquals(1.0, comparison.agreementRate());
        assertEquals(2, comparison.agreements().size());
        assertTrue(comparison.adapterOnly().isEmpty());
        assertTrue(comparison.oracleOnly().isEmpty());
        assertEquals(1, comparison.reviewQueue(2).size(), "only a deterministic agreement sample needs human audit");
        assertThrows(IllegalArgumentException.class, () -> new JavacCallOracle().analyze(repository, model,
                Set.of("../outside.java"), List.of("java:method:example.")));
    }

    private void write(String relative, String content) throws Exception {
        Path path = repository.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }
}
