package ai.codelens.workspace;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Comparator;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Materializes exact GitHub commits from authenticated zipballs with no checkout or code execution. */
public final class GitHubRepositoryWorkspace {
    private static final Pattern COMMIT_SHA = Pattern.compile("[0-9a-fA-F]{40}");
    private static final Pattern WINDOWS_DRIVE = Pattern.compile("^[A-Za-z]:");
    private final RepositoryArchiveSource archives;
    private final Path workspaceRoot;
    private final long maxArchiveBytes;
    private final long maxExtractedBytes;
    private final int maxEntries;

    public GitHubRepositoryWorkspace(
            RepositoryArchiveSource archives,
            Path workspaceRoot,
            long maxArchiveBytes,
            long maxExtractedBytes,
            int maxEntries
    ) {
        this.archives = java.util.Objects.requireNonNull(archives);
        this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
        this.maxArchiveBytes = maxArchiveBytes;
        this.maxExtractedBytes = maxExtractedBytes;
        this.maxEntries = maxEntries;
        if (maxArchiveBytes <= 0 || maxExtractedBytes <= 0 || maxEntries <= 0) {
            throw new IllegalArgumentException("Workspace limits must be positive");
        }
    }

    public RepositoryWorkspace.MaterializedWorkspace materialize(
            long installationId,
            String owner,
            String repository,
            String baseSha,
            String headSha
    ) {
        requireSha(baseSha, "baseSha");
        requireSha(headSha, "headSha");
        if (installationId <= 0 || unsafeName(owner) || unsafeName(repository)) {
            throw new IllegalArgumentException("A valid installation and owner/repository are required");
        }
        Path runRoot = workspaceRoot.resolve(UUID.randomUUID().toString()).normalize();
        requireWithinRoot(runRoot);
        try {
            Files.createDirectories(runRoot);
            Path baseArchive = runRoot.resolve("base.zip");
            Path headArchive = runRoot.resolve("head.zip");
            archives.download(installationId, owner, repository, baseSha, baseArchive, maxArchiveBytes);
            archives.download(installationId, owner, repository, headSha, headArchive, maxArchiveBytes);
            Path base = extract(baseArchive, runRoot.resolve("base"));
            Path head = extract(headArchive, runRoot.resolve("head"));
            Files.deleteIfExists(baseArchive);
            Files.deleteIfExists(headArchive);
            Path artifacts = Files.createDirectories(runRoot.resolve("artifacts"));
            Path patches = Files.createDirectories(runRoot.resolve("patches"));
            makeReadOnly(base);
            return new Workspace(runRoot, baseSha, headSha, base, head, artifacts, patches);
        } catch (RuntimeException | IOException exception) {
            deleteRunRoot(runRoot);
            throw exception instanceof RuntimeException runtime ? runtime
                    : new IllegalStateException("Unable to create authenticated repository workspace", exception);
        }
    }

    private Path extract(Path archive, Path destination) throws IOException {
        if (!Files.isRegularFile(archive, LinkOption.NOFOLLOW_LINKS) || Files.size(archive) > maxArchiveBytes) {
            throw new IllegalStateException("Repository archive is missing or exceeds the compressed size limit");
        }
        Files.createDirectories(destination);
        int entries = 0;
        long extracted = 0;
        String archiveRoot = null;
        try (InputStream input = Files.newInputStream(archive); ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > maxEntries) throw new IllegalStateException("Repository archive entry limit exceeded");
                String name = entry.getName();
                if (unsafeEntry(name)) throw new IllegalStateException("Repository archive contains an unsafe path");
                int slash = name.indexOf('/');
                String root = slash < 0 ? name : name.substring(0, slash);
                if (archiveRoot == null) archiveRoot = root;
                if (!archiveRoot.equals(root)) throw new IllegalStateException("Repository archive has multiple roots");
                if (slash < 0 || slash == name.length() - 1) {
                    zip.closeEntry();
                    continue;
                }
                String relative = name.substring(slash + 1);
                Path target = destination.resolve(relative).normalize();
                if (target.equals(destination) || !target.startsWith(destination)) {
                    throw new IllegalStateException("Repository archive contains an unsafe path");
                }
                if (entry.isDirectory()) Files.createDirectories(target);
                else {
                    Files.createDirectories(target.getParent());
                    extracted += copyEntry(zip, target, maxExtractedBytes - extracted);
                }
                zip.closeEntry();
            }
        }
        if (archiveRoot == null || entries == 0) throw new IllegalStateException("Repository archive is empty");
        return destination;
    }

    private static long copyEntry(ZipInputStream zip, Path target, long remainingBudget) throws IOException {
        if (remainingBudget < 0) throw new IllegalStateException("Repository extraction limit exceeded");
        long copied = 0;
        try (var output = Files.newOutputStream(target)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = zip.read(buffer)) >= 0) {
                copied += read;
                if (copied > remainingBudget) throw new IllegalStateException("Repository extraction limit exceeded");
                output.write(buffer, 0, read);
            }
        }
        return copied;
    }

    private static boolean unsafeEntry(String name) {
        if (name == null || name.isBlank() || name.contains("\\") || name.indexOf('\0') >= 0
                || name.startsWith("/") || WINDOWS_DRIVE.matcher(name).find()) return true;
        Path normalized = Path.of(name).normalize();
        return normalized.isAbsolute() || normalized.startsWith("..") || name.contains("/../");
    }

    private static boolean unsafeName(String value) {
        return value == null || !value.matches("[A-Za-z0-9_.-]+") || value.equals(".") || value.equals("..");
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

    private final class Workspace implements RepositoryWorkspace.MaterializedWorkspace {
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
