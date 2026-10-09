package ai.codelens.semantic;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Selects compact holdout files without consulting either semantic implementation. */
public final class SemanticHoldoutScopeSelector {
    public static final String POLICY_VERSION = "java-holdout-source-only-v1";
    public static final int MIN_LINES = 60;
    public static final int MAX_LINES = 240;
    public static final int MIN_LEXICAL_CALL_SITES = 3;
    private static final long MAX_SOURCE_BYTES = 2L * 1024 * 1024;
    private static final Pattern CALL_SYNTAX = Pattern.compile(
            "(?:\\bnew\\s+[A-Za-z_$][A-Za-z0-9_$]*(?:\\s*<[^;{}()]*>)?\\s*\\(|"
                    + "\\b[A-Za-z_$][A-Za-z0-9_$]*\\s*\\.\\s*[A-Za-z_$][A-Za-z0-9_$]*\\s*\\()"
    );

    public Selection select(
            Path repositoryRoot,
            String repository,
            String commitSha,
            BuildModel buildModel,
            int maxFiles
    ) {
        Path root = repositoryRoot.toAbsolutePath().normalize();
        validate(repository, commitSha, root, buildModel, maxFiles);
        Set<String> sourceRoots = buildModel.modules().stream()
                .flatMap(module -> module.mainSourceRoots().stream())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (sourceRoots.isEmpty()) throw new IllegalArgumentException("No conventional main Java source roots found");

        List<Candidate> eligible = new ArrayList<>();
        for (String sourceRoot : sourceRoots) {
            Path directory = root.resolve(sourceRoot).normalize();
            if (!directory.startsWith(root) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) continue;
            try (Stream<Path> paths = Files.walk(directory)) {
                paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                        .filter(path -> path.getFileName().toString().endsWith(".java"))
                        .map(path -> candidate(root, path, repository, commitSha))
                        .filter(candidate -> candidate != null)
                        .forEach(eligible::add);
            } catch (IOException exception) {
                throw new IllegalStateException("Unable to enumerate holdout candidates", exception);
            }
        }
        eligible.sort(Comparator.comparing(Candidate::rank).thenComparing(Candidate::path));
        List<Candidate> selected = eligible.stream().limit(maxFiles).toList();
        if (selected.size() < maxFiles) {
            throw new IllegalArgumentException("Eligible holdout files %d < requested %d".formatted(selected.size(), maxFiles));
        }
        return new Selection(POLICY_VERSION, repository, commitSha, buildModel.hash(), maxFiles,
                MIN_LINES, MAX_LINES, MIN_LEXICAL_CALL_SITES, eligible.size(), selected);
    }

    private static Candidate candidate(Path root, Path file, String repository, String commitSha) {
        String name = file.getFileName().toString();
        String path = relative(root, file);
        if (name.equals("module-info.java") || name.equals("package-info.java")
                || name.endsWith("Test.java") || path.toLowerCase(java.util.Locale.ROOT).contains("/generated/")) {
            return null;
        }
        try {
            long bytes = Files.size(file);
            if (bytes <= 0 || bytes > MAX_SOURCE_BYTES) return null;
            String content = Files.readString(file, StandardCharsets.UTF_8);
            int lines = Math.toIntExact(content.lines().count());
            if (lines < MIN_LINES || lines > MAX_LINES) return null;
            int callSites = countCallSyntax(content);
            if (callSites < MIN_LEXICAL_CALL_SITES) return null;
            String rank = sha256(POLICY_VERSION + "\n" + repository + "\n" + commitSha + "\n" + path);
            return new Candidate(path, lines, bytes, callSites, rank);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to inspect holdout candidate " + path, exception);
        }
    }

    private static int countCallSyntax(String content) {
        int count = 0;
        Matcher matcher = CALL_SYNTAX.matcher(content);
        while (matcher.find()) count++;
        return count;
    }

    private static void validate(String repository, String commitSha, Path root, BuildModel buildModel, int maxFiles) {
        if (!Files.isDirectory(root)) throw new IllegalArgumentException("Repository root does not exist");
        if (repository == null || !repository.matches("[^/\\s]+/[^/\\s]+")) {
            throw new IllegalArgumentException("Repository must be owner/name");
        }
        if (commitSha == null || !commitSha.matches("[0-9a-fA-F]{40}")) {
            throw new IllegalArgumentException("Commit must be a full SHA");
        }
        if (buildModel == null) throw new IllegalArgumentException("Build model is required");
        if (maxFiles < 1 || maxFiles > 5) throw new IllegalArgumentException("Holdout scope must contain 1-5 files");
    }

    private static String relative(Path root, Path path) {
        return root.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record Candidate(String path, int lines, long bytes, int lexicalCallSites, String rank) {}

    public record Selection(
            String policyVersion,
            String repository,
            String commitSha,
            String buildModelHash,
            int requestedFiles,
            int minLines,
            int maxLines,
            int minLexicalCallSites,
            int eligibleFiles,
            List<Candidate> selected
    ) {
        public Selection { selected = List.copyOf(selected); }

        public Set<String> sourcePaths() {
            return selected.stream().map(Candidate::path)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }
    }
}
