package ai.codelens.semantic;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

public final class FileSemanticSnapshotStore implements SemanticSnapshotStore {
    private final Path root;
    private final ObjectMapper mapper;

    public FileSemanticSnapshotStore(Path root, ObjectMapper mapper) {
        this.root = root.toAbsolutePath().normalize();
        this.mapper = mapper.copy();
    }

    @Override
    public Optional<SemanticModels.Index> load(Key key) {
        Path target = target(key);
        if (!Files.isRegularFile(target)) return Optional.empty();
        try {
            return Optional.of(mapper.readValue(target.toFile(), SemanticModels.Index.class));
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read semantic snapshot " + target, exception);
        }
    }

    @Override
    public void save(Key key, SemanticModels.Index index) {
        if (!key.commitSha().equals(index.commitSha())
                || !key.adapterVersion().equals(index.adapterVersion())
                || !key.buildModelHash().equals(index.buildModelHash())) {
            throw new IllegalArgumentException("Snapshot key does not match index provenance");
        }
        Path target = target(key);
        try {
            Files.createDirectories(root);
            Path temporary = root.resolve(target.getFileName() + "." + UUID.randomUUID() + ".tmp").normalize();
            requireWithinRoot(temporary);
            mapper.writeValue(temporary.toFile(), index);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to persist semantic snapshot " + target, exception);
        }
    }

    private Path target(Key key) {
        String material = key.repositoryKey() + "\n" + key.commitSha() + "\n" + key.adapterVersion() + "\n" + key.buildModelHash();
        Path target = root.resolve(sha256(material) + ".json").normalize();
        requireWithinRoot(target);
        return target;
    }

    private void requireWithinRoot(Path path) {
        if (path.equals(root) || !path.startsWith(root)) throw new IllegalArgumentException("Snapshot path escaped cache root");
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}

