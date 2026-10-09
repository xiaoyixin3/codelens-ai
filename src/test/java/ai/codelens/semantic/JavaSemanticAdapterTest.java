package ai.codelens.semantic;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.ToolProvider;

import static ai.codelens.semantic.SemanticModels.RelationType.CALLS;
import static ai.codelens.semantic.SemanticModels.RelationType.ANNOTATED_WITH;
import static ai.codelens.semantic.SemanticModels.RelationType.CATCHES;
import static ai.codelens.semantic.SemanticModels.RelationType.IMPLEMENTS;
import static ai.codelens.semantic.SemanticModels.RelationType.OVERRIDES;
import static ai.codelens.semantic.SemanticModels.RelationType.READS;
import static ai.codelens.semantic.SemanticModels.RelationType.TESTS;
import static ai.codelens.semantic.SemanticModels.RelationType.THROWS;
import static ai.codelens.semantic.SemanticModels.RelationType.WRITES;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaSemanticAdapterTest {
    @TempDir Path repository;

    @BeforeEach
    void fixture() throws Exception {
        Files.writeString(repository.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion></project>");
        write("src/main/java/example/Receipt.java", """
                package example;
                public record Receipt(String id) {}
                """);
        write("src/main/java/example/PaymentGateway.java", """
                package example;
                public interface PaymentGateway { Receipt charge(String account); }
                """);
        write("src/main/java/example/StripeGateway.java", """
                package example;
                public final class StripeGateway implements PaymentGateway {
                    public Receipt charge(String account) { return new Receipt(account); }
                }
                """);
        write("src/main/java/example/PaymentService.java", """
                package example;
                public final class PaymentService {
                    private final PaymentGateway gateway;
                    public PaymentService(PaymentGateway gateway) { this.gateway = gateway; }
                    public Receipt charge(String account) { return gateway.charge(account); }
                    public Receipt charge(int account) { return gateway.charge(Integer.toString(account)); }
                }
                """);
        write("src/main/java/example/OtherService.java", """
                package example;
                public final class OtherService {
                    public Receipt charge(String account) { return new Receipt("other-" + account); }
                }
                """);
        write("src/main/java/example/PaymentController.java", """
                package example;
                public final class PaymentController {
                    private final PaymentService service;
                    public PaymentController(PaymentService service) { this.service = service; }
                    public Receipt pay(String account) { return service.charge(account); }
                }
                """);
        write("src/test/java/example/PaymentServiceTest.java", """
                package example;
                public final class PaymentServiceTest {
                    public void chargeUsesGateway() {
                        PaymentService service = new PaymentService(new StripeGateway());
                        service.charge("acct");
                    }
                }
                """);
    }

    @Test
    void indexesTheWholeRepositoryAndFindsUnchangedCallersAndTestsByResolvedType() {
        BuildModel model = new BuildModelDetector().detect(repository);
        SemanticModels.Index index = new JavaSemanticAdapter().index(
                repository, "1111111111111111111111111111111111111111", model
        );

        String charge = "java:method:example.PaymentService#charge(java.lang.String)";
        String controller = "java:method:example.PaymentController#pay(java.lang.String)";
        String test = "java:method:example.PaymentServiceTest#chargeUsesGateway()";
        assertEquals(SemanticModels.CoverageLevel.SEMANTIC, index.coverage().level());
        assertEquals(7, index.coverage().indexedFiles());

        assertTrue(index.incoming(charge, CALLS).stream().anyMatch(edge -> edge.fromStableKey().equals(controller)
                && edge.typeResolved() && edge.sourcePath().endsWith("PaymentController.java")));
        assertTrue(index.incoming(charge, TESTS).stream().anyMatch(edge -> edge.fromStableKey().equals(test)
                && edge.typeResolved() && edge.sourcePath().endsWith("PaymentServiceTest.java")));

        String wrongTarget = "java:method:example.OtherService#charge(java.lang.String)";
        assertFalse(index.relationships().stream().anyMatch(edge -> edge.fromStableKey().equals(controller)
                && edge.toStableKey().equals(wrongTarget)));
    }

    @Test
    void recordsInheritanceAndEveryRelationshipCarriesEvidenceLocation() {
        SemanticModels.Index index = new JavaSemanticAdapter().index(repository,
                "2222222222222222222222222222222222222222", new BuildModelDetector().detect(repository));

        assertTrue(index.relationships().stream().anyMatch(edge -> edge.type() == IMPLEMENTS
                && edge.fromStableKey().equals("java:type:example.StripeGateway")
                && edge.toStableKey().equals("java:type:example.PaymentGateway")
                && edge.typeResolved()));
        assertTrue(index.relationships().stream().anyMatch(edge -> edge.type() == OVERRIDES
                && edge.fromStableKey().equals("java:method:example.StripeGateway#charge(java.lang.String)")
                && edge.toStableKey().equals("java:method:example.PaymentGateway#charge(java.lang.String)")
                && edge.typeResolved()));
        assertTrue(index.relationships().stream().anyMatch(edge -> edge.type() == WRITES
                && edge.fromStableKey().equals("java:constructor:example.PaymentService#<init>(example.PaymentGateway)")
                && edge.toStableKey().equals("java:field:example.PaymentService#gateway")));
        assertTrue(index.relationships().stream().anyMatch(edge -> edge.type() == READS
                && edge.fromStableKey().equals("java:method:example.PaymentService#charge(java.lang.String)")
                && edge.toStableKey().equals("java:field:example.PaymentService#gateway")));
        assertTrue(index.relationships().stream().allMatch(edge -> !edge.sourcePath().isBlank() && edge.sourceLine() > 0));
    }

    @Test
    void indexesDeclaredThrownAndCaughtExceptionContracts() throws Exception {
        write("src/main/java/example/FailureBoundary.java", """
                package example;
                import java.io.IOException;
                public final class FailureBoundary {
                    public void risky() throws IOException { throw new IOException("failure"); }
                    public void handle() { try { risky(); } catch (IOException ignored) { } }
                }
                """);
        SemanticModels.Index index = new JavaSemanticAdapter().index(repository,
                "8888888888888888888888888888888888888888", new BuildModelDetector().detect(repository));
        String risky = "java:method:example.FailureBoundary#risky()";
        String handle = "java:method:example.FailureBoundary#handle()";

        assertTrue(index.relationships().stream().anyMatch(edge -> edge.type() == THROWS
                && edge.fromStableKey().equals(risky)
                && edge.toStableKey().equals("java:type:java.io.IOException")
                && edge.typeResolved()));
        assertTrue(index.relationships().stream().anyMatch(edge -> edge.type() == CATCHES
                && edge.fromStableKey().equals(handle)
                && edge.toStableKey().equals("java:type:java.io.IOException")
                && edge.typeResolved()));
    }

    @Test
    void incrementalHeadReusesUnaffectedFilesAndReparsesDirectCallers() throws Exception {
        JavaSemanticAdapter adapter = new JavaSemanticAdapter();
        BuildModel model = new BuildModelDetector().detect(repository);
        SemanticModels.Index base = adapter.index(repository,
                "4444444444444444444444444444444444444444", model);
        write("src/main/java/example/PaymentService.java", """
                package example;
                public final class PaymentService {
                    private final PaymentGateway gateway;
                    public PaymentService(PaymentGateway gateway) { this.gateway = gateway; }
                    public Receipt charge(String account) { return gateway.charge(account.trim()); }
                    public Receipt charge(int account) { return gateway.charge(Integer.toString(account)); }
                }
                """);

        SemanticModels.Index head = adapter.indexIncremental(repository,
                "5555555555555555555555555555555555555555", model, base,
                Set.of("src/main/java/example/PaymentService.java"));

        String charge = "java:method:example.PaymentService#charge(java.lang.String)";
        String controller = "java:method:example.PaymentController#pay(java.lang.String)";
        assertTrue(head.coverage().reusedFiles() > 0);
        assertTrue(head.incoming(charge, CALLS).stream().anyMatch(edge -> edge.fromStableKey().equals(controller)));
        assertEquals(Long.valueOf(head.coverage().reusedFiles()), head.coverage().degradationReasons().get("incremental_reused_file"));
    }

    @Test
    void incrementalHeadRevisitsAnUnchangedCallerWhenANewTargetBecomesResolvable() throws Exception {
        write("src/main/java/example/FutureClient.java", """
                package example;
                public final class FutureClient {
                    private final FutureService service;
                    public FutureClient(FutureService service) { this.service = service; }
                    public Receipt run() { return service.run(); }
                }
                """);
        JavaSemanticAdapter adapter = new JavaSemanticAdapter();
        BuildModel model = new BuildModelDetector().detect(repository);
        SemanticModels.Index base = adapter.index(repository,
                "6666666666666666666666666666666666666666", model);
        assertTrue(base.relationships().stream().anyMatch(edge -> edge.sourcePath().endsWith("FutureClient.java")
                && edge.toStableKey().equals("unresolved:method:run/0")));

        write("src/main/java/example/FutureService.java", """
                package example;
                public final class FutureService {
                    public Receipt run() { return new Receipt("future"); }
                }
                """);
        SemanticModels.Index head = adapter.indexIncremental(repository,
                "7777777777777777777777777777777777777777", model, base,
                Set.of("src/main/java/example/FutureService.java"));

        String target = "java:method:example.FutureService#run()";
        assertTrue(head.incoming(target, CALLS).stream().anyMatch(edge -> edge.sourcePath().endsWith("FutureClient.java")
                && edge.typeResolved()));
    }

    @Test
    void directCallTruthSetMeetsThePhaseOnePrecisionThreshold() {
        SemanticModels.Index index = new JavaSemanticAdapter().index(repository,
                "3333333333333333333333333333333333333333", new BuildModelDetector().detect(repository));
        Set<String> expectedInternalCalls = Set.of(
                "example.StripeGateway#charge(java.lang.String)->example.Receipt#<init>(java.lang.String)",
                "example.PaymentService#charge(java.lang.String)->example.PaymentGateway#charge(java.lang.String)",
                "example.PaymentService#charge(int)->example.PaymentGateway#charge(java.lang.String)",
                "example.OtherService#charge(java.lang.String)->example.Receipt#<init>(java.lang.String)",
                "example.PaymentController#pay(java.lang.String)->example.PaymentService#charge(java.lang.String)",
                "example.PaymentServiceTest#chargeUsesGateway()->example.PaymentService#<init>(example.PaymentGateway)",
                "example.PaymentServiceTest#chargeUsesGateway()->example.StripeGateway#<init>()",
                "example.PaymentServiceTest#chargeUsesGateway()->example.PaymentService#charge(java.lang.String)"
        );
        Set<String> actual = index.relationships().stream()
                .filter(edge -> edge.type() == CALLS && edge.typeResolved())
                .filter(edge -> edge.fromStableKey().startsWith("java:method:") || edge.fromStableKey().startsWith("java:constructor:"))
                .filter(edge -> edge.toStableKey().startsWith("java:method:example.") || edge.toStableKey().startsWith("java:constructor:example."))
                .map(JavaSemanticAdapterTest::displayCall)
                .collect(java.util.stream.Collectors.toSet());

        long correct = actual.stream().filter(expectedInternalCalls::contains).count();
        double precision = actual.isEmpty() ? 0.0 : (double) correct / actual.size();
        assertTrue(precision >= 0.90, () -> "precision=" + precision + ", actual=" + actual);
        assertEquals(expectedInternalCalls, actual, "synthetic direct-call truth set must have neither false positives nor misses");
    }

    @Test
    void resolvesExternalCallsFromAnIntegrityCheckedOperatorCache() throws Exception {
        Files.writeString(repository.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion><dependencies>
                  <dependency><groupId>external</groupId><artifactId>helper</artifactId><version>1.0</version></dependency>
                </dependencies></project>
                """);
        write("src/main/java/example/ExternalCaller.java", """
                package example;
                import external.Helper;
                public final class ExternalCaller { public int call() { return Helper.answer(); } }
                """);
        Path cache = repository.getParent().resolve("operator-cache");
        createDependencyJar(cache.resolve("external/helper/1.0/helper-1.0.jar"));
        BuildModel model = new BuildModelDetector(cache, 10, 1024 * 1024).detect(repository);

        SemanticModels.Index index = new JavaSemanticAdapter().index(repository,
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", model);

        assertTrue(index.relationships().stream().anyMatch(edge -> edge.type() == CALLS
                && edge.fromStableKey().equals("java:method:example.ExternalCaller#call()")
                && edge.toStableKey().equals("java:method:external.Helper#answer()")
                && edge.typeResolved()));
        assertFalse(index.coverage().degradationReasons().containsKey("dependency_jar_unavailable"));
    }

    @Test
    void marksDeclaredDependenciesPartialWhenNoTrustedCacheIsConfigured() throws Exception {
        Files.writeString(repository.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion><dependencies>
                  <dependency><groupId>external</groupId><artifactId>helper</artifactId><version>1.0</version></dependency>
                </dependencies></project>
                """);

        SemanticModels.Index index = new JavaSemanticAdapter().index(repository,
                "cccccccccccccccccccccccccccccccccccccccc", new BuildModelDetector().detect(repository));

        assertEquals(SemanticModels.CoverageLevel.SEMANTIC_PARTIAL, index.coverage().level());
        assertEquals(1L, index.coverage().degradationReasons().get("dependency_cache_not_configured"));
    }

    @Test
    void refusesACacheJarChangedAfterTheBuildModelWasHashed() throws Exception {
        Files.writeString(repository.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion><dependencies>
                  <dependency><groupId>external</groupId><artifactId>helper</artifactId><version>1.0</version></dependency>
                </dependencies></project>
                """);
        Path cache = repository.getParent().resolve("tamper-cache");
        Path jar = cache.resolve("external/helper/1.0/helper-1.0.jar");
        createDependencyJar(jar);
        BuildModel model = new BuildModelDetector(cache, 10, 1024 * 1024).detect(repository);
        byte[] changed = Files.readAllBytes(jar);
        changed[changed.length - 1] ^= 1;
        Files.write(jar, changed);

        SemanticModels.Index index = new JavaSemanticAdapter().index(repository,
                "dddddddddddddddddddddddddddddddddddddddd", model);

        assertEquals(SemanticModels.CoverageLevel.SEMANTIC_PARTIAL, index.coverage().level());
        assertEquals(1L, index.coverage().degradationReasons().get("dependency_jar_integrity_mismatch"));
    }

    @Test
    void indexesDeclaredCustomMavenSourceRoots() throws Exception {
        Files.writeString(repository.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion><build>
                  <sourceDirectory>custom/main</sourceDirectory>
                  <testSourceDirectory>custom/test</testSourceDirectory>
                </build></project>
                """);
        write("custom/main/custom/Library.java", """
                package custom;
                public final class Library { public String value() { return "ok"; } }
                """);
        write("custom/test/custom/LibraryTest.java", """
                package custom;
                public final class LibraryTest { public String readsValue() { return new Library().value(); } }
                """);
        BuildModel model = new BuildModelDetector().detect(repository);

        SemanticModels.Index index = new JavaSemanticAdapter().index(repository,
                "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee", model);

        assertTrue(index.symbols().stream().anyMatch(symbol -> symbol.stableKey().equals("java:type:custom.Library")));
        assertTrue(index.incoming("java:method:custom.Library#value()", CALLS).stream()
                .anyMatch(edge -> edge.fromStableKey().equals("java:method:custom.LibraryTest#readsValue()")
                        && edge.typeResolved()));
    }

    @Test
    void publishesPartialCoverageWhenADeclaredSourceRootIsUnavailable() throws Exception {
        Files.writeString(repository.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion><build>
                  <testSourceDirectory>missing/test</testSourceDirectory>
                </build></project>
                """);
        BuildModel model = new BuildModelDetector().detect(repository);

        SemanticModels.Index index = new JavaSemanticAdapter().index(repository,
                "ffffffffffffffffffffffffffffffffffffffff", model);

        assertEquals(SemanticModels.CoverageLevel.SEMANTIC_PARTIAL, index.coverage().level());
        assertEquals(1L, index.coverage().degradationReasons().get("custom_source_root_missing_or_symlink"));
    }

    @Test
    void doesNotScanTheWholeRepositoryWhenKnownBuildRootsAreInvalid() throws Exception {
        Files.writeString(repository.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion><build>
                  <sourceDirectory>missing/main</sourceDirectory>
                  <testSourceDirectory>missing/test</testSourceDirectory>
                </build></project>
                """);
        BuildModel model = new BuildModelDetector().detect(repository);

        SemanticModels.Index index = new JavaSemanticAdapter().index(repository,
                "abababababababababababababababababababab", model);

        assertEquals(0, index.coverage().indexedFiles());
        assertEquals(SemanticModels.CoverageLevel.FAILED, index.coverage().level());
        assertTrue(index.symbols().isEmpty());
    }

    @Test
    void reportsPartialCoverageForAnUnknownBuildEvenWhenConventionalSourcesExist() throws Exception {
        Files.delete(repository.resolve("pom.xml"));
        BuildModel model = new BuildModelDetector().detect(repository);

        SemanticModels.Index index = new JavaSemanticAdapter().index(repository,
                "cdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcd", model);

        assertTrue(index.coverage().indexedFiles() > 0);
        assertEquals(SemanticModels.CoverageLevel.SEMANTIC_PARTIAL, index.coverage().level());
        assertEquals(1L, index.coverage().degradationReasons().get("no_maven_or_gradle_descriptor"));
    }

    @Test
    void anonymousClassSymbolsRemainStableAcrossIndependentIndexes() throws Exception {
        write("src/main/java/example/AnonymousCaller.java", """
                package example;
                public final class AnonymousCaller {
                    public Runnable task() {
                        return new Runnable() {
                            @Override public void run() { new Receipt("anonymous"); }
                        };
                    }
                }
                """);
        JavaSemanticAdapter adapter = new JavaSemanticAdapter();
        BuildModel model = new BuildModelDetector().detect(repository);
        SemanticModels.Index first = adapter.index(repository,
                "9999999999999999999999999999999999999999", model);
        SemanticModels.Index second = adapter.index(repository,
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", model);
        Set<String> firstKeys = first.symbols().stream().map(SemanticModels.Symbol::stableKey)
                .filter(key -> key.contains("anonymous")).collect(java.util.stream.Collectors.toSet());
        Set<String> secondKeys = second.symbols().stream().map(SemanticModels.Symbol::stableKey)
                .filter(key -> key.contains("anonymous")).collect(java.util.stream.Collectors.toSet());

        assertEquals(firstKeys, secondKeys);
        assertTrue(firstKeys.stream().anyMatch(key -> key.contains("$anonymous@")));
        assertFalse(firstKeys.stream().anyMatch(key -> key.matches(".*Anonymous-[0-9a-fA-F-]{36}.*")));
    }

    private void createDependencyJar(Path jar) throws Exception {
        Path sources = repository.getParent().resolve("dependency-src");
        Path classes = repository.getParent().resolve("dependency-classes");
        Files.createDirectories(sources.resolve("external"));
        Files.createDirectories(classes);
        Path source = sources.resolve("external/Helper.java");
        Files.writeString(source, "package external; public final class Helper { public static int answer() { return 42; } }");
        int result = ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-d", classes.toString(), source.toString());
        assertEquals(0, result);
        Files.createDirectories(jar.getParent());
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("external/Helper.class"));
            Files.copy(classes.resolve("external/Helper.class"), output);
            output.closeEntry();
        }
    }

    @Test
    void resolvesExplicitSuperConstructorsAndAnnotationMemberCalls() throws Exception {
        write("src/main/java/example/Base.java", """
                package example;
                public class Base { public Base(String value) {} }
                """);
        write("src/main/java/example/Marker.java", """
                package example;
                public @interface Marker { int order(); }
                """);
        write("src/main/java/example/Specialized.java", """
                package example;
                public final class Specialized extends Base {
                    public Specialized() { super("value"); }
                    public int order(Marker marker) { return marker.order(); }
                }
                """);
        SemanticModels.Index index = new JavaSemanticAdapter().index(repository,
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", new BuildModelDetector().detect(repository));

        assertTrue(index.relationships().stream().anyMatch(edge -> edge.type() == CALLS && edge.typeResolved()
                && edge.fromStableKey().equals("java:constructor:example.Specialized#<init>()")
                && edge.toStableKey().equals("java:constructor:example.Base#<init>(java.lang.String)")));
        assertTrue(index.relationships().stream().anyMatch(edge -> edge.type() == CALLS && edge.typeResolved()
                && edge.fromStableKey().equals("java:method:example.Specialized#order(example.Marker)")
                && edge.toStableKey().equals("java:method:example.Marker#order()")), () -> index.relationships().stream()
                .filter(edge -> edge.sourcePath().endsWith("Specialized.java")).toList().toString());
    }

    @Test
    void annotationEvidenceStaysInItsOwningCompilationUnitAndMatchesIncremental() throws Exception {
        write("src/main/java/example/Marker.java", "package example; public @interface Marker {}");
        for (String name : Set.of("First", "Second")) {
            write("src/main/java/example/" + name + ".java", "package example; @Marker public class " + name
                    + " { @Marker int value; @Marker public int read() { return value; } }");
        }
        var adapter = new JavaSemanticAdapter();
        var model = new BuildModelDetector().detect(repository);
        var base = adapter.index(repository, "base", model);
        write("src/main/java/example/First.java", "package example; @Marker public class First"
                + " { @Marker int value; @Marker public int read() { return value + 1; } }");
        var full = adapter.index(repository, "head", model);
        var incremental = adapter.indexIncremental(repository, "head", model, base, Set.of("src/main/java/example/First.java"));
        var owners = full.symbols().stream().collect(java.util.stream.Collectors.toMap(SemanticModels.Symbol::stableKey, SemanticModels.Symbol::path));
        var annotations = full.relationships().stream().filter(edge -> edge.type() == ANNOTATED_WITH).toList();
        assertEquals(6, annotations.size(), "exactly the six declared annotation occurrences, not a cross-file product");
        assertTrue(annotations.stream().allMatch(edge -> edge.sourcePath().equals(owners.get(edge.fromStableKey()))));
        assertEquals(Set.copyOf(full.relationships()), Set.copyOf(incremental.relationships()));
    }

    @Test
    void anonymousSelfCallTargetsMatchDeclaredKeysAndRemainStable() throws Exception {
        write("src/main/java/example/AnonymousSelfCall.java", """
                package example;
                public class AnonymousSelfCall {
                    public Runnable task() {
                        return new Runnable() {
                            boolean ready() { return true; }
                            public void run() { if (ready()) new Receipt("ok"); }
                        };
                    }
                }
                """);
        var adapter = new JavaSemanticAdapter();
        var model = new BuildModelDetector().detect(repository);
        var first = adapter.index(repository, "same", model);
        var second = adapter.index(repository, "same", model);
        var readyKey = first.symbols().stream().map(SemanticModels.Symbol::stableKey)
                .filter(key -> key.contains("$anonymous@") && key.endsWith("#ready()")).findFirst().orElseThrow();
        assertTrue(first.relationships().stream().anyMatch(edge -> edge.type() == CALLS && edge.toStableKey().equals(readyKey) && edge.typeResolved()));
        assertFalse(first.relationships().stream().anyMatch(edge -> edge.toStableKey().matches(".*Anonymous-[0-9a-fA-F-]{36}.*")),
                () -> first.relationships().stream().filter(edge -> edge.toStableKey().contains("Anonymous-")).toList().toString());
        assertEquals(Set.copyOf(first.relationships()), Set.copyOf(second.relationships()));
    }

    @Test
    void incrementalResolutionUsesRepositoryDeclarationsNotTheReviewersClasspath() throws Exception {
        write("src/main/java/ai/codelens/intelligence/CodeIntelligenceService.java", """
                package ai.codelens.intelligence;
                public class CodeIntelligenceService { public int legacy(int value) { return value; } }
                """);
        String caller = "package example; import ai.codelens.intelligence.CodeIntelligenceService; public class ShadowClient {"
                + " private CodeIntelligenceService service; public int run(int value) { return service.legacy(value%s); } }";
        write("src/main/java/example/ShadowClient.java", caller.formatted(""));
        var adapter = new JavaSemanticAdapter();
        var model = new BuildModelDetector().detect(repository);
        var base = adapter.index(repository, "base", model);
        write("src/main/java/example/ShadowClient.java", caller.formatted(" + 1"));
        var full = adapter.index(repository, "head", model);
        var incremental = adapter.indexIncremental(repository, "head", model, base, Set.of("src/main/java/example/ShadowClient.java"));
        assertTrue(incremental.relationships().stream().anyMatch(edge -> edge.type() == CALLS && edge.typeResolved()
                && edge.toStableKey().equals("java:method:ai.codelens.intelligence.CodeIntelligenceService#legacy(int)")));
        assertEquals(Set.copyOf(full.relationships()), Set.copyOf(incremental.relationships()));
    }

    @Test
    void undeclaredReviewerLibrariesCannotBecomeTrustedRepositoryDependencies() throws Exception {
        write("src/main/java/example/HostLibraryCaller.java", """
                package example;
                import org.springframework.util.StringUtils;
                public class HostLibraryCaller { public boolean check(String value) { return StringUtils.hasText(value); } }
                """);
        var index = new JavaSemanticAdapter().index(repository, "head", new BuildModelDetector().detect(repository));
        assertFalse(index.relationships().stream().anyMatch(edge -> edge.type() == CALLS && edge.typeResolved()
                && edge.toStableKey().equals("java:method:org.springframework.util.StringUtils#hasText(java.lang.String)")));
        assertTrue(index.relationships().stream().anyMatch(edge -> edge.type() == CALLS && !edge.typeResolved()
                && edge.sourcePath().endsWith("HostLibraryCaller.java")));
    }

    @Test
    void incrementalCoverageRetainsMissingDependencyDegradation() throws Exception {
        Files.writeString(repository.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion><dependencies>
                  <dependency><groupId>external</groupId><artifactId>helper</artifactId><version>1.0</version></dependency>
                </dependencies></project>
                """);
        var adapter = new JavaSemanticAdapter();
        var model = new BuildModelDetector().detect(repository);
        var base = adapter.index(repository, "base", model);
        var head = adapter.indexIncremental(repository, "head", model, base, Set.of("src/main/java/example/OtherService.java"));
        assertTrue(head.coverage().reusedFiles() > 0);
        assertEquals(SemanticModels.CoverageLevel.SEMANTIC_PARTIAL, head.coverage().level());
        assertEquals(1L, head.coverage().degradationReasons().get("dependency_cache_not_configured"));
    }

    @Test
    void anonymousOwnersOnTheSameLineHaveDistinctSourceIdentities() throws Exception {
        write("src/main/java/example/InlineAnonymous.java", "package example; public class InlineAnonymous {"
                + " public Runnable first() { return new Runnable() { public void run() {} }; }"
                + " public Runnable second() { return new Runnable() { public void run() {} }; } }");
        var index = new JavaSemanticAdapter().index(repository, "head", new BuildModelDetector().detect(repository));
        assertEquals(2, index.symbols().stream().filter(symbol -> symbol.stableKey().contains("InlineAnonymous$anonymous@")
                && symbol.stableKey().endsWith("#run()")).count());
    }

    @Test
    void oldAdapterSnapshotsAreNotReusedAfterTheCorrectnessFix() {
        var adapter = new JavaSemanticAdapter();
        var model = new BuildModelDetector().detect(repository);
        var base = adapter.index(repository, "base", model);
        var legacy = new SemanticModels.Index(base.commitSha(), "javaparser-3.28.2-v4", base.buildModelHash(),
                base.files(), base.symbols(), base.relationships(), base.coverage());
        var head = adapter.indexIncremental(repository, "head", model, legacy, Set.of());
        assertEquals(0, head.coverage().reusedFiles());
        assertEquals(adapter.version(), head.adapterVersion());
        assertEquals(Set.copyOf(base.relationships()), Set.copyOf(head.relationships()));
    }

    @Test
    void incrementalSymbolLookupDoesNotResurrectDeletedTargets() throws Exception {
        var adapter = new JavaSemanticAdapter();
        var model = new BuildModelDetector().detect(repository);
        var base = adapter.index(repository, "base", model);
        Files.delete(repository.resolve("src/main/java/example/PaymentService.java"));
        var full = adapter.index(repository, "head", model);
        var incremental = adapter.indexIncremental(repository, "head", model, base, Set.of("src/main/java/example/PaymentService.java"));
        assertFalse(incremental.symbols().stream().anyMatch(symbol -> symbol.path().endsWith("PaymentService.java")));
        assertEquals(Set.copyOf(full.relationships()), Set.copyOf(incremental.relationships()));
    }

    @Test
    void completedIndexesReleaseTheLibrariesGlobalSolverCache() throws Exception {
        var facade = com.github.javaparser.symbolsolver.javaparsermodel.JavaParserFacade.class;
        var field = facade.getDeclaredField("instances");
        field.setAccessible(true); // Test-only observation of the pinned library's cache lifecycle.
        try {
            var adapter = new JavaSemanticAdapter();
            var model = new BuildModelDetector().detect(repository);
            adapter.index(repository, "first", model);
            assertTrue(((java.util.Map<?, ?>) field.get(null)).isEmpty(), "finished indexing must not retain its TypeSolver/AST graph");
            adapter.index(repository, "second", model);
            assertTrue(((java.util.Map<?, ?>) field.get(null)).isEmpty());
        } finally {
            com.github.javaparser.symbolsolver.javaparsermodel.JavaParserFacade.clearInstances();
        }
    }

    @Test
    void independentAdapterInstancesCanIndexWithoutSharingLiveSolverState() throws Exception {
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        var model = new BuildModelDetector().detect(repository);
        try {
            var first = executor.submit(() -> new JavaSemanticAdapter().index(repository, "first", model));
            var second = executor.submit(() -> new JavaSemanticAdapter().index(repository, "second", model));
            var a = first.get(10, java.util.concurrent.TimeUnit.SECONDS);
            var b = second.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(Set.copyOf(a.symbols()), Set.copyOf(b.symbols()));
            assertEquals(Set.copyOf(a.relationships()), Set.copyOf(b.relationships()));
        } finally { executor.shutdownNow(); }
    }

    @Test
    void interruptedSolverSessionWaitPreservesCancellation() {
        var model = new BuildModelDetector().detect(repository);
        Thread.currentThread().interrupt();
        try {
            org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                    () -> new JavaSemanticAdapter().index(repository, "head", model));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }

    private static String displayCall(SemanticModels.Relationship edge) {
        return edge.fromStableKey().replaceFirst("java:(?:method|constructor):", "") + "->"
                + edge.toStableKey().replaceFirst("java:(?:method|constructor):", "");
    }

    private void write(String path, String content) throws Exception {
        Path file = repository.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
