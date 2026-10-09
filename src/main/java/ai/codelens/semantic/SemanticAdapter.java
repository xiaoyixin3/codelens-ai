package ai.codelens.semantic;

import java.nio.file.Path;

public interface SemanticAdapter {
    String language();
    String version();
    SemanticModels.Index index(Path repositoryRoot, String commitSha, BuildModel buildModel);
}

