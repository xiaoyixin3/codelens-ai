package ai.codelens.semantic;

import com.fasterxml.jackson.databind.ObjectMapper;
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

class SemanticReviewPacketBuilderTest {
    @TempDir Path repository;

    @Test
    void createsAssistedSilverPacketsAndPredictionFreeGoldPackets() throws Exception {
        Files.writeString(repository.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion></project>");
        write("src/main/java/example/Target.java", """
                package example;
                public final class Target { public void execute() {} }
                """);
        write("src/main/java/example/Caller.java", """
                package example;
                public final class Caller {
                    public void run() {
                        new Target().execute();
                    }
                }
                """);
        String sha = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        String source = "src/main/java/example/Caller.java";
        List<String> prefixes = List.of("java:method:example.", "java:constructor:example.");
        BuildModel model = new BuildModelDetector().detect(repository);
        JavacCallOracle oracle = new JavacCallOracle();
        JavacCallOracle.Result result = oracle.analyze(repository, model, Set.of(source), prefixes);
        SemanticModels.Index adapter = new JavaSemanticAdapter().index(repository, sha, model);
        JavacCallOracle.Comparison comparison = oracle.compare(result, adapter, Set.of(source), prefixes);

        SemanticReviewPacketBuilder builder = new SemanticReviewPacketBuilder();
        SemanticReviewPacketBuilder.SilverPacket silver = builder.silver(
                repository, "example/repository", sha, comparison, 10);
        assertEquals("silver/compiler-oracle", silver.evidenceTier());
        assertTrue(silver.predictionsVisible());
        assertEquals(1.0, silver.toolAgreement());
        assertEquals(1, silver.items().size());
        assertEquals("agreement_sample", silver.items().get(0).kind());
        assertTrue(String.join("\n", silver.items().get(0).excerpt().lines()).contains("new Target().execute()"));
        assertFalse(silver.items().get(0).adapterTargets().isEmpty());
        assertEquals(silver.items().get(0).adapterTargets(), silver.items().get(0).oracleTargets());

        SemanticReviewPacketBuilder.GoldPacket gold = builder.gold(
                repository, "example/repository", sha, Set.of(source));
        assertEquals("gold/independent-holdout", gold.evidenceTier());
        assertFalse(gold.predictionsVisible());
        assertEquals(64, gold.contextDigest().length());
        assertEquals(1, gold.scopeFiles().size());
        String goldJson = new ObjectMapper().findAndRegisterModules().writeValueAsString(gold);
        assertFalse(goldJson.contains("adapterTargets"));
        assertFalse(goldJson.contains("oracleTargets"));

        assertThrows(IllegalArgumentException.class,
                () -> builder.gold(repository, "example/repository", sha, Set.of("../outside.java")));
    }

    private void write(String relative, String content) throws Exception {
        Path path = repository.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }
}
