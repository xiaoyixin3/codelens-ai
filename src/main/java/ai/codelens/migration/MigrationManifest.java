package ai.codelens.migration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public record MigrationManifest(List<Entry> entries) {
    private static final Pattern NAME = Pattern.compile("([0-9]{3})_[a-z0-9_]+\\.sql");
    private static final long MAX_MIGRATION_BYTES = 10L * 1024 * 1024;

    public MigrationManifest {
        entries = List.copyOf(entries);
        if (entries.isEmpty()) throw new IllegalArgumentException("migration manifest must not be empty");
    }

    public static MigrationManifest load(Path directory) {
        Path root = directory.toAbsolutePath().normalize();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root)) {
            throw new IllegalArgumentException("migration directory is unavailable or unsafe: " + root);
        }
        try (Stream<Path> files = Files.list(root)) {
            List<Path> paths = files.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> path.getFileName().toString().endsWith(".sql"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
            java.util.ArrayList<Entry> entries = new java.util.ArrayList<>();
            int expectedSequence = 1;
            for (Path path : paths) {
                String name = path.getFileName().toString();
                Matcher matcher = NAME.matcher(name);
                if (!matcher.matches() || Integer.parseInt(matcher.group(1)) != expectedSequence++) {
                    throw new IllegalArgumentException("migrations must be contiguous and named NNN_description.sql: " + name);
                }
                long size = Files.size(path);
                if (size < 1 || size > MAX_MIGRATION_BYTES || Files.isSymbolicLink(path)) {
                    throw new IllegalArgumentException("migration file is empty, oversized, or unsafe: " + name);
                }
                byte[] bytes = Files.readAllBytes(path);
                entries.add(new Entry(name, new String(bytes, StandardCharsets.UTF_8), sha256(bytes)));
            }
            return new MigrationManifest(entries);
        } catch (IOException exception) {
            throw new IllegalStateException("unable to load migration manifest", exception);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    public record Entry(String name, String sql, String checksum) {}
}
