package ai.codelens.semantic;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FileSemanticSnapshotStoreTest {
    @TempDir Path root;

    @Test
    void storesByRepositoryShaAdapterAndBuildHashAndRejectsMismatchedProvenance() {
        FileSemanticSnapshotStore store = new FileSemanticSnapshotStore(root, new ObjectMapper());
        SemanticSnapshotStore.Key key = new SemanticSnapshotStore.Key(
                "owner/repository", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "adapter-v1", "build-v1"
        );
        SemanticModels.Index index = new SemanticModels.Index(
                key.commitSha(), key.adapterVersion(), key.buildModelHash(), List.of(), List.of(), List.of(),
                new SemanticModels.Coverage(SemanticModels.CoverageLevel.SEMANTIC, 0, 0, 0, 0, 0, 0, 0, Map.of())
        );

        store.save(key, index);
        assertEquals(index, store.load(key).orElseThrow());

        SemanticSnapshotStore.Key wrong = new SemanticSnapshotStore.Key(
                "owner/repository", "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "adapter-v1", "build-v1"
        );
        assertThrows(IllegalArgumentException.class, () -> store.save(wrong, index));
    }
}

