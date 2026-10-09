package ai.codelens.semantic;

import java.util.Optional;

public interface SemanticSnapshotStore {
    Optional<SemanticModels.Index> load(Key key);
    void save(Key key, SemanticModels.Index index);

    record Key(String repositoryKey, String commitSha, String adapterVersion, String buildModelHash) {
        public Key {
            if (repositoryKey == null || repositoryKey.isBlank()) throw new IllegalArgumentException("repositoryKey is required");
            if (commitSha == null || !commitSha.matches("[0-9a-fA-F]{40}")) {
                throw new IllegalArgumentException("commitSha must be a full commit SHA");
            }
            if (adapterVersion == null || adapterVersion.isBlank()) throw new IllegalArgumentException("adapterVersion is required");
            if (buildModelHash == null || buildModelHash.isBlank()) throw new IllegalArgumentException("buildModelHash is required");
        }
    }
}

