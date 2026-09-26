package ai.codelens.semantic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuildModelDetectorTest {
    @TempDir Path root;

    @Test
    void detectsMavenModulesAndHashesDescriptorContent() throws Exception {
        Files.writeString(root.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion></project>");
        Files.createDirectories(root.resolve("src/main/java"));
        Files.createDirectories(root.resolve("src/test/java"));

        BuildModelDetector detector = new BuildModelDetector();
        BuildModel first = detector.detect(root);

        assertEquals(BuildModel.BuildSystem.MAVEN, first.system());
        assertEquals(1, first.modules().size());
        assertEquals("src/main/java", first.modules().get(0).mainSourceRoots().get(0));
        assertEquals("src/test/java", first.modules().get(0).testSourceRoots().get(0));
        assertTrue(first.degradations().isEmpty());

        Files.writeString(root.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion><version>2</version></project>");
        BuildModel second = detector.detect(root);
        assertNotEquals(first.hash(), second.hash());
    }

    @Test
    void reportsUnknownLayoutsInsteadOfClaimingSemanticCoverage() throws Exception {
        Files.createDirectories(root.resolve("source"));
        BuildModel model = new BuildModelDetector().detect(root);

        assertEquals(BuildModel.BuildSystem.UNKNOWN, model.system());
        assertEquals("no_maven_or_gradle_descriptor", model.degradations().get("build_system"));
        assertEquals("no_conventional_java_source_roots", model.degradations().get("java_sources"));
    }
}

