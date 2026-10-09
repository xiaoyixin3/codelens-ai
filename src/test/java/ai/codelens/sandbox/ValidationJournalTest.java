package ai.codelens.sandbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class ValidationJournalTest {
    @Test void persistsOnlyManifestMetadataAndReservesBeforeFinishing(@TempDir Path root) throws Exception {
        var plan=JavaValidationPlanTest.plan();var journal=new ValidationJournal(root);journal.register(plan);
        Path directory=root.resolve(plan.manifest().id());String saved=Files.readString(directory.resolve("plan.json"));
        assertEquals(plan.canonical(),saved);assertFalse(saved.contains("return value"));
        var done=new DockerJavaSandbox.StepResult(JavaValidationPlan.Phase.BASELINE,DockerJavaSandbox.Status.TEST_PASSED,-1);
        assertThrows(java.io.IOException.class,()->journal.finish(plan,done));journal.reserve(plan,done.phase());journal.finish(plan,done);
        assertThrows(java.io.IOException.class,()->journal.finish(plan,done));
    }
    @Test void unknownAttemptCannotBeReusedByReconstructingJournal(@TempDir Path root) throws Exception {
        var plan=JavaValidationPlanTest.plan();var journal=new ValidationJournal(root);journal.register(plan);journal.reserve(plan,JavaValidationPlan.Phase.BASELINE);
        var restarted=new ValidationJournal(root);
        assertThrows(java.io.IOException.class,()->restarted.register(plan));
        assertThrows(java.io.IOException.class,()->restarted.reserve(plan,JavaValidationPlan.Phase.BASELINE));
        assertThrows(java.io.IOException.class,()->restarted.reserve(plan,JavaValidationPlan.Phase.HEAD_REGRESSION));
    }
    @Test void changedManifestRefusesAnyStep(@TempDir Path root) throws Exception {
        var plan=JavaValidationPlanTest.plan();var journal=new ValidationJournal(root);journal.register(plan);
        Files.writeString(root.resolve(plan.manifest().id()).resolve("plan.json"),"{}");
        assertThrows(java.io.IOException.class,()->journal.reserve(plan,JavaValidationPlan.Phase.BASELINE));
    }
    @Test void concurrentReservationHasExactlyOneWinner(@TempDir Path root) throws Exception {
        var plan=JavaValidationPlanTest.plan();var journal=new ValidationJournal(root);journal.register(plan);
        var pool=Executors.newFixedThreadPool(2);
        try {
            var action=(java.util.concurrent.Callable<Boolean>)()->{try{new ValidationJournal(root).reserve(plan,JavaValidationPlan.Phase.BASELINE);return true;}catch(java.io.IOException refused){return false;}};
            var one=pool.submit(action);var two=pool.submit(action);assertNotEquals(one.get(5,TimeUnit.SECONDS),two.get(5,TimeUnit.SECONDS));
        }finally{pool.shutdownNow();}
    }
}
