package ai.codelens.semantic;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static ai.codelens.semantic.SemanticModels.RelationType.CALLS;
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
