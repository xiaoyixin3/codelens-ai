package ai.codelens.semantic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        assertEquals("no_declared_or_conventional_java_source_roots", model.degradations().get("java_sources"));
    }

    @Test
    void resolvesLiteralMavenDependenciesOnlyFromAnExternalBoundedCache() throws Exception {
        Files.writeString(root.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                  <properties><helper.version>1.2.3</helper.version></properties>
                  <dependencies>
                    <dependency><groupId>example.libs</groupId><artifactId>helper</artifactId>
                      <version>${helper.version}</version></dependency>
                  </dependencies>
                </project>
                """);
        Files.createDirectories(root.resolve("src/main/java"));
        Path cache = root.getParent().resolve("trusted-cache");
        Path jar = cache.resolve("example/libs/helper/1.2.3/helper-1.2.3.jar");
        Files.createDirectories(jar.getParent());
        Files.write(jar, new byte[]{1, 2, 3});

        BuildModel first = new BuildModelDetector(cache, 5, 1024).detect(root);

        assertEquals(List.of("example.libs:helper:1.2.3"),
                first.dependencies().stream().map(BuildModel.Dependency::coordinate).toList());
        assertEquals(1, first.dependencyClasspath().size());
        assertEquals(3, first.dependencyClasspath().get(0).size());
        assertEquals("dependency_direct_declarations_only", first.degradations().get("dependency_resolution_scope"));

        Files.write(jar, new byte[]{1, 2, 3, 4});
        BuildModel second = new BuildModelDetector(cache, 5, 1024).detect(root);
        assertNotEquals(first.hash(), second.hash(), "cache content must participate in snapshot identity");
    }

    @Test
    void rejectsXmlWithDoctypesWithoutReadingExternalEntities() throws Exception {
        Path cache = root.resolve("cache");
        Files.createDirectories(cache);
        Files.writeString(root.resolve("pom.xml"), """
                <!DOCTYPE project [<!ENTITY xxe SYSTEM "file:///definitely-not-readable">]>
                <project><modelVersion>4.0.0</modelVersion><dependencies>
                  <dependency><groupId>example</groupId><artifactId>bad</artifactId><version>&xxe;</version></dependency>
                </dependencies></project>
                """);
        Files.createDirectories(root.resolve("src/main/java"));

        BuildModel model = new BuildModelDetector(cache, 5, 1024).detect(root);

        assertTrue(model.dependencies().isEmpty());
        assertEquals("dependency_descriptor_unparseable", model.degradations().get("pom.xml"));
        assertTrue(model.dependencyClasspath().isEmpty());
    }

    @Test
    void refusesARepositoryOwnedDependencyCache() throws Exception {
        Files.writeString(root.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion><dependencies>
                  <dependency><groupId>example</groupId><artifactId>helper</artifactId><version>1</version></dependency>
                </dependencies></project>
                """);
        Files.createDirectories(root.resolve("src/main/java"));
        Path cache = root.resolve("cache");
        Path jar = cache.resolve("example/helper/1/helper-1.jar");
        Files.createDirectories(jar.getParent());
        Files.write(jar, new byte[]{1});

        BuildModel model = new BuildModelDetector(cache, 5, 1024).detect(root);

        assertTrue(model.dependencyClasspath().isEmpty());
        assertEquals("dependency_cache_untrusted_or_unavailable", model.degradations().get("dependency_cache"));
    }

    @Test
    void readsOnlyLiteralGradleCoordinatesAndFlagsCatalogBasedDeclarations() throws Exception {
        Files.writeString(root.resolve("build.gradle.kts"), """
                dependencies {
                    implementation("example.libs:helper:2.0")
                    // implementation("commented:dependency:9")
                    testImplementation(libs.junit)
                }
                """);
        Files.createDirectories(root.resolve("src/main/java"));

        BuildModel model = new BuildModelDetector().detect(root);

        assertEquals(List.of("example.libs:helper:2.0"),
                model.dependencies().stream().map(BuildModel.Dependency::coordinate).toList());
        assertEquals("dependency_declaration_dynamic_or_catalog",
                model.degradations().get("build.gradle.kts#gradle"));
    }

    @Test
    void discoversLiteralMavenCustomSourceRootsWithoutRunningMaven() throws Exception {
        Files.writeString(root.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                  <properties><main.dir>code/main</main.dir></properties>
                  <build>
                    <sourceDirectory>${project.basedir}/${main.dir}</sourceDirectory>
                    <testSourceDirectory>code/test</testSourceDirectory>
                  </build>
                </project>
                """);
        Files.createDirectories(root.resolve("code/main"));
        Files.createDirectories(root.resolve("code/test"));
        Files.createDirectories(root.resolve("src/main/java"));
        Files.createDirectories(root.resolve("src/test/java"));

        BuildModel model = new BuildModelDetector().detect(root);

        assertEquals(List.of("code/main"), model.modules().get(0).mainSourceRoots());
        assertEquals(List.of("code/test"), model.modules().get(0).testSourceRoots());
        assertFalse(model.degradations().containsKey("java_sources"));
    }

    @Test
    void rejectsOutsideAndMissingCustomSourceRoots() throws Exception {
        Files.writeString(root.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion><build>
                  <sourceDirectory>../outside</sourceDirectory>
                  <testSourceDirectory>missing/tests</testSourceDirectory>
                </build></project>
                """);
        Files.createDirectories(root.getParent().resolve("outside"));

        BuildModel model = new BuildModelDetector().detect(root);

        assertTrue(model.modules().get(0).mainSourceRoots().isEmpty());
        assertTrue(model.modules().get(0).testSourceRoots().isEmpty());
        assertTrue(model.degradations().containsValue("custom_source_root_outside_repository"));
        assertTrue(model.degradations().containsValue("custom_source_root_missing_or_symlink"));
    }

    @Test
    void discoversOnlyLiteralGradleCustomSourceRoots() throws Exception {
        Files.writeString(root.resolve("build.gradle.kts"), """
                sourceSets.main.java.srcDirs = ["code/main"]
                sourceSets["test"].java.srcDirs("code/test")
                """);
        Files.createDirectories(root.resolve("code/main"));
        Files.createDirectories(root.resolve("code/test"));
        Files.createDirectories(root.resolve("src/main/java"));

        BuildModel model = new BuildModelDetector().detect(root);

        assertEquals(List.of("code/main"), model.modules().get(0).mainSourceRoots());
        assertEquals(List.of("code/test"), model.modules().get(0).testSourceRoots());
    }

    @Test
    void refusesDynamicGradleSourceExpressions() throws Exception {
        Files.writeString(root.resolve("build.gradle.kts"), """
                sourceSets.main.java.srcDir(layout.projectDirectory.dir("code/main"))
                """);
        Files.createDirectories(root.resolve("code/main"));

        BuildModel model = new BuildModelDetector().detect(root);

        assertTrue(model.modules().get(0).mainSourceRoots().isEmpty());
        assertEquals("custom_source_layout_dynamic_or_unsupported",
                model.degradations().get("build.gradle.kts#source-layout"));
    }
}

