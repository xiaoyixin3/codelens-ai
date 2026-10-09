package ai.codelens.semantic;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/** Creates content-addressed local review packets while keeping gold predictions hidden. */
public final class SemanticReviewPacketBuilder {
    private static final int EXCERPT_RADIUS = 2;
    private static final int MAX_LINE_CHARS = 500;
    private static final long MAX_SOURCE_BYTES = 2L * 1024 * 1024;
    private static final int MAX_CONTEXT_FILES = 25_000;
    private static final long MAX_CONTEXT_BYTES = 512L * 1024 * 1024;
    static final Set<String> CONTEXT_EXCLUDED_DIRECTORIES = Set.of(
            ".git", ".gradle", ".idea", "target", "build", "node_modules", "dist", "out"
    );

    public SilverPacket silver(
            Path repositoryRoot,
            String repository,
            String commitSha,
            JavacCallOracle.Comparison comparison,
            int sampleEveryAgreement
    ) {
        Path root = validateRoot(repositoryRoot);
        validateIdentity(repository, commitSha);
        List<SemanticTruthSetEvaluator.CallFact> queue = comparison.reviewQueue(sampleEveryAgreement);
        Set<SemanticTruthSetEvaluator.CallFact> adapterFacts = new LinkedHashSet<>(comparison.agreements());
        adapterFacts.addAll(comparison.adapterOnly());
        Set<SemanticTruthSetEvaluator.CallFact> oracleFacts = new LinkedHashSet<>(comparison.agreements());
        oracleFacts.addAll(comparison.oracleOnly());

        Map<Site, List<SemanticTruthSetEvaluator.CallFact>> sites = new LinkedHashMap<>();
        queue.stream().sorted(factOrder()).forEach(fact -> sites
                .computeIfAbsent(new Site(fact.sourcePath(), fact.line()), ignored -> new ArrayList<>()).add(fact));
        List<SilverItem> items = new ArrayList<>();
        for (Site site : sites.keySet()) {
            Set<String> adapterCallers = callers(adapterFacts, site);
            Set<String> oracleCallers = callers(oracleFacts, site);
            Set<String> adapterTargets = targets(adapterFacts, site);
            Set<String> oracleTargets = targets(oracleFacts, site);
            boolean agreement = adapterCallers.equals(oracleCallers) && adapterTargets.equals(oracleTargets);
            Excerpt excerpt = excerpt(root, site.sourcePath(), site.line());
            String material = site.sourcePath() + "\n" + site.line() + "\n" + adapterCallers + "\n" + oracleCallers
                    + "\n" + adapterTargets + "\n" + oracleTargets + "\n" + excerpt.digest();
            items.add(new SilverItem(sha256(material).substring(0, 20),
                    agreement ? "agreement_sample" : "tool_disagreement", site.sourcePath(), site.line(), excerpt,
                    adapterCallers, oracleCallers, adapterTargets, oracleTargets,
                    agreement
                            ? "Confirm the independently agreed caller and target using the frozen repository context."
                            : "Resolve the compiler/adapter disagreement using source, declarations, build files, and tests."));
        }
        String contextDigest = contextDigest(root, items.stream().map(SilverItem::sourcePath).collect(java.util.stream.Collectors.toSet()));
        String packetId = sha256(repository + "\n" + commitSha + "\n" + contextDigest + "\n" + items);
        return new SilverPacket(packetId, repository, commitSha, Instant.now(), contextDigest,
                "silver/compiler-oracle", true, comparison.agreementRate(), comparison.compilerErrors(), items);
    }

    public GoldPacket gold(
            Path repositoryRoot,
            String repository,
            String commitSha,
            Set<String> sourcePaths,
            List<String> targetPrefixes
    ) {
        Path root = validateRoot(repositoryRoot);
        validateIdentity(repository, commitSha);
        if (sourcePaths == null || sourcePaths.isEmpty()) throw new IllegalArgumentException("Gold scope must contain source paths");
        if (targetPrefixes == null || targetPrefixes.isEmpty() || targetPrefixes.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("Gold scope must contain target prefixes");
        }
        List<ScopeFile> files = sourcePaths.stream().sorted().map(path -> scopeFile(root, path)).toList();
        ContextSnapshot context = fullContext(root);
        List<String> prefixes = targetPrefixes.stream().distinct().sorted().toList();
        String packetId = sha256(repository + "\n" + commitSha + "\n" + context.digest() + "\n" + files + "\n" + prefixes + "\ngold");
        return new GoldPacket(packetId, repository, commitSha, Instant.now(), context.digest(),
                "gold/independent-holdout", false, context.fileCount(), context.totalBytes(),
                CONTEXT_EXCLUDED_DIRECTORIES.stream().sorted().toList(), files, prefixes,
                "Use the full frozen repository context. Identify every in-scope direct call without viewing CodeLens or compiler-oracle predictions.");
    }

    private static ContextSnapshot fullContext(Path root) {
        List<Path> files = contextFiles(root);
        if (files.size() > MAX_CONTEXT_FILES) throw new IllegalArgumentException("Repository context exceeds file limit");
        long totalBytes = 0;
        StringBuilder material = new StringBuilder();
        for (Path file : files) {
            try {
                long size = Files.size(file);
                totalBytes = Math.addExact(totalBytes, size);
                if (totalBytes > MAX_CONTEXT_BYTES) throw new IllegalArgumentException("Repository context exceeds byte limit");
                material.append(relative(root, file)).append('\n')
                        .append(sha256(Files.readAllBytes(file))).append('\n').append(size).append('\n');
            } catch (IOException exception) {
                throw new IllegalStateException("Unable to hash repository context", exception);
            }
        }
        if (files.isEmpty()) throw new IllegalArgumentException("Repository context contains no reviewable files");
        return new ContextSnapshot(sha256(material.toString()), files.size(), totalBytes);
    }

    static List<Path> contextFiles(Path root) {
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> !contextExcluded(root, path))
                    .sorted(Comparator.comparing(path -> relative(root, path)))
                    .toList();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to enumerate repository context", exception);
        }
    }

    private static boolean contextExcluded(Path root, Path path) {
        for (Path part : root.relativize(path)) {
            if (CONTEXT_EXCLUDED_DIRECTORIES.contains(part.toString())) return true;
        }
        return false;
    }

    static String relative(Path root, Path path) {
        return root.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    private static ScopeFile scopeFile(Path root, String relative) {
        Path file = safeSource(root, relative);
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            return new ScopeFile(relative, sha256(content), content.lines().count());
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read gold scope file " + relative, exception);
        }
    }

    private static Excerpt excerpt(Path root, String relative, int line) {
        Path file = safeSource(root, relative);
        try {
            List<String> all = Files.readAllLines(file, StandardCharsets.UTF_8);
            if (line < 1 || line > all.size()) throw new IllegalArgumentException("Review item line is outside source file: " + relative);
            int start = Math.max(1, line - EXCERPT_RADIUS);
            int end = Math.min(all.size(), line + EXCERPT_RADIUS);
            List<String> selected = new ArrayList<>();
            for (int current = start; current <= end; current++) {
                String value = all.get(current - 1);
                if (value.length() > MAX_LINE_CHARS) value = value.substring(0, MAX_LINE_CHARS) + "…";
                selected.add("%d: %s".formatted(current, value));
            }
            String text = String.join("\n", selected);
            return new Excerpt(start, end, selected, sha256(text));
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read review source " + relative, exception);
        }
    }

    private static Set<String> callers(Set<SemanticTruthSetEvaluator.CallFact> facts, Site site) {
        return facts.stream().filter(fact -> site.matches(fact)).map(SemanticTruthSetEvaluator.CallFact::fromStableKey)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private static Set<String> targets(Set<SemanticTruthSetEvaluator.CallFact> facts, Site site) {
        return facts.stream().filter(fact -> site.matches(fact)).map(SemanticTruthSetEvaluator.CallFact::toStableKey)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private static Comparator<SemanticTruthSetEvaluator.CallFact> factOrder() {
        return Comparator.comparing(SemanticTruthSetEvaluator.CallFact::sourcePath)
                .thenComparingInt(SemanticTruthSetEvaluator.CallFact::line)
                .thenComparing(SemanticTruthSetEvaluator.CallFact::fromStableKey)
                .thenComparing(SemanticTruthSetEvaluator.CallFact::toStableKey);
    }

    private static String contextDigest(Path root, Set<String> sourcePaths) {
        StringBuilder material = new StringBuilder();
        sourcePaths.stream().sorted().forEach(path -> {
            Path source = safeSource(root, path);
            try {
                material.append(path).append('\n').append(sha256(Files.readString(source, StandardCharsets.UTF_8))).append('\n');
            } catch (IOException exception) {
                throw new IllegalStateException("Unable to hash review context " + path, exception);
            }
        });
        return sha256(material.toString());
    }

    private static Path safeSource(Path root, String relative) {
        if (relative == null || relative.isBlank() || relative.contains("\\")) {
            throw new IllegalArgumentException("Unsafe review source path");
        }
        Path candidate = root.resolve(relative).normalize();
        if (!candidate.startsWith(root) || !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Review source is outside the repository or is not a regular file");
        }
        try {
            if (Files.size(candidate) > MAX_SOURCE_BYTES) throw new IllegalArgumentException("Review source exceeds size limit");
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to inspect review source", exception);
        }
        return candidate;
    }

    private static Path validateRoot(Path root) {
        Path normalized = root.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalized)) throw new IllegalArgumentException("Repository root does not exist");
        return normalized;
    }

    private static void validateIdentity(String repository, String commitSha) {
        if (repository == null || !repository.matches("[^/\\s]+/[^/\\s]+")) {
            throw new IllegalArgumentException("Repository must be owner/name");
        }
        if (commitSha == null || !commitSha.matches("[0-9a-fA-F]{40}")) {
            throw new IllegalArgumentException("Commit must be a full SHA");
        }
    }

    private static String sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record Site(String sourcePath, int line) {
        private boolean matches(SemanticTruthSetEvaluator.CallFact fact) {
            return sourcePath.equals(fact.sourcePath()) && line == fact.line();
        }
    }

    private record ContextSnapshot(String digest, int fileCount, long totalBytes) {}

    public record Excerpt(int startLine, int endLine, List<String> lines, String digest) {
        public Excerpt { lines = List.copyOf(lines); }
    }

    public record SilverItem(
            String id,
            String kind,
            String sourcePath,
            int line,
            Excerpt excerpt,
            Set<String> adapterCallers,
            Set<String> oracleCallers,
            Set<String> adapterTargets,
            Set<String> oracleTargets,
            String question
    ) {
        public SilverItem {
            adapterCallers = Set.copyOf(adapterCallers);
            oracleCallers = Set.copyOf(oracleCallers);
            adapterTargets = Set.copyOf(adapterTargets);
            oracleTargets = Set.copyOf(oracleTargets);
        }
    }

    public record SilverPacket(
            String packetId,
            String repository,
            String commitSha,
            Instant createdAt,
            String contextDigest,
            String evidenceTier,
            boolean predictionsVisible,
            double toolAgreement,
            int compilerErrors,
            List<SilverItem> items
    ) {
        public SilverPacket { items = List.copyOf(items); }
    }

    public record ScopeFile(String path, String contentDigest, long lines) {}

    public record GoldPacket(
            String packetId,
            String repository,
            String commitSha,
            Instant createdAt,
            String contextDigest,
            String evidenceTier,
            boolean predictionsVisible,
            int contextFileCount,
            long contextBytes,
            List<String> contextExclusions,
            List<ScopeFile> scopeFiles,
            List<String> targetPrefixes,
            String instructions
    ) {
        public GoldPacket {
            contextExclusions = List.copyOf(contextExclusions);
            scopeFiles = List.copyOf(scopeFiles);
            targetPrefixes = List.copyOf(targetPrefixes);
        }
    }
}
