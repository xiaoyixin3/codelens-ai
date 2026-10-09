package ai.codelens.sandbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DockerJavaSandboxTest {
    @Test void observedPatternDoesNotAuthorizePublicationAndNeverUsesShellMountOrMutableTag(@TempDir Path root) throws Exception {
        var plan=JavaValidationPlanTest.plan();var fake=new Fake(plan);var runner=runner(root,fake);
        var report=runner.run(plan);assertTrue(report.beforeFailAfterPassObserved());assertFalse(report.verifiedFix());assertFalse(report.publicationAllowed());assertFalse(report.automaticApplyAllowed());
        assertEquals(3,report.steps().size());assertEquals(3,fake.count("rm"));
        for(var args:fake.commands.stream().filter(args->args.get(0).equals("create")).toList()) {
            assertFalse(args.contains("--mount"));assertFalse(args.contains("--volume"));assertFalse(args.contains("--privileged"));assertFalse(args.contains("sh"));
            assertEquals("none",args.get(args.indexOf("--network")+1));assertEquals("never",args.get(args.indexOf("--pull")+1));
            assertEquals("10001:10001",args.get(args.indexOf("--user")+1));assertTrue(args.contains("--read-only"));
        }
        assertThrows(java.io.IOException.class,()->runner.run(plan));assertEquals(3,fake.count("create"));
    }
    @Test void baselineFailureExistingTestFailureAndAlreadyPassingRegressionStopAtCorrectStage(@TempDir Path root) throws Exception {
        int i=0;
        for(var results:List.of(List.of("CLS1 COMPILE_FAILED -1"),List.of("CLS1 TEST_FAILED 0"),
                List.of("CLS1 TEST_PASSED -1","CLS1 TEST_PASSED -1"),List.of("CLS1 TEST_PASSED -1","CLS1 TEST_FAILED 0"),
                List.of("CLS1 TEST_PASSED -1","CLS1 TEST_FAILED 1","CLS1 TEST_FAILED 1"))) {
            var plan=JavaValidationPlanTest.plan();var fake=new Fake(plan);fake.results=results;
            var report=runner(root.resolve("case-"+i++),fake).run(plan);
            assertFalse(report.beforeFailAfterPassObserved());assertEquals(results.size(),report.steps().size());assertEquals(results.size(),fake.count("rm"));
        }
    }
    @Test void outerTimeoutCleansItsContainerAndSavesOnlyStatus(@TempDir Path root) throws Exception {
        var plan=JavaValidationPlanTest.plan();var fake=new Fake(plan);fake.timeout=true;
        var report=runner(root,fake).run(plan);assertEquals(DockerJavaSandbox.Status.TEST_TIMEOUT,report.steps().get(0).status());assertEquals(1,fake.count("rm"));
        String finished=Files.readString(root.resolve(plan.manifest().id()).resolve("BASELINE.finished.json"));assertTrue(finished.contains("TEST_TIMEOUT"));assertFalse(finished.contains("raw"));
    }
    @Test void unrecognizedOrOversizedSupervisorOutputIsNotSuccess(@TempDir Path root) throws Exception {
        var plan=JavaValidationPlanTest.plan();var fake=new Fake(plan);fake.results=List.of("raw source and secret-looking output");
        var report=runner(root,fake).run(plan);assertEquals(DockerJavaSandbox.Status.REFUSED,report.steps().get(0).status());assertFalse(report.beforeFailAfterPassObserved());
        assertFalse(Files.readString(root.resolve(plan.manifest().id()).resolve("BASELINE.finished.json")).contains("secret-looking"));
    }
    @Test void daemonPolicyMismatchRefusesBeforeCodeStarts(@TempDir Path root) throws Exception {
        for(int variant=1;variant<=3;variant++) {
            var plan=JavaValidationPlanTest.plan();var fake=new Fake(plan);fake.weakened=variant;Path caseRoot=root.resolve("case-"+variant);
            assertThrows(java.io.IOException.class,()->runner(caseRoot,fake).run(plan));assertEquals(0,fake.count("start"));assertEquals(1,fake.count("rm"));
            assertTrue(Files.exists(caseRoot.resolve(plan.manifest().id()).resolve("BASELINE.started.json")));
            assertFalse(Files.exists(caseRoot.resolve(plan.manifest().id()).resolve("BASELINE.finished.json")));
        }
    }
    @Test void unexpectedContainerIdentityIsNeverRemoved(@TempDir Path root) throws Exception {
        var plan=JavaValidationPlanTest.plan();var fake=new Fake(plan);fake.wrongOwner=true;
        assertThrows(java.io.IOException.class,()->runner(root,fake).run(plan));assertEquals(0,fake.count("start"));assertEquals(0,fake.count("rm"));
    }
    @Test void remoteDaemonAndWrongImageFailBeforeContainerCreation(@TempDir Path root) throws Exception {
        for(boolean remote:List.of(true,false)) {
            var plan=JavaValidationPlanTest.plan();var fake=new Fake(plan);fake.remote=remote;fake.badImage=!remote;
            assertThrows(java.io.IOException.class,()->runner(root.resolve(Boolean.toString(remote)),fake).run(plan));assertEquals(0,fake.count("create"));
        }
    }
    @Test void journalFailurePreventsAllDockerCalls(@TempDir Path root) throws Exception {
        var plan=JavaValidationPlanTest.plan();var fake=new Fake(plan);var journal=new ValidationJournal(root);journal.register(plan);
        assertThrows(java.io.IOException.class,()->new DockerJavaSandbox(fake,journal,Duration.ofSeconds(15)).run(plan));assertTrue(fake.commands.isEmpty());
        var fresh=JavaValidationPlanTest.plan();
        assertThrows(java.io.IOException.class,()->new DockerJavaSandbox(fake,journal,Duration.ofSeconds(16)).run(fresh));assertTrue(fake.commands.isEmpty());
    }
    static DockerJavaSandbox runner(Path root,Fake fake) throws Exception{fake.journalDirectory=root;return new DockerJavaSandbox(fake,new ValidationJournal(root),Duration.ofSeconds(15));}
    static final class Fake implements DockerJavaSandbox.Transport {
        final JavaValidationPlan plan;final List<List<String>> commands=new ArrayList<>();String name;int starts;
        int weakened;boolean wrongOwner,remote,badImage,timeout;
        List<String> results=List.of("CLS1 TEST_PASSED -1","CLS1 TEST_FAILED 1","CLS1 TEST_PASSED -1");
        final String id="f".repeat(64);
        Fake(JavaValidationPlan plan){this.plan=plan;}
        long count(String verb){return commands.stream().filter(args->args.get(0).equals(verb)).count();}
        public DockerJavaSandbox.Reply call(List<String> args,byte[] input,Duration duration) throws java.io.IOException {
            commands.add(List.copyOf(args));String output="";
            switch(args.get(0)) {
                case "context" -> output=args.get(1).equals("show")?"desktop-linux":"{\"Host\":\""+(remote?"tcp://remote:2376":"npipe:////./pipe/dockerDesktopLinuxEngine")+"\"}";
                case "image" -> {
                    var data=new ObjectMapper().createObjectNode();data.put("Id",badImage?"sha256:"+"a".repeat(64):plan.manifest().imageId()).put("Os","linux");
                    data.putObject("Config").putObject("Labels").put("ai.codelens.sandbox-policy",JavaSandboxHarness.POLICY);output=data.toString();
                }
                case "create" -> {name=args.get(args.indexOf("--name")+1);output=id;assertTrue(Files.isRegularFile(journalDirectory.resolve(plan.manifest().id()).resolve("plan.json")));}
                case "inspect" -> output="["+inspection()+"]";
                case "start" -> {assertNotNull(input);if(timeout)return new DockerJavaSandbox.Reply(-1,"",true);output=results.get(starts++);}
                case "rm" -> assertEquals(id,args.get(2));
                default -> throw new AssertionError("Unexpected Docker command");
            }
            return new DockerJavaSandbox.Reply(0,output,false);
        }
        Path journalDirectory;
        ObjectNode inspection() {
            var json=new ObjectMapper();var data=json.createObjectNode();data.put("Id",id).put("Name","/"+name).put("Image",plan.manifest().imageId());
            var config=data.putObject("Config");config.put("User","10001:10001");config.putObject("Labels").put("ai.codelens.sandbox-plan",wrongOwner?"another-plan":plan.manifest().id());
            var h=data.putObject("HostConfig");h.put("NetworkMode","none").put("ReadonlyRootfs",weakened!=1).put("Privileged",false).put("Memory",384L*1024*1024)
                    .put("MemorySwap",384L*1024*1024).put("NanoCpus",1_000_000_000L).put("PidsLimit",64).put("Init",true)
                    .put("PidMode","").put("UTSMode","").put("IpcMode","private").put("ShmSize",1024L*1024);
            h.putArray("CapDrop").add("ALL");h.putArray("SecurityOpt").add(weakened==2?"no-new-privileges:false":"no-new-privileges:true");h.putObject("LogConfig").put("Type","none");h.putObject("RestartPolicy").put("Name","no");
            var limits=h.putArray("Ulimits");limits.addObject().put("Name","nofile").put("Soft",128).put("Hard",128);
            limits.addObject().put("Name","fsize").put("Soft",weakened==3?9999999:1048576).put("Hard",1048576);
            h.putObject("Tmpfs").put("/work",DockerJavaSandbox.WORK_TMPFS).put("/tmp",DockerJavaSandbox.TEMP_TMPFS);return data;
        }
    }
}
