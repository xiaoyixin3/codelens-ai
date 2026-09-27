package ai.codelens.semantic;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Exports a sealed, prediction-free gold review packet from a clean fixed Git revision. */
public final class SemanticGoldPacketCli {
    private static final Duration GIT_TIMEOUT = Duration.ofSeconds(15);

    private SemanticGoldPacketCli() {}

    public static void main(String[] args) throws Exception {
        Path packet = run(args);
        System.out.println("GOLD_PACKET " + packet);
    }

    static Path run(String[] args) throws Exception {
        Arguments parsed = Arguments.parse(args);
        Path repositoryRoot = parsed.path("--repository-root").toAbsolutePath().normalize();
        Path outputRoot = parsed.path("--output-root").toAbsolutePath().normalize();
        if (outputRoot.startsWith(repositoryRoot)) {
            throw new IllegalArgumentException("Output root must be outside the repository context");
        }
        String repository = parsed.single("--repository");
        String commitSha = parsed.single("--commit");
        List<String> targetPrefixes = parsed.many("--target-prefix");
        int files = Integer.parseInt(parsed.optional("--files", "1"));
        verifyGitRevision(repositoryRoot, commitSha);

        BuildModel model = new BuildModelDetector().detect(repositoryRoot);
        SemanticHoldoutScopeSelector.Selection selection = new SemanticHoldoutScopeSelector().select(
                repositoryRoot, repository, commitSha, model, files);
        SemanticReviewPacketBuilder.GoldPacket packet = new SemanticReviewPacketBuilder().gold(
                repositoryRoot, repository, commitSha, selection.sourcePaths(), targetPrefixes);
        Path destination = outputRoot.resolve(packet.packetId()).normalize();
        if (!destination.startsWith(outputRoot)) throw new IllegalArgumentException("Unsafe packet destination");
        if (Files.exists(destination)) throw new IllegalStateException("Sealed packet already exists: " + destination);
        Files.createDirectories(destination);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        try {
            writeAtomic(destination.resolve("scope-selection.json"), mapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsBytes(selection));
            byte[] packetJson = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(packet);
            String serialized = new String(packetJson, StandardCharsets.UTF_8);
            if (serialized.contains("adapterTargets") || serialized.contains("oracleTargets")
                    || serialized.contains("toolAgreement") || serialized.contains("reviewQueue")) {
                throw new IllegalStateException("Prediction-bearing fields leaked into the gold packet");
            }
            writeAtomic(destination.resolve("gold-packet.json"), packetJson);
            Path archive = destination.resolve("repository-context.zip");
            String archivedContextDigest = writeContextArchive(repositoryRoot, archive);
            if (!packet.contextDigest().equals(archivedContextDigest)) {
                throw new IllegalStateException("Repository context changed while the gold packet was being sealed");
            }
            writeAtomic(destination.resolve("CONTEXT-ARCHIVE.sha256"),
                    (sha256(archive) + "  repository-context.zip\n").getBytes(StandardCharsets.UTF_8));
            writeAtomic(destination.resolve("reviewer-submission-template.json"),
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(submissionTemplate(packet)));
            writeAtomic(destination.resolve("REVIEWER-INSTRUCTIONS.md"), instructions(packet, selection)
                    .getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            try (var paths = Files.walk(destination)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (IOException ignored) { }
                });
            }
            throw exception;
        }
        return destination;
    }

    private static String instructions(
            SemanticReviewPacketBuilder.GoldPacket packet,
            SemanticHoldoutScopeSelector.Selection selection
    ) {
        StringBuilder text = new StringBuilder()
                .append("# Independent Java call review packet\n\n")
                .append("- Packet: `").append(packet.packetId()).append("`\n")
                .append("- Repository: `").append(packet.repository()).append("`\n")
                .append("- Commit: `").append(packet.commitSha()).append("`\n")
                .append("- Context digest: `").append(packet.contextDigest()).append("`\n")
                .append("- Selection policy: `").append(selection.policyVersion()).append("`\n")
                .append("- Predictions visible: `false`\n\n")
                .append("The complete frozen review context is in `repository-context.zip`; verify it with `CONTEXT-ARCHIVE.sha256`.\n\n")
                .append("Copy `reviewer-submission-template.json` before filling it; do not edit another reviewer's submission.\n\n")
                .append("## Scope\n\nSelected source files:\n\n");
        packet.scopeFiles().forEach(file -> text.append("- `").append(file.path()).append("`\n"));
        text.append("\nOnly direct calls whose target stable key begins with one of these prefixes are in scope:\n\n");
        packet.targetPrefixes().forEach(prefix -> text.append("- `").append(prefix).append("`\n"));
        return text.append("\n## Review procedure\n\n")
                .append("Use the complete repository at the recorded commit, including declarations, build descriptors, tests, and documentation. ")
                .append("Record every in-scope direct call as source path, one-based call line, caller stable key, and target stable key. ")
                .append("Do not request or view CodeLens, JavaParser, or compiler-oracle predictions before submitting the independent label set. ")
                .append("If evidence is insufficient, record the uncertainty separately instead of guessing.\n")
                .toString();
    }

    private static Map<String, Object> submissionTemplate(SemanticReviewPacketBuilder.GoldPacket packet) {
        Map<String, Object> qualification = new LinkedHashMap<>();
        qualification.put("primaryLanguages", List.of("java"));
        qualification.put("yearsExperience", 0);
        qualification.put("repositoryFamiliarity", "CALIBRATED_EXTERNAL");
        qualification.put("calibrationSetId", "");
        qualification.put("calibrationScore", 0.0);
        qualification.put("calibrationCompletedAt", "");
        Map<String, Object> template = new LinkedHashMap<>();
        template.put("packetId", packet.packetId());
        template.put("reviewerId", "");
        template.put("submittedAt", "");
        template.put("contextPacketId", packet.packetId());
        template.put("independent", true);
        template.put("predictionVisible", false);
        template.put("qualification", qualification);
        template.put("calls", List.of());
        template.put("uncertainties", List.of());
        return template;
    }

    private static void verifyGitRevision(Path repositoryRoot, String expectedSha) throws Exception {
        String actual = git(repositoryRoot, "rev-parse", "HEAD").trim();
        if (!expectedSha.matches("[0-9a-fA-F]{40}") || !actual.equalsIgnoreCase(expectedSha)) {
            throw new IllegalArgumentException("Repository HEAD does not match the requested full commit SHA");
        }
        if (!git(repositoryRoot, "status", "--porcelain").isBlank()) {
            throw new IllegalArgumentException("Repository must be clean before sealing a gold packet");
        }
    }

    private static String git(Path repositoryRoot, String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "-C", repositoryRoot.toString()));
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        boolean finished = process.waitFor(GIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IllegalStateException("Git revision verification timed out");
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.exitValue() != 0) throw new IllegalStateException("Git revision verification failed: " + output.trim());
        return output;
    }

    private static void writeAtomic(Path destination, byte[] bytes) throws IOException {
        Path temporary = destination.resolveSibling(destination.getFileName() + ".tmp");
        Files.write(temporary, bytes);
        try {
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporary, destination);
        }
    }

    private static String writeContextArchive(Path repositoryRoot, Path destination) throws IOException {
        Path temporary = destination.resolveSibling(destination.getFileName() + ".tmp");
        StringBuilder contextMaterial = new StringBuilder();
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(temporary), StandardCharsets.UTF_8)) {
            for (Path file : SemanticReviewPacketBuilder.contextFiles(repositoryRoot)) {
                String relative = SemanticReviewPacketBuilder.relative(repositoryRoot, file);
                ZipEntry entry = new ZipEntry(relative);
                entry.setTime(0L);
                zip.putNextEntry(entry);
                MessageDigest fileDigest = digest();
                long bytes;
                try (DigestInputStream input = new DigestInputStream(Files.newInputStream(file), fileDigest)) {
                    bytes = input.transferTo(zip);
                }
                zip.closeEntry();
                contextMaterial.append(relative).append('\n')
                        .append(HexFormat.of().formatHex(fileDigest.digest())).append('\n')
                        .append(bytes).append('\n');
            }
        }
        try {
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporary, destination);
        }
        return HexFormat.of().formatHex(digest().digest(contextMaterial.toString().getBytes(StandardCharsets.UTF_8)));
    }

    private static String sha256(Path file) throws IOException {
        MessageDigest digest = digest();
        try (DigestInputStream input = new DigestInputStream(Files.newInputStream(file), digest)) {
                input.transferTo(java.io.OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record Arguments(Map<String, List<String>> values) {
        private static final List<String> ALLOWED = List.of(
                "--repository-root", "--repository", "--commit", "--target-prefix", "--output-root", "--files"
        );

        private static Arguments parse(String[] args) {
            Map<String, List<String>> values = new LinkedHashMap<>();
            for (int index = 0; index < args.length; index += 2) {
                if (index + 1 >= args.length || !ALLOWED.contains(args[index])) {
                    throw usage();
                }
                values.computeIfAbsent(args[index], ignored -> new ArrayList<>()).add(args[index + 1]);
            }
            Arguments parsed = new Arguments(values);
            parsed.single("--repository-root");
            parsed.single("--repository");
            parsed.single("--commit");
            parsed.single("--output-root");
            if (parsed.many("--target-prefix").isEmpty()) throw usage();
            return parsed;
        }

        private String single(String name) {
            List<String> found = values.getOrDefault(name, List.of());
            if (found.size() != 1 || found.get(0).isBlank()) throw usage();
            return found.get(0);
        }

        private String optional(String name, String fallback) {
            return values.containsKey(name) ? single(name) : fallback;
        }

        private List<String> many(String name) {
            return List.copyOf(values.getOrDefault(name, List.of()));
        }

        private Path path(String name) {
            return Path.of(single(name));
        }

        private static IllegalArgumentException usage() {
            return new IllegalArgumentException("Usage: --repository-root PATH --repository owner/name --commit FULL_SHA "
                    + "--target-prefix PREFIX [--target-prefix PREFIX] --output-root PATH [--files 1-5]");
        }
    }
}
