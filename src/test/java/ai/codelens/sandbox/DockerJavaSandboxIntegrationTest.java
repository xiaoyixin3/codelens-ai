package ai.codelens.sandbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic explicitly enabled fixture only. Does not accept a real repository, command or daemon target. */
@EnabledIfEnvironmentVariable(named="CODELENS_SANDBOX_TESTS",matches="true")
class DockerJavaSandboxIntegrationTest {
    @Test void actuallyCompilesAndObservesHeadFailureThenPatchedSuccess(@TempDir Path root) throws Exception {
        var plan=plan(JavaValidationPlanTest.existing(),JavaValidationPlanTest.regression());
        var report=run(root,plan,15);assertTrue(report.beforeFailAfterPassObserved());assertFalse(report.verifiedFix());assertFalse(report.automaticApplyAllowed());assertFalse(report.publicationAllowed());
        assertEquals(List.of(DockerJavaSandbox.Status.TEST_PASSED,DockerJavaSandbox.Status.TEST_FAILED,DockerJavaSandbox.Status.TEST_PASSED),report.steps().stream().map(DockerJavaSandbox.StepResult::status).toList());
        assertEquals(1,report.steps().get(1).failedTestIndex());
        try(var paths=Files.walk(root)){for(Path file:paths.filter(Files::isRegularFile).toList()){String text=Files.readString(file);assertFalse(text.contains("return value"));}}
    }
    @Test void baselineTestFailureDoesNotAdvance(@TempDir Path root) throws Exception {
        var report=run(root,plan(main("throw new AssertionError();"),JavaValidationPlanTest.regression()),15);
        assertEquals(1,report.steps().size());assertEquals(DockerJavaSandbox.Status.TEST_FAILED,report.steps().get(0).status());assertFalse(report.beforeFailAfterPassObserved());
    }
    @Test void alreadyPassingRegressionIsNotRepairEvidence(@TempDir Path root) throws Exception {
        var report=run(root,plan(JavaValidationPlanTest.existing(),JavaValidationPlanTest.regression().replace("!=3","<0")),15);
        assertEquals(2,report.steps().size());assertEquals(DockerJavaSandbox.Status.TEST_PASSED,report.steps().get(1).status());assertFalse(report.beforeFailAfterPassObserved());
    }
    @Test void regressionCompilationFailureIsNotReproduction(@TempDir Path root) throws Exception {
        var report=run(root,plan(JavaValidationPlanTest.existing(),JavaValidationPlanTest.regression().replace("new Target()","new MissingType()")),15);
        assertEquals(2,report.steps().size());assertEquals(DockerJavaSandbox.Status.COMPILE_FAILED,report.steps().get(1).status());assertFalse(report.beforeFailAfterPassObserved());
    }
    @Test void failingPatchedRegressionIsNeverVerified(@TempDir Path root) throws Exception {
        var report=run(root,plan(JavaValidationPlanTest.existing(),JavaValidationPlanTest.regression().replace("!=3","!=4")),15);
        assertEquals(3,report.steps().size());assertEquals(DockerJavaSandbox.Status.TEST_FAILED,report.steps().get(2).status());assertFalse(report.beforeFailAfterPassObserved());
    }
    @Test void watchdogKillsInfiniteTestAndCleansExactOwnedContainer(@TempDir Path root) throws Exception {
        var report=run(root,plan(main("while(true) { Thread.onSpinWait(); }"),JavaValidationPlanTest.regression()),2);
        assertEquals(1,report.steps().size());assertEquals(DockerJavaSandbox.Status.TEST_TIMEOUT,report.steps().get(0).status());assertFalse(report.beforeFailAfterPassObserved());
    }
    @Test void childHasNoHostCredentialsNetworkSocketOrWritableRootAndOutputIsDiscarded(@TempDir Path root) throws Exception {
        String checks="""
                if(System.getenv("DATABASE_URL")!=null || System.getenv("GITHUB_PRIVATE_KEY")!=null) throw new AssertionError();
                if(!java.nio.file.Files.readString(java.nio.file.Path.of("/proc/self/status")).contains("Uid:\\t10001\\t10001")) throw new AssertionError();
                if(java.nio.file.Files.exists(java.nio.file.Path.of("/var/run/docker.sock"))) throw new AssertionError();
                try { java.nio.file.Files.writeString(java.nio.file.Path.of("/forbidden-root-write"),"no"); throw new AssertionError(); }
                catch(java.nio.file.FileSystemException expected) {}
                try(var socket=new java.net.Socket()) {
                  try { socket.connect(new java.net.InetSocketAddress("1.1.1.1",443),250); throw new AssertionError(); }
                  catch(java.io.IOException expected) {}
                }
                java.nio.file.Files.writeString(java.nio.file.Path.of("/tmp/probe"),"ephemeral");
                System.out.println("SYNTHETIC_PRIVATE_OUTPUT");
                """;
        var report=run(root,plan(main(checks),JavaValidationPlanTest.regression()),15);assertTrue(report.beforeFailAfterPassObserved());
        try(var paths=Files.walk(root)){for(Path file:paths.filter(Files::isRegularFile).toList())assertFalse(Files.readString(file).contains("SYNTHETIC_PRIVATE_OUTPUT"));}
    }
    static String main(String body){return "package example; public class ExistingProbe { public static void main(String[] args) throws Exception { "+body+" } }";}
    static JavaValidationPlan plan(String existing,String regression){return JavaValidationPlanTest.plan(System.getenv("CODELENS_SANDBOX_FIXTURE_IMAGE"),existing,regression);}
    static DockerJavaSandbox.Report run(Path root,JavaValidationPlan plan,int seconds) throws Exception {
        var transport=new DockerJavaSandbox.ProcessTransport();
        var report=new DockerJavaSandbox(transport,new ValidationJournal(root),Duration.ofSeconds(seconds)).run(plan);
        var containers=transport.call(List.of("ps","-aq","--filter","label=ai.codelens.sandbox-plan="+plan.manifest().id()),null,Duration.ofSeconds(10));
        assertEquals(0,containers.exitCode());assertTrue(containers.output().isBlank(),"Fixture container was not cleaned up");return report;
    }
}
