package ai.codelens.workspace;

import java.nio.file.Path;

public interface RepositoryWorkspace {
    MaterializedWorkspace materialize(Path localRepository, String baseSha, String headSha);

    interface MaterializedWorkspace extends AutoCloseable {
        String executionLevel();
        String baseSha();
        String headSha();
        Path base();
        Path head();
        Path artifacts();
        Path patches();
        @Override void close();
    }
}

