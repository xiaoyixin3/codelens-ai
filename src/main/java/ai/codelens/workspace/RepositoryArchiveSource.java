package ai.codelens.workspace;

import java.nio.file.Path;

/** Downloads an authenticated repository archive without executing repository content. */
public interface RepositoryArchiveSource {
    void download(long installationId, String owner, String repository, String commitSha, Path destination, long maxBytes);
}
