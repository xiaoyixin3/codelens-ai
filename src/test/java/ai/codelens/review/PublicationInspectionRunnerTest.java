package ai.codelens.review;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

import static org.junit.jupiter.api.Assertions.*;

class PublicationInspectionRunnerTest {
    private final String id = "00000000-0000-0000-0000-000000000020";
    @Test void acceptsExactlyOneCanonicalRunId() {
        assertEquals(id, PublicationInspectionRunner.runId(new DefaultApplicationArguments("--run-id=" + id)));
    }
    @Test void rejectsWriteFlagsAndAmbiguousInputs() {
        for (String[] args : new String[][]{ {}, {"--run-id=" + id, "--force"}, {"--run-id=" + id, "--run-id=" + id},
                {"--run-id=bad"}, {"--run-id=" + id, "requeue"}, {"--run-id"} }) {
            assertThrows(IllegalArgumentException.class, () -> PublicationInspectionRunner.runId(new DefaultApplicationArguments(args)));
        }
    }
    @Test void rejectsEveryMixedProfileBeforeComponentsCanStart() {
        assertDoesNotThrow(() -> PublicationInspectionRunner.requireReadOnlyProfiles(new String[]{"publication-inspect"}));
        for (String profile : new String[]{"worker", "api", "migrate", "default"}) {
            assertThrows(IllegalArgumentException.class, () -> PublicationInspectionRunner.requireReadOnlyProfiles(new String[]{"publication-inspect", profile}));
        }
    }
}
