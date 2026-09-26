package ai.codelens.semantic;

import java.nio.file.Path;
import java.util.Set;

public final class SemanticIndexService {
    private final JavaSemanticAdapter adapter;
    private final SemanticSnapshotStore snapshots;
    private final BuildModelDetector buildModels;

    public SemanticIndexService(JavaSemanticAdapter adapter, SemanticSnapshotStore snapshots, BuildModelDetector buildModels) {
        this.adapter = adapter;
        this.snapshots = snapshots;
        this.buildModels = buildModels;
    }

    public SemanticModels.Index base(String repositoryKey, Path repositoryRoot, String commitSha) {
        BuildModel model = buildModels.detect(repositoryRoot);
        SemanticSnapshotStore.Key key = key(repositoryKey, commitSha, model);
        return snapshots.load(key).orElseGet(() -> {
            SemanticModels.Index index = adapter.index(repositoryRoot, commitSha, model);
            snapshots.save(key, index);
            return index;
        });
    }

    public SemanticModels.Index head(
            String repositoryKey,
            Path repositoryRoot,
            String commitSha,
            SemanticModels.Index base,
            Set<String> changedPaths
    ) {
        BuildModel model = buildModels.detect(repositoryRoot);
        SemanticSnapshotStore.Key key = key(repositoryKey, commitSha, model);
        return snapshots.load(key).orElseGet(() -> {
            SemanticModels.Index index = adapter.indexIncremental(repositoryRoot, commitSha, model, base, changedPaths);
            snapshots.save(key, index);
            return index;
        });
    }

    private SemanticSnapshotStore.Key key(String repositoryKey, String commitSha, BuildModel model) {
        return new SemanticSnapshotStore.Key(repositoryKey, commitSha, adapter.version(), model.hash());
    }
}

