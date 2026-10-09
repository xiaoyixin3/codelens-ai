package ai.codelens.review;

import ai.codelens.contracts.Models;
import ai.codelens.credentials.CredentialVault;
import ai.codelens.github.PublicationUncertainException;
import ai.codelens.store.JdbcStore;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/** Immutable derived output. Never stores a workspace, prompt, or complete source file. */
public record FrozenReviewOutput(int version, Models.ReviewJob job, long repositoryId, long checkId,
                                String conclusion, String title, String markdown,
                                List<Models.Annotation> annotations, String summaryJson) {
    static final int MAX_BYTES = 1_048_576;

    public FrozenReviewOutput {
        if (version != 1 || job == null || !job.valid() || repositoryId < 1 || checkId < 1
                || !Set.of("neutral", "success", "failure").contains(conclusion)
                || title == null || markdown == null || summaryJson == null || annotations == null
                || annotations.size() > 50) throw new IllegalArgumentException("Invalid frozen review output");
        annotations = List.copyOf(annotations);
    }

    public record Sealed(String hash, String ciphertext) {}

    public Sealed seal(String key, ObjectMapper json) {
        try {
            String plaintext = json.writeValueAsString(this);
            if (plaintext.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
                throw new IllegalArgumentException("Frozen review output exceeds its byte budget");
            }
            return new Sealed(hash(plaintext), new CredentialVault(key).seal(plaintext, context(job)));
        } catch (Exception failure) { throw unavailable(); }
    }

    public static FrozenReviewOutput open(JdbcStore.FrozenOutput record, Models.ReviewJob expected,
                                         long repositoryId, boolean trial, String key, ObjectMapper json) {
        try {
            if (record.schemaVersion() != 1 || record.installationId() != expected.installationId()) throw unavailable();
            String plaintext = new CredentialVault(key).open(record.encryptedPayload(), context(expected));
            if (plaintext.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES || !hash(plaintext).equals(record.payloadHash())) throw unavailable();
            FrozenReviewOutput output = json.readValue(plaintext, FrozenReviewOutput.class);
            if (!output.job().equals(expected) || output.repositoryId() != repositoryId
                    || (trial && !output.conclusion().equals("neutral"))) throw unavailable();
            return output;
        } catch (Exception failure) { throw unavailable(); }
    }

    private static String context(Models.ReviewJob job) {
        return "publication-output:v1:installation:" + job.installationId() + ":run:" + job.reviewRunId();
    }
    private static String hash(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }
    static PublicationUncertainException unavailable() {
        // No ciphertext, plaintext, parse exception, or secret is included in the message/cause.
        return new PublicationUncertainException("ARTIFACT", "Frozen review output is unavailable or conflicts; do not regenerate", null);
    }
}
