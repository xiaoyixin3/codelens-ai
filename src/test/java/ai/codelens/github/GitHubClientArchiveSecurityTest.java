package ai.codelens.github;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitHubClientArchiveSecurityTest {
    @Test
    void allowsOnlyExpectedHttpsArchiveHostsWithoutCredentialsOrPorts() {
        assertTrue(GitHubClient.allowedArchiveUri(URI.create("https://api.github.com/repos/o/r/zipball/abc")));
        assertTrue(GitHubClient.allowedArchiveUri(URI.create("https://codeload.github.com/o/r/legacy.zip/abc")));
        assertFalse(GitHubClient.allowedArchiveUri(URI.create("http://codeload.github.com/o/r.zip")));
        assertFalse(GitHubClient.allowedArchiveUri(URI.create("https://codeload.github.com.evil.example/r.zip")));
        assertFalse(GitHubClient.allowedArchiveUri(URI.create("https://user@example.com/r.zip")));
        assertFalse(GitHubClient.allowedArchiveUri(URI.create("https://api.github.com:444/r.zip")));
    }
}
