package ai.codelens.review;

import ai.codelens.contracts.Models;
import ai.codelens.github.PublicationUncertainException;
import ai.codelens.store.JdbcStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FrozenReviewOutputTest {
    private final ObjectMapper json = new ObjectMapper();
    private final String key = Base64.getEncoder().encodeToString(new byte[32]);
    private final Models.ReviewJob job = new Models.ReviewJob("run", 1, "owner", "repo", 7, "base1234", "head1234");
    private FrozenReviewOutput output(String body) {
        return new FrozenReviewOutput(1, job, 42, 9, "neutral", "original title", body, List.of(), "{\"riskLevel\":\"low\"}");
    }
    private JdbcStore.FrozenOutput record(FrozenReviewOutput.Sealed sealed) {
        return new JdbcStore.FrozenOutput(1, 1, sealed.hash(), sealed.ciphertext());
    }

    @Test void encryptsAndPreservesTheOriginalOutputExactly() {
        var original = output("private evidence excerpt");
        var sealed = original.seal(key, json);
        assertFalse(sealed.ciphertext().contains("private evidence"));
        assertEquals(original, FrozenReviewOutput.open(record(sealed), job, 42, true, key, json));
        var again = original.seal(key, json);
        assertEquals(sealed.hash(), again.hash());
        assertNotEquals(sealed.ciphertext(), again.ciphertext());
    }

    @Test void rejectsTamperingWrongKeyOrWrongInstallationWithoutExposingContent() {
        var sealed = output("sensitive-original").seal(key, json);
        byte[] bytes = Base64.getDecoder().decode(sealed.ciphertext()); bytes[bytes.length - 1] ^= 1;
        var tampered = new JdbcStore.FrozenOutput(1, 1, sealed.hash(), Base64.getEncoder().encodeToString(bytes));
        var error = assertThrows(PublicationUncertainException.class, () -> FrozenReviewOutput.open(tampered, job, 42, true, key, json));
        assertFalse(error.getMessage().contains("sensitive-original")); assertNull(error.getCause());
        assertThrows(PublicationUncertainException.class, () -> FrozenReviewOutput.open(record(sealed), job, 42, true, Base64.getEncoder().encodeToString(new byte[31]), json));
        byte[] wrongKey = new byte[32]; wrongKey[0] = 1;
        assertThrows(PublicationUncertainException.class, () -> FrozenReviewOutput.open(record(sealed), job, 42, true, Base64.getEncoder().encodeToString(wrongKey), json));
        var other = new Models.ReviewJob("run", 2, "owner", "repo", 7, "base1234", "head1234");
        assertThrows(PublicationUncertainException.class, () -> FrozenReviewOutput.open(record(sealed), other, 42, true, key, json));
    }

    @Test void rejectsHashRevisionRepositoryAndRunIdentityConflicts() {
        var sealed = output("original").seal(key, json);
        assertThrows(PublicationUncertainException.class, () -> FrozenReviewOutput.open(new JdbcStore.FrozenOutput(1, 1, "0".repeat(64), sealed.ciphertext()), job, 42, true, key, json));
        assertThrows(PublicationUncertainException.class, () -> FrozenReviewOutput.open(record(sealed), job, 43, true, key, json));
        var changed = new Models.ReviewJob("run", 1, "owner", "repo", 7, "base5678", "head1234");
        assertThrows(PublicationUncertainException.class, () -> FrozenReviewOutput.open(record(sealed), changed, 42, true, key, json));
        var otherRun = new Models.ReviewJob("other", 1, "owner", "repo", 7, "base1234", "head1234");
        assertThrows(PublicationUncertainException.class, () -> FrozenReviewOutput.open(record(sealed), otherRun, 42, true, key, json));
    }

    @Test void boundsUtf8BytesAndNeverDowngradesTrialSafety() {
        assertThrows(PublicationUncertainException.class, () -> output("界".repeat(FrozenReviewOutput.MAX_BYTES / 2)).seal(key, json));
        var blocking = new FrozenReviewOutput(1, job, 42, 9, "failure", "title", "body", List.of(), "{}").seal(key, json);
        assertThrows(PublicationUncertainException.class, () -> FrozenReviewOutput.open(record(blocking), job, 42, true, key, json));
    }
}
