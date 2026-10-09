package ai.codelens.semantic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

import static ai.codelens.semantic.SemanticModels.RelationType.CALLS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A manually curated truth set over a fixed, Apache-2.0 Gson revision.
 *
 * <p>The external repository is intentionally not downloaded by the test. Run with
 * {@code -Dcodelens.gson.repository=/absolute/path/to/gson} after checking out the pinned commit.</p>
 */
@EnabledIfSystemProperty(named = "codelens.gson.repository", matches = ".+")
class GsonSemanticTruthSetTest {
    private static final String PINNED_COMMIT = "854c8255b625cf1e13c701a83ea9ccb4caaa576a";
    private static final String SOURCE_PATH = "gson/src/main/java/com/google/gson/JsonParser.java";

    @Test
    void resolvedCallsMatchTheFixedRevisionTruthSet() throws Exception {
        Path repository = Path.of(System.getProperty("codelens.gson.repository")).toAbsolutePath().normalize();
        assertEquals(PINNED_COMMIT, gitHead(repository), "truth-set repository must remain at the reviewed commit");

        BuildModel model = new BuildModelDetector().detect(repository);
        SemanticModels.Index index = new JavaSemanticAdapter().index(repository, PINNED_COMMIT, model);

        Set<CallFact> expected = Set.of(
                call(92, "parseString(java.lang.String)", "parseReader(java.io.Reader)"),
                call(109, "parseReader(java.io.Reader)", "com.google.gson.stream.JsonReader#<init>(java.io.Reader)"),
                call(110, "parseReader(java.io.Reader)", "parseReader(com.google.gson.stream.JsonReader)"),
                call(111, "parseReader(java.io.Reader)", "com.google.gson.JsonElement#isJsonNull()"),
                call(111, "parseReader(java.io.Reader)", "com.google.gson.stream.JsonReader#peek()"),
                call(112, "parseReader(java.io.Reader)", "com.google.gson.JsonSyntaxException#<init>(java.lang.String)"),
                call(116, "parseReader(java.io.Reader)", "com.google.gson.JsonSyntaxException#<init>(java.lang.Throwable)"),
                call(118, "parseReader(java.io.Reader)", "com.google.gson.JsonIOException#<init>(java.lang.Throwable)"),
                call(138, "parseReader(com.google.gson.stream.JsonReader)", "com.google.gson.stream.JsonReader#getStrictness()"),
                call(141, "parseReader(com.google.gson.stream.JsonReader)", "com.google.gson.stream.JsonReader#setStrictness(com.google.gson.Strictness)"),
                call(144, "parseReader(com.google.gson.stream.JsonReader)", "com.google.gson.internal.Streams#parse(com.google.gson.stream.JsonReader)"),
                call(146, "parseReader(com.google.gson.stream.JsonReader)", "com.google.gson.JsonParseException#<init>(java.lang.String,java.lang.Throwable)"),
                call(148, "parseReader(com.google.gson.stream.JsonReader)", "com.google.gson.stream.JsonReader#setStrictness(com.google.gson.Strictness)"),
                call(158, "parse(java.lang.String)", "parseString(java.lang.String)"),
                call(167, "parse(java.io.Reader)", "parseReader(java.io.Reader)"),
                call(176, "parse(com.google.gson.stream.JsonReader)", "parseReader(com.google.gson.stream.JsonReader)")
        );

        Set<CallFact> actual = index.relationships().stream()
                .filter(edge -> edge.type() == CALLS && edge.typeResolved())
                .filter(edge -> edge.sourcePath().equals(SOURCE_PATH))
                .filter(edge -> edge.toStableKey().startsWith("java:method:com.google.gson.")
                        || edge.toStableKey().startsWith("java:constructor:com.google.gson."))
                .map(edge -> new CallFact(edge.sourceLine(), edge.fromStableKey(), edge.toStableKey()))
                .collect(Collectors.toSet());

        JavacCallOracle compilerOracle = new JavacCallOracle();
        java.util.List<String> targetPrefixes = java.util.List.of(
                "java:method:com.google.gson.", "java:constructor:com.google.gson.");
        JavacCallOracle.Result oracle = compilerOracle.analyze(repository, model, Set.of(SOURCE_PATH), targetPrefixes);
        JavacCallOracle.Comparison comparison = compilerOracle.compare(oracle, index, Set.of(SOURCE_PATH), targetPrefixes);
        Set<CallFact> oracleCalls = oracle.calls().stream()
                .map(call -> new CallFact(call.line(), call.fromStableKey(), call.toStableKey()))
                .collect(Collectors.toSet());

        Set<CallFact> correct = actual.stream().filter(expected::contains).collect(Collectors.toSet());
        double precision = actual.isEmpty() ? 0.0 : (double) correct.size() / actual.size();
        double recall = (double) correct.size() / expected.size();
        System.out.printf("GSON_TRUTH_SET commit=%s expected=%d actual=%d correct=%d precision=%.4f recall=%.4f "
                        + "oracle=%d oracleCompilerErrors=%d toolAgreement=%.4f reviewQueue=%d "
                        + "indexedFiles=%d failedFiles=%d resolved=%d unresolved=%d%n",
                PINNED_COMMIT, expected.size(), actual.size(), correct.size(), precision, recall,
                oracleCalls.size(), oracle.compilerErrors(), comparison.agreementRate(), comparison.reviewQueue(10).size(),
                index.coverage().indexedFiles(), index.coverage().failedFiles(),
                index.coverage().resolvedRelationships(), index.coverage().unresolvedRelationships());

        assertTrue(precision >= 0.90, () -> "precision=" + precision + ", false positives=" + difference(actual, expected));
        assertTrue(recall >= 0.90, () -> "recall=" + recall + ", misses=" + difference(expected, actual));
        assertEquals(expected, actual, "the reviewed truth set currently expects exact agreement");
        assertEquals(expected, oracleCalls, "independent JDK compiler attribution must agree with the reviewed truth set");
        assertTrue(comparison.adapterOnly().isEmpty() && comparison.oracleOnly().isEmpty());
    }

    private static CallFact call(int line, String fromMember, String toMember) {
        String from = "java:method:com.google.gson.JsonParser#" + fromMember;
        String to = toMember.startsWith("com.google.gson.")
                ? "java:" + (toMember.contains("#<init>") ? "constructor:" : "method:") + toMember
                : "java:method:com.google.gson.JsonParser#" + toMember;
        return new CallFact(line, from, to);
    }

    private static Set<CallFact> difference(Set<CallFact> left, Set<CallFact> right) {
        return left.stream().filter(fact -> !right.contains(fact)).collect(Collectors.toSet());
    }

    private static String gitHead(Path repository) throws Exception {
        Process process = new ProcessBuilder("git", "-C", repository.toString(), "rev-parse", "HEAD")
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        assertEquals(0, process.waitFor(), () -> "unable to read truth-set revision: " + output);
        return output;
    }

    private record CallFact(int line, String fromStableKey, String toStableKey) {}
}
