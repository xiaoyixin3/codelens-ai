package ai.codelens.sandbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Unwired internal prototype. No Spring bean, API activation, repository build scripts, mounts or automatic retry. */
public final class DockerJavaSandbox {
    public enum Status { TEST_PASSED, TEST_FAILED, COMPILE_FAILED, TEST_TIMEOUT, REFUSED, INFRASTRUCTURE_FAILED }
    public record StepResult(JavaValidationPlan.Phase phase,Status status,int failedTestIndex) {}
    public record Report(String planId,String planHash,List<StepResult> steps,boolean beforeFailAfterPassObserved,
                         boolean verifiedFix,boolean automaticApplyAllowed,boolean publicationAllowed,List<String> limitations) {}
    public record Reply(int exitCode,String output,boolean timedOut) {
        @Override public String toString(){return "DockerReply[output=redacted]";}
    }
    public interface Transport { Reply call(List<String> arguments,byte[] input,Duration timeout) throws IOException,InterruptedException; }
    private final Transport docker;
    private final ValidationJournal journal;
    private final Duration stepTimeout;
    private final ObjectMapper json=new ObjectMapper();
    private static final String LABEL="ai.codelens.sandbox-plan";
    static final String WORK_TMPFS="rw,nosuid,nodev,noexec,size=16m,uid=10001,gid=10001,mode=700";
    static final String TEMP_TMPFS="rw,nosuid,nodev,noexec,size=8m,uid=10001,gid=10001,mode=700";
    public DockerJavaSandbox(Transport docker,ValidationJournal journal,Duration stepTimeout) {
        if(stepTimeout==null || stepTimeout.compareTo(Duration.ofSeconds(2))<0 || stepTimeout.compareTo(Duration.ofSeconds(30))>0)throw new IllegalArgumentException("bounded_step_timeout_required");
        this.docker=docker;this.journal=journal;this.stepTimeout=stepTimeout;
    }
    public Report run(JavaValidationPlan plan) throws IOException,InterruptedException {
        if(stepTimeout.compareTo(Duration.ofSeconds(plan.manifest().maxStageSeconds()))>0)throw new IOException("planned_runtime_budget_exceeded");
        // Durable metadata precedes every physical execution, including a failed attempt.
        journal.register(plan); preflight(plan);
        var results=new ArrayList<StepResult>();boolean observed=false;
        for(var phase:JavaValidationPlan.Phase.values()) {
            journal.reserve(plan,phase);var result=step(plan,phase);journal.finish(plan,result);results.add(result);
            if(phase==JavaValidationPlan.Phase.BASELINE && result.status()!=Status.TEST_PASSED)break;
            if(phase==JavaValidationPlan.Phase.HEAD_REGRESSION && (result.status()!=Status.TEST_FAILED || result.failedTestIndex()!=1))break;
            if(phase==JavaValidationPlan.Phase.PATCHED_REGRESSION)observed=result.status()==Status.TEST_PASSED;
        }
        return new Report(plan.manifest().id(),plan.hash(),List.copyOf(results),observed,false,false,false,List.of(
                "Internal standard-library-only probe: no Maven/Gradle, plugins, dependencies or JUnit adapter.",
                "Before-fail/after-pass on supplied tests is limited execution evidence, not proof of candidate reuse, public behavioral compatibility or a verified fix.",
                "No live remote revision or fresh server-side execution authorization was checked; production routing remains disabled.",
                "A local file journal is not a distributed durable queue or cross-backup anti-replay authority; crash orphan cleanup and security acceptance remain open."));
    }
    private void preflight(JavaValidationPlan plan) throws IOException,InterruptedException {
        var context=required(List.of("context","show"),null).output().trim();
        if(!context.matches("[A-Za-z0-9_.-]{1,128}"))throw new IOException("local_docker_context_required");
        var endpoint=json.readTree(required(List.of("context","inspect",context,"--format","{{json .Endpoints.docker}}"),null).output()).path("Host").asText();
        if(!endpoint.startsWith("unix:///") && !endpoint.startsWith("npipe:////./pipe/"))throw new IOException("remote_docker_refused");
        var image=json.readTree(required(List.of("image","inspect",plan.manifest().imageId(),"--format","{{json .}}"),null).output());
        if(!plan.manifest().imageId().equals(image.path("Id").asText()) || !image.path("Os").asText().equals("linux")
                || !image.path("Config").path("Labels").path("ai.codelens.sandbox-policy").asText().equals(JavaSandboxHarness.POLICY)) {
            throw new IOException("approved_linux_image_required");
        }
    }
    private StepResult step(JavaValidationPlan plan,JavaValidationPlan.Phase phase) throws IOException,InterruptedException {
        String name="codelens-java-probe-"+UUID.randomUUID(),id=null;
        try {
            id=required(createArguments(plan,phase,name),null).output().trim();
            if(!id.matches("[0-9a-f]{64}"))throw new IOException("invalid_created_container_identity");
            var inspection=inspect(id);owned(inspection,id,name,plan);isolated(inspection,plan);
            var response=docker.call(List.of("start","--attach","--interactive",id),plan.payload(phase),stepTimeout);
            if(response.timedOut())return new StepResult(phase,Status.TEST_TIMEOUT,-1);
            if(response.exitCode()!=0)return new StepResult(phase,Status.INFRASTRUCTURE_FAILED,-1);
            String[] fields=response.output().strip().split(" ");
            if(fields.length!=3 || !fields[0].equals("CLS1"))return new StepResult(phase,Status.REFUSED,-1);
            try {
                var status=Status.valueOf(fields[1]);int index=Integer.parseInt(fields[2]);
                int count=plan.manifest().steps().get(phase.ordinal()).testMains().size();
                if((status==Status.TEST_FAILED || status==Status.TEST_TIMEOUT)?index<0 || index>=count:index!=-1)throw new IllegalArgumentException();
                return new StepResult(phase,status,index);
            }catch(RuntimeException invalid){return new StepResult(phase,Status.REFUSED,-1);}
        } finally {
            // Resolve only our unpredictable name, then require full identity + plan label before removal.
            var response=docker.call(List.of("inspect",name),null,Duration.ofSeconds(10));
            if(response.timedOut())throw new IOException("sandbox_cleanup_unresolved");
            if(response.exitCode()==0) {
                var data=json.readTree(response.output()).get(0);String actual=data.path("Id").asText();
                if(!actual.matches("[0-9a-f]{64}"))throw new IOException("sandbox_cleanup_identity_refused");
                owned(data,id==null?actual:id,name,plan);required(List.of("rm","--force",actual),null);
            } else if(id!=null)throw new IOException("sandbox_cleanup_unresolved");
        }
    }
    static List<String> createArguments(JavaValidationPlan plan,JavaValidationPlan.Phase phase,String name) {
        return List.of("create","--interactive","--pull","never","--name",name,"--label",LABEL+"="+plan.manifest().id(),
                "--label","ai.codelens.sandbox-phase="+phase,"--network","none","--read-only","--user","10001:10001",
                "--cap-drop","ALL","--security-opt","no-new-privileges:true","--memory","384m","--memory-swap","384m",
                "--cpus","1","--pids-limit","64","--init","--no-healthcheck","--log-driver","none","--restart","no",
                "--ulimit","nofile=128:128","--ulimit","fsize=1048576:1048576","--shm-size","1m",
                "--tmpfs","/work:"+WORK_TMPFS,"--tmpfs","/tmp:"+TEMP_TMPFS,"--workdir","/opt/codelens-sandbox",
                "--entrypoint","java",plan.manifest().imageId(),"-Xmx128m","-XX:+DisableAttachMechanism","-XX:-UsePerfData",
                "-Djava.io.tmpdir=/tmp","-cp","/opt/codelens-sandbox/harness.jar",JavaSandboxHarness.class.getName());
    }
    private JsonNode inspect(String id) throws IOException,InterruptedException{return json.readTree(required(List.of("inspect",id),null).output()).get(0);}
    private static void owned(JsonNode data,String id,String name,JavaValidationPlan plan) throws IOException {
        if(data==null || !id.equals(data.path("Id").asText()) || !("/"+name).equals(data.path("Name").asText())
                || !plan.manifest().id().equals(data.path("Config").path("Labels").path(LABEL).asText()))throw new IOException("sandbox_identity_refused");
    }
    private static void isolated(JsonNode data,JavaValidationPlan plan) throws IOException {
        var host=data.path("HostConfig");var config=data.path("Config");
        boolean safe=data.path("Image").asText().equals(plan.manifest().imageId()) && config.path("User").asText().equals("10001:10001")
                && host.path("NetworkMode").asText().equals("none") && host.path("ReadonlyRootfs").asBoolean()
                && !host.path("Privileged").asBoolean() && host.path("Memory").asLong()==384L*1024*1024
                && host.path("MemorySwap").asLong()==384L*1024*1024 && host.path("NanoCpus").asLong()==1_000_000_000L
                && host.path("PidsLimit").asInt()==64 && host.path("Init").asBoolean()
                && host.path("CapDrop").toString().equals("[\"ALL\"]") && host.path("CapAdd").size()==0
                && host.path("SecurityOpt").size()==1 && List.of("no-new-privileges","no-new-privileges:true").contains(host.path("SecurityOpt").get(0).asText())
                && host.path("Binds").size()==0
                && host.path("Mounts").size()==0 && host.path("Devices").size()==0 && host.path("DeviceRequests").size()==0
                && host.path("VolumesFrom").size()==0 && config.path("Volumes").size()==0
                && host.path("LogConfig").path("Type").asText().equals("none") && host.path("RestartPolicy").path("Name").asText().equals("no")
                && host.path("PidMode").asText().isEmpty() && host.path("UTSMode").asText().isEmpty()
                && host.path("IpcMode").asText().equals("private") && host.path("Tmpfs").size()==2
                && host.path("ShmSize").asLong()==1024L*1024 && limit(host,"nofile",128) && limit(host,"fsize",1048576)
                && host.path("Tmpfs").path("/work").asText().equals(WORK_TMPFS) && host.path("Tmpfs").path("/tmp").asText().equals(TEMP_TMPFS);
        for(var mount:data.path("Mounts"))safe &= mount.path("Type").asText().equals("tmpfs");
        if(!safe)throw new IOException("sandbox_isolation_refused");
    }
    private static boolean limit(JsonNode host,String name,long value) {
        int matches=0;
        for(var limit:host.path("Ulimits"))if(limit.path("Name").asText().equals(name)) {
            if(limit.path("Soft").asLong()!=value || limit.path("Hard").asLong()!=value)return false;
            matches++;
        }
        return matches==1;
    }
    private Reply required(List<String> args,byte[] input) throws IOException,InterruptedException {
        var reply=docker.call(args,input,Duration.ofSeconds(10));if(reply.timedOut() || reply.exitCode()!=0)throw new IOException("docker_operation_unavailable");return reply;
    }
    /** Only invokes the trusted Docker client, with fixed argument lists; never a host shell or Java source. */
    public static final class ProcessTransport implements Transport {
        public ProcessTransport(){if(System.getenv("DOCKER_HOST")!=null && !System.getenv("DOCKER_HOST").isBlank())throw new IllegalStateException("docker_host_override_refused");}
        public Reply call(List<String> arguments,byte[] input,Duration timeout) throws IOException,InterruptedException {
            var args=new ArrayList<String>();args.add("docker");args.addAll(arguments);
            var process=new ProcessBuilder(args).redirectErrorStream(true).start();
            var executor=Executors.newFixedThreadPool(2,r->{var thread=new Thread(r,"sandbox-docker-io");thread.setDaemon(true);return thread;});
            var truncated=new AtomicBoolean();
            var read=executor.submit(()->{try(var stream=process.getInputStream();var bytes=new java.io.ByteArrayOutputStream()) {
                byte[] buffer=new byte[4096];int length;while((length=stream.read(buffer))!=-1){int remaining=65536-bytes.size();if(length>remaining)truncated.set(true);if(remaining>0)bytes.write(buffer,0,Math.min(length,remaining));}
                return bytes.toString(StandardCharsets.UTF_8);
            }});
            var write=executor.submit(()->{try(var stream=process.getOutputStream()){if(input!=null)stream.write(input);}return null;});
            try {
                if(!process.waitFor(timeout.toMillis(),TimeUnit.MILLISECONDS))return new Reply(-1,"",true);
                write.get(1,TimeUnit.SECONDS);String output=read.get(2,TimeUnit.SECONDS);
                return new Reply(process.exitValue(),truncated.get()?"":output,false);
            }catch(java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException failed){throw new IOException("bounded_docker_io_failed");}
            finally {if(process.isAlive()){process.destroyForcibly();process.waitFor(2,TimeUnit.SECONDS);}read.cancel(true);write.cancel(true);executor.shutdownNow();}
        }
    }
}
