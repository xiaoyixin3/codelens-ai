package ai.codelens.semantic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "codelens.junit4.repository", matches = ".+")
class JUnit4SemanticSilverAcceptanceTest {
    private static final String PINNED_COMMIT = "05fe2a64f59127c02135be22f416e91260d6ede6";
    private static final String SOURCE_PATH = "src/main/java/org/junit/runners/BlockJUnit4ClassRunner.java";

    @Test
    void producesAReusableLowCostReviewQueueOnASecondRealRepository() throws Exception {
        Path repository = Path.of(System.getProperty("codelens.junit4.repository")).toAbsolutePath().normalize();
        assertEquals(PINNED_COMMIT, gitHead(repository));
        BuildModel model = new BuildModelDetector().detect(repository);
        SemanticModels.Index index = new JavaSemanticAdapter().index(repository, PINNED_COMMIT, model);
        List<String> prefixes = List.of("java:method:org.junit.", "java:constructor:org.junit.");
        Set<String> scope = Set.of(SOURCE_PATH);

        JavacCallOracle oracle = new JavacCallOracle();
        JavacCallOracle.Result attributed = oracle.analyze(repository, model, scope, prefixes);
        JavacCallOracle.Comparison comparison = oracle.compare(attributed, index, scope, prefixes);
        SemanticReviewPacketBuilder packets = new SemanticReviewPacketBuilder();
        SemanticReviewPacketBuilder.SilverPacket silver = packets.silver(
                repository, "junit-team/junit4", PINNED_COMMIT, comparison, 10);
        SemanticReviewPacketBuilder.GoldPacket gold = packets.gold(
                repository, "junit-team/junit4", PINNED_COMMIT, scope, prefixes);

        System.out.printf("JUNIT4_SILVER commit=%s adapterCalls=%d oracleCalls=%d agreements=%d adapterOnly=%d "
                        + "oracleOnly=%d agreement=%.4f compilerErrors=%d reviewItems=%d indexedFiles=%d failedFiles=%d%n",
                PINNED_COMMIT,
                comparison.agreements().size() + comparison.adapterOnly().size(),
                comparison.agreements().size() + comparison.oracleOnly().size(),
                comparison.agreements().size(), comparison.adapterOnly().size(), comparison.oracleOnly().size(),
                comparison.agreementRate(), comparison.compilerErrors(), silver.items().size(),
                index.coverage().indexedFiles(), index.coverage().failedFiles());

        assertEquals(0, index.coverage().failedFiles());
        assertTrue(comparison.agreementRate() >= 0.90, () -> "silver tool agreement=" + comparison.agreementRate()
                + ", adapterOnly=" + comparison.adapterOnly() + ", oracleOnly=" + comparison.oracleOnly());
        assertTrue(silver.predictionsVisible());
        assertFalse(gold.predictionsVisible());
        assertFalse(silver.items().isEmpty());
    }

    private static String gitHead(Path repository) throws Exception {
        Process process = new ProcessBuilder("git", "-C", repository.toString(), "rev-parse", "HEAD")
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        assertEquals(0, process.waitFor(), () -> "unable to read repository revision: " + output);
        return output;
    }
}
