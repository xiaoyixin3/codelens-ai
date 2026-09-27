package ai.codelens.semantic;

import ai.codelens.config.RuntimeConfig;
import ai.codelens.github.GitHubClient;
import ai.codelens.workspace.GitHubRepositoryWorkspace;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.nio.file.Path;

@Configuration
@Profile("worker")
public class SemanticRuntimeConfiguration {
    @Bean
    GitHubRepositoryWorkspace githubRepositoryWorkspace(GitHubClient github, RuntimeConfig config) {
        return new GitHubRepositoryWorkspace(github, Path.of(config.semanticWorkspaceRoot()),
                config.semanticMaxArchiveBytes(), config.semanticMaxExtractedBytes(), config.semanticMaxEntries());
    }

    @Bean
    SemanticIndexService semanticIndexService(JdbcSemanticSnapshotStore snapshots, RuntimeConfig config) {
        return new SemanticIndexService(new JavaSemanticAdapter(config.semanticMaxFiles(), config.semanticMaxFileBytes()),
                snapshots, new BuildModelDetector());
    }

    @Bean
    SemanticReviewService semanticReviewService(RuntimeConfig config, GitHubRepositoryWorkspace workspaces,
                                                SemanticIndexService indexes, SemanticReviewAuditStore audits) {
        return new SemanticReviewService(config.semanticEnabled(), config.semanticRepositories(), workspaces, indexes, audits);
    }
}
