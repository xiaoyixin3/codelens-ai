package ai.codelens.semantic;

import ai.codelens.workspace.LocalGitRepositoryWorkspace;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/** Opt-in measurement executable, not a release test or product-quality oracle. */
public final class SemanticPerformanceProbe {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private SemanticPerformanceProbe() {}

    public static void main(String[] args) throws Exception {
        if (args.length == 3 && args[0].equals("--diagnose")) {
            diagnose(Path.of(args[1]), Path.of(args[2])); return;
        }
        if (args.length != 5) throw new IllegalArgumentException("repository base-sha head-sha samples output-json");
        Path repository = Path.of(args[0]).toRealPath();
        int samples = Integer.parseInt(args[3]);
        if (samples < 3 || samples > 10) throw new IllegalArgumentException("samples must be 3..10");
        Path output = Path.of(args[4]).toAbsolutePath().normalize();
        if (output.startsWith(repository)) throw new IllegalArgumentException("Output must not be inside input repository");
        Files.createDirectories(output.getParent());
        Path workspaceRoot = Files.createTempDirectory("codelens-performance-");
        Map<String, Object> report = new LinkedHashMap<>();
        long materializeStart = System.nanoTime();
        try (var workspace = new LocalGitRepositoryWorkspace(workspaceRoot).materialize(repository, args[1], args[2])) {
            report.put("materializeMs", elapsed(materializeStart));
            var detector = new BuildModelDetector();
            var baseModel = detector.detect(workspace.base());
            var headModel = detector.detect(workspace.head());
            var adapter = new JavaSemanticAdapter();
            report.put("complete", false);
            report.put("baseSha", args[1]); report.put("headSha", args[2]);
            report.put("repository", repository.toString()); report.put("adapterVersion", adapter.version());
            report.put("heapLimitBytes", Runtime.getRuntime().maxMemory());
            List<Map<String, Object>> rows = new ArrayList<>();
            report.put("samples", rows);
            JSON.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
            long baseStart = System.nanoTime();
            var base = adapter.index(workspace.base(), args[1], baseModel);
            report.put("baseInitialMs", elapsed(baseStart));
            var preliminaryHead = adapter.index(workspace.head(), args[2], headModel);
            Map<String, String> baseHashes = base.files().stream().collect(Collectors.toMap(
                    SemanticModels.FileStatus::path, SemanticModels.FileStatus::contentHash));
            Map<String, String> headHashes = preliminaryHead.files().stream().collect(Collectors.toMap(
                    SemanticModels.FileStatus::path, SemanticModels.FileStatus::contentHash));
            Set<String> changed = new TreeSet<>();
            Set<String> allPaths = new TreeSet<>(baseHashes.keySet()); allPaths.addAll(headHashes.keySet());
            for (String p : allPaths) if (!java.util.Objects.equals(baseHashes.get(p), headHashes.get(p))) changed.add(p);
            // One warmup per strategy; preliminaryHead above is not a reported measurement.
            adapter.index(workspace.head(), args[2], headModel);
            adapter.indexIncremental(workspace.head(), args[2], headModel, base, changed);
            for (int i = 0; i < samples; i++) {
                final SemanticModels.Index frozenBase = base;
                Timed full, incremental;
                if (i % 2 == 0) {
                    full = time(() -> adapter.index(workspace.head(), args[2], headModel));
                    incremental = time(() -> adapter.indexIncremental(workspace.head(), args[2], headModel, frozenBase, changed));
                } else {
                    incremental = time(() -> adapter.indexIncremental(workspace.head(), args[2], headModel, frozenBase, changed));
                    full = time(() -> adapter.index(workspace.head(), args[2], headModel));
                }
                Set<String> expected = facts(full.index()), actual = facts(incremental.index());
                Set<String> missing = new TreeSet<>(expected); missing.removeAll(actual);
                Set<String> extra = new TreeSet<>(actual); extra.removeAll(expected);
                rows.add(Map.of("sample", i + 1, "order", i % 2 == 0 ? "full-then-incremental" : "incremental-then-full",
                        "fullMs", full.ms(), "incrementalMs", incremental.ms(), "equal", expected.equals(actual),
                        "missingCount", missing.size(), "extraCount", extra.size(),
                        "fullDigest", digest(expected), "incrementalDigest", digest(actual)));
                report.put("fullCoverage", full.index().coverage());
                report.put("incrementalCoverage", incremental.index().coverage());
                report.put("symbols", full.index().symbols().size());
                report.put("relationships", full.index().relationships().size());
                // Checkpoint outside both timed operations; incomplete runs remain explicitly incomplete.
                JSON.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
                System.out.printf("sample=%d full=%.3fms incremental=%.3fms equal=%s%n", i + 1, full.ms(), incremental.ms(), expected.equals(actual));
            }
            Path cache = Files.createDirectory(output.getParent().resolve("cache-" + output.getFileName()));
            var service = new SemanticIndexService(adapter, new FileSemanticSnapshotStore(cache, JSON), detector);
            long coldStart = System.nanoTime();
            var cached = service.base(repository.toString(), workspace.head(), args[2]);
            report.put("fileStoreFirstComputeAndSaveMs", elapsed(coldStart));
            List<Double> cachedTimes = new ArrayList<>();
            for (int i = 0; i < samples; i++) {
                long start = System.nanoTime();
                var result = service.base(repository.toString(), workspace.head(), args[2]);
                cachedTimes.add(elapsed(start));
                if (!facts(cached).equals(facts(result))) throw new IllegalStateException("Cache changed semantic output");
            }
            report.put("fileStoreHitMs", cachedTimes);
            report.put("baseSha", args[1]); report.put("headSha", args[2]);
            report.put("changedJavaPaths", changed);
            report.put("buildModelEqual", baseModel.hash().equals(headModel.hash()));
            report.put("adapterVersion", adapter.version());
            report.put("repository", repository.toString());
            report.put("samples", rows);
            report.put("baseCoverage", base.coverage());
            report.put("heapLimitBytes", Runtime.getRuntime().maxMemory());
            report.put("limitations", List.of("S1; no repository build/test execution", "5 in-JVM warmed samples; OS page cache not flushed",
                    "semantic facts compared as sets, not independent correctness labels", "file cache is not production JDBC cache"));
            report.put("complete", true);
            JSON.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
        } finally {
            // Materializer removes only its random child; this directory was created above.
            Files.deleteIfExists(workspaceRoot);
        }
    }

    private record Timed(SemanticModels.Index index, double ms) {}
    private static Timed time(Supplier<SemanticModels.Index> work) {
        long start = System.nanoTime(); var result = work.get(); return new Timed(result, elapsed(start));
    }
    private static double elapsed(long start) { return (System.nanoTime() - start) / 1_000_000.0; }
    private static Set<String> facts(SemanticModels.Index index) {
        Set<String> facts = new TreeSet<>();
        index.files().forEach(file -> facts.add("file:" + file.path() + ":" + file.status() + ":" + file.contentHash()));
        index.symbols().forEach(symbol -> facts.add("symbol:" + symbol));
        index.relationships().forEach(edge -> facts.add("edge:" + edge));
        return facts;
    }
    private static String digest(Set<String> facts) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(String.join("\n", facts).getBytes(StandardCharsets.UTF_8)));
    }

    /** Separate untimed diagnosis after the frozen measurement run; never overwrites samples. */
    private static void diagnose(Path previous, Path output) throws Exception {
        var prior = JSON.readTree(previous.toFile());
        Path repository = Path.of(prior.path("repository").asText()).toRealPath();
        Path normalizedOutput = output.toAbsolutePath().normalize();
        if (normalizedOutput.startsWith(repository) || Files.exists(normalizedOutput)) throw new IllegalArgumentException("Use a fresh output outside repository");
        Path cacheRoot = previous.toAbsolutePath().getParent().resolve("cache-" + previous.getFileName());
        Path snapshot;
        try (var files = Files.list(cacheRoot)) { snapshot = files.filter(p -> p.toString().endsWith(".json")).findFirst().orElseThrow(); }
        var full = JSON.readValue(snapshot.toFile(), SemanticModels.Index.class);
        Path temporary = Files.createTempDirectory("codelens-perf-diagnosis-");
        try (var workspace = new LocalGitRepositoryWorkspace(temporary).materialize(repository, prior.path("baseSha").asText(), prior.path("headSha").asText())) {
            var detector = new BuildModelDetector(); var adapter = new JavaSemanticAdapter();
            var base = adapter.index(workspace.base(), prior.path("baseSha").asText(), detector.detect(workspace.base()));
            Set<String> changed = new TreeSet<>(); prior.path("changedJavaPaths").forEach(value -> changed.add(value.asText()));
            var incremental = adapter.indexIncremental(workspace.head(), prior.path("headSha").asText(), detector.detect(workspace.head()), base, changed);
            Set<String> expected = facts(full), actual = facts(incremental);
            Set<String> missing = new TreeSet<>(expected); missing.removeAll(actual);
            Set<String> extra = new TreeSet<>(actual); extra.removeAll(expected);
            Set<String> normalizedExpected = normalizeAnonymous(expected), normalizedActual = normalizeAnonymous(actual);
            Set<String> normalizedMissing = new TreeSet<>(normalizedExpected); normalizedMissing.removeAll(normalizedActual);
            Set<String> normalizedExtra = new TreeSet<>(normalizedActual); normalizedExtra.removeAll(normalizedExpected);
            Set<SemanticModels.Relationship> missingEdges = new java.util.HashSet<>(full.relationships()); missingEdges.removeAll(incremental.relationships());
            Set<SemanticModels.Relationship> extraEdges = new java.util.HashSet<>(incremental.relationships()); extraEdges.removeAll(full.relationships());
            Set<String> fullKeys = full.symbols().stream().map(SemanticModels.Symbol::stableKey).collect(Collectors.toSet());
            Set<String> incrementalKeys = incremental.symbols().stream().map(SemanticModels.Symbol::stableKey).collect(Collectors.toSet());
            var missingInternal = missingEdges.stream().filter(e -> e.typeResolved() && fullKeys.contains(e.fromStableKey()) && fullKeys.contains(e.toStableKey())).toList();
            var extraInternal = extraEdges.stream().filter(e -> e.typeResolved() && incrementalKeys.contains(e.fromStableKey()) && incrementalKeys.contains(e.toStableKey())).toList();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("untimedDiagnosis", true); result.put("priorReport", previous.toAbsolutePath().toString());
            result.put("missingFacts", missing.size()); result.put("extraFacts", extra.size());
            result.put("missingByKind", missing.stream().collect(Collectors.groupingBy(f -> f.substring(0, f.indexOf(':')), java.util.TreeMap::new, Collectors.counting())));
            result.put("extraByKind", extra.stream().collect(Collectors.groupingBy(f -> f.substring(0, f.indexOf(':')), java.util.TreeMap::new, Collectors.counting())));
            result.put("missingEdgesByType", missingEdges.stream().collect(Collectors.groupingBy(e -> e.type().name(), java.util.TreeMap::new, Collectors.counting())));
            result.put("extraEdgesByType", extraEdges.stream().collect(Collectors.groupingBy(e -> e.type().name(), java.util.TreeMap::new, Collectors.counting())));
            result.put("missingInternalResolvedEdges", missingInternal.size()); result.put("extraInternalResolvedEdges", extraInternal.size());
            result.put("missingInternalCalls", missingInternal.stream().filter(e -> e.type() == SemanticModels.RelationType.CALLS).count());
            result.put("extraInternalCalls", extraInternal.stream().filter(e -> e.type() == SemanticModels.RelationType.CALLS).count());
            result.put("missingInternalExamples", missingInternal.stream().sorted(java.util.Comparator.comparing(Object::toString)).limit(8).toList());
            result.put("extraInternalExamples", extraInternal.stream().sorted(java.util.Comparator.comparing(Object::toString)).limit(8).toList());
            result.put("missingExamples", missing.stream().limit(12).toList()); result.put("extraExamples", extra.stream().limit(12).toList());
            result.put("anonymousUuidNormalizedMissing", normalizedMissing.size()); result.put("anonymousUuidNormalizedExtra", normalizedExtra.size());
            result.put("fullCoverage", full.coverage()); result.put("incrementalCoverage", incremental.coverage());
            result.put("scope", "Diagnostic comparison to archived full snapshot; not an additional timing sample or independent precision label");
            JSON.writerWithDefaultPrettyPrinter().writeValue(normalizedOutput.toFile(), result);
            System.out.printf("diagnosis missing=%d extra=%d normalizedMissing=%d normalizedExtra=%d%n", missing.size(), extra.size(), normalizedMissing.size(), normalizedExtra.size());
        } finally { Files.deleteIfExists(temporary); }
    }

    private static Set<String> normalizeAnonymous(Set<String> input) {
        return input.stream().map(f -> f.replaceAll("Anonymous-[0-9a-fA-F-]{36}", "Anonymous-<unstable-id>"))
                .collect(Collectors.toCollection(TreeSet::new));
    }
}
