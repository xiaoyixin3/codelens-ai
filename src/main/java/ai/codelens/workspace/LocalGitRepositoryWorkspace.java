package ai.codelens.workspace;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class LocalGitRepositoryWorkspace implements RepositoryWorkspace {
    private static final Pattern COMMIT_SHA = Pattern.compile("[0-9a-fA-F]{40}");
    private static final int MAX_DIAGNOSTIC_BYTES = 64 * 1024;
    private static final int MAX_ARCHIVE_ENTRIES = 200_000;
    private static final long MAX_ARCHIVE_BYTES = 1024L * 1024 * 1024;
    private static final long MAX_EXTRACTED_BYTES = 2L * 1024 * 1024 * 1024;

    private final Path workspaceRoot;
    private final Duration commandTimeout;

    public LocalGitRepositoryWorkspace(Path workspaceRoot) {
        this(workspaceRoot, Duration.ofSeconds(30));
    }

    public LocalGitRepositoryWorkspace(Path workspaceRoot, Duration commandTimeout) {
        this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
        this.commandTimeout = commandTimeout;
        if (commandTimeout.isNegative() || commandTimeout.isZero()) {
            throw new IllegalArgumentException("Command timeout must be positive");
        }
    }

    @Override
    public MaterializedWorkspace materialize(Path localRepository, String baseSha, String headSha) {
        requireSha(baseSha, "baseSha");
        requireSha(headSha, "headSha");
        Path repository = localRepository.toAbsolutePath().normalize();
        if (!Files.exists(repository.resolve(".git"))) {
            throw new IllegalArgumentException("Expected a local Git repository: " + repository);
        }

        Path runRoot = workspaceRoot.resolve(UUID.randomUUID().toString()).normalize();
        requireWithinRoot(runRoot);
        try {
            Files.createDirectories(runRoot);
            verifyCommit(repository, baseSha);
            verifyCommit(repository, headSha);
            Path base = materializeCommit(repository, baseSha, runRoot.resolve("base"), runRoot.resolve("base.zip"));
            Path head = materializeCommit(repository, headSha, runRoot.resolve("head"), runRoot.resolve("head.zip"));
            Path artifacts = Files.createDirectories(runRoot.resolve("artifacts"));
            Path patches = Files.createDirectories(runRoot.resolve("patches"));
            makeReadOnly(base);
            return new Workspace(runRoot, baseSha, headSha, base, head, artifacts, patches);
        } catch (RuntimeException | IOException exception) {
            deleteRunRoot(runRoot);
            throw exception instanceof RuntimeException runtime ? runtime
                    : new IllegalStateException("Unable to create repository workspace", exception);
        }
    }

    private void verifyCommit(Path repository, String sha) {
        run(repository, List.of("cat-file", "-e", sha + "^{commit}"));
    }

    private Path materializeCommit(Path repository, String sha, Path destination, Path archive) throws IOException {
        Files.createDirectories(destination);
        run(repository, List.of("archive", "--format=zip", "--output=" + archive, sha));
        if (Files.size(archive) > MAX_ARCHIVE_BYTES) throw new IllegalStateException("Repository archive limit exceeded");
        long extracted = 0;
        int entries = 0;
        try (InputStream input = Files.newInputStream(archive); ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > MAX_ARCHIVE_ENTRIES) throw new IllegalStateException("Archive entry limit exceeded");
                Path target = destination.resolve(entry.getName()).normalize();
                if (!target.startsWith(destination)) throw new IllegalStateException("Archive contains an unsafe path");
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    long copied = copyEntry(zip, target, MAX_EXTRACTED_BYTES - extracted);
                    extracted += copied;
                }
                zip.closeEntry();
            }
        } finally {
            Files.deleteIfExists(archive);
        }
        return destination;
    }

    private static long copyEntry(ZipInputStream zip, Path target, long remainingBudget) throws IOException {
        if (remainingBudget < 0) throw new IllegalStateException("Archive extraction limit exceeded");
        long copied = 0;
        try (var output = Files.newOutputStream(target)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = zip.read(buffer)) >= 0) {
                copied += read;
                if (copied > remainingBudget) throw new IllegalStateException("Archive extraction limit exceeded");
                output.write(buffer, 0, read);
            }
        }
        return copied;
    }

    private void run(Path repository, List<String> arguments) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("-c");
        command.add("core.hooksPath=");
        command.add("-C");
        command.add(repository.toString());
        command.addAll(arguments);
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            Process running = process;
            Thread reader = new Thread(() -> copyBounded(running.getInputStream(), output), "codelens-git-output");
            reader.setDaemon(true);
            reader.start();
            if (!process.waitFor(commandTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("Git command timed out");
            }
            reader.join(Math.min(commandTimeout.toMillis(), 5_000));
            if (process.exitValue() != 0) {
                throw new IllegalStateException("Git command failed: " + output.toString(java.nio.charset.StandardCharsets.UTF_8));
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to invoke Git", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            if (process != null) process.destroyForcibly();
            throw new IllegalStateException("Interrupted while invoking Git", exception);
        }
    }

    private static void copyBounded(InputStream input, ByteArrayOutputStream output) {
        try (input) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                int remaining = MAX_DIAGNOSTIC_BYTES - output.size();
                if (remaining > 0) output.write(buffer, 0, Math.min(read, remaining));
            }
        } catch (IOException ignored) {
            // The process exit status remains authoritative; diagnostics are best effort.
        }
    }

    private static void requireSha(String sha, String label) {
        if (sha == null || !COMMIT_SHA.matcher(sha).matches()) {
            throw new IllegalArgumentException(label + " must be a full 40-character hexadecimal commit SHA");
        }
    }

    private void requireWithinRoot(Path target) {
        if (target.equals(workspaceRoot) || !target.startsWith(workspaceRoot)) {
            throw new IllegalArgumentException("Workspace target is outside the configured root");
        }
    }

    private static void makeReadOnly(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    try {
                        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path);
                        permissions.remove(PosixFilePermission.OWNER_WRITE);
                        permissions.remove(PosixFilePermission.GROUP_WRITE);
                        permissions.remove(PosixFilePermission.OTHERS_WRITE);
                        Files.setPosixFilePermissions(path, permissions);
                    } catch (UnsupportedOperationException ignored) {
                        path.toFile().setWritable(false, false);
                    }
                } catch (IOException exception) {
                    throw new WorkspaceIoException(exception);
                }
            });
        } catch (WorkspaceIoException exception) {
            throw exception.cause;
        }
    }

    private void deleteRunRoot(Path runRoot) {
        Path normalized = runRoot.toAbsolutePath().normalize();
        requireWithinRoot(normalized);
        if (!Files.exists(normalized)) return;
        try {
            Files.walkFileTree(normalized, new SimpleFileVisitor<>() {
                @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    dir.toFile().setWritable(true, false);
                    return FileVisitResult.CONTINUE;
                }

                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    file.toFile().setWritable(true, false);
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override public FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException {
                    if (error != null) throw error;
                    dir.toFile().setWritable(true, false);
                    Files.deleteIfExists(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to securely delete workspace " + normalized, exception);
        }
    }

    private final class Workspace implements MaterializedWorkspace {
        private final Path runRoot;
        private final String baseSha;
        private final String headSha;
        private final Path base;
        private final Path head;
        private final Path artifacts;
        private final Path patches;
        private boolean closed;

        private Workspace(Path runRoot, String baseSha, String headSha, Path base, Path head, Path artifacts, Path patches) {
            this.runRoot = runRoot;
            this.baseSha = baseSha;
            this.headSha = headSha;
            this.base = base;
            this.head = head;
            this.artifacts = artifacts;
            this.patches = patches;
        }

        @Override public String executionLevel() { return "S1"; }
        @Override public String baseSha() { return baseSha; }
        @Override public String headSha() { return headSha; }
        @Override public Path base() { return base; }
        @Override public Path head() { return head; }
        @Override public Path artifacts() { return artifacts; }
        @Override public Path patches() { return patches; }

        @Override public synchronized void close() {
            if (closed) return;
            deleteRunRoot(runRoot);
            closed = true;
        }
    }

    private static final class WorkspaceIoException extends RuntimeException {
        private final IOException cause;
        private WorkspaceIoException(IOException cause) { this.cause = cause; }
    }
}
