package ai.codelens.semantic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Path;

import static ai.codelens.semantic.SemanticModels.RelationType.CALLS;
import static ai.codelens.semantic.SemanticModels.RelationType.TESTS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "codelens.acceptance.repository", matches = ".+")
class SelfRepositorySemanticAcceptanceTest {
    @Test
    void indexesARealMultiPackageRepositoryAndFindsUnchangedConsumers() {
        Path repository = Path.of(System.getProperty("codelens.acceptance.repository")).toAbsolutePath().normalize();
        BuildModel model = new BuildModelDetector().detect(repository);
        SemanticModels.Index index = new JavaSemanticAdapter().index(repository,
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", model);

        String digest = "java:method:ai.codelens.intelligence.CodeIntelligenceService#digest(java.lang.String)";
        assertTrue(index.incoming(digest, CALLS).stream()
                .anyMatch(edge -> edge.sourcePath().equals("src/main/java/ai/codelens/review/ReviewEngine.java")));

        String parse = "java:method:ai.codelens.intelligence.CodeIntelligenceService#parse(java.lang.String,java.lang.String,java.lang.String)";
        assertTrue(index.incoming(parse, TESTS).stream()
                .anyMatch(edge -> edge.sourcePath().equals("src/test/java/ai/codelens/intelligence/CodeIntelligenceServiceTest.java")));

        assertEquals(0, index.coverage().failedFiles());
        assertTrue(index.coverage().indexedFiles() >= 25);
        System.out.printf("SEMANTIC_ACCEPTANCE files=%d symbols=%d relationships=%d resolved=%d unresolved=%d level=%s%n",
                index.coverage().indexedFiles(), index.symbols().size(), index.relationships().size(),
                index.coverage().resolvedRelationships(), index.coverage().unresolvedRelationships(), index.coverage().level());
    }
}

