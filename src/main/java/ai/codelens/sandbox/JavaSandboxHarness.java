package ai.codelens.sandbox;

import javax.tools.ToolProvider;
import java.io.DataInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** JDK-only container supervisor. Never run this entry point on the review host. */
public final class JavaSandboxHarness {
    public static final int MAGIC=0x434C5331;
    public static final String POLICY="stdlib-javac17-probe-v1";
    public static final int MAX_FILES=24, MAX_BYTES=1024*1024, MAX_FILE_BYTES=256*1024;
    private JavaSandboxHarness() {}
    public static boolean safePath(String value) {
        return value!=null && value.length()<=512 && value.matches("[A-Za-z0-9_.$/-]+\\.java") && !value.startsWith("/")
                && java.util.Arrays.stream(value.split("/",-1)).noneMatch(part->part.isEmpty() || part.equals(".") || part.equals("..") || part.equals(".git"));
    }
    public static boolean safeMain(String value) {
        return value!=null && value.length()<=200 && value.matches("(?:[A-Za-z_$][A-Za-z0-9_$]*\\.)*[A-Za-z_$][A-Za-z0-9_$]*");
    }
    public static void main(String[] ignored) {
        // A deployment mistake must not turn this into a host compiler/executor.
        if(!"/opt/codelens-sandbox".equals(System.getProperty("user.dir"))
                || !Files.isRegularFile(Path.of("/opt/codelens-sandbox/container-only"))) {
            System.out.println("CLS1 REFUSED -1"); return;
        }
        try { System.out.println(run()); }
        catch(Throwable refused) { System.out.println("CLS1 REFUSED -1"); }
    }
    private static String run() throws Exception {
        var input=new DataInputStream(System.in);
        if(input.readInt()!=MAGIC) throw new IllegalArgumentException();
        int count=input.readInt(); if(count<1 || count>MAX_FILES) throw new IllegalArgumentException();
        Path root=Files.createDirectory(Path.of("/work/src")); Path classes=Files.createDirectory(Path.of("/work/classes"));
        var paths=new ArrayList<Path>(); var unique=new HashSet<String>(); int total=0;
        for(int i=0;i<count;i++) {
            String name=input.readUTF(); int length=input.readInt();
            if(!safePath(name) || !unique.add(name.toLowerCase(java.util.Locale.ROOT)) || length<1 || length>MAX_FILE_BYTES
                    || (total+=length)>MAX_BYTES) throw new IllegalArgumentException();
            byte[] bytes=input.readNBytes(length); if(bytes.length!=length) throw new IllegalArgumentException();
            String source=StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            if(source.indexOf(0)>=0) throw new IllegalArgumentException();
            Path target=root.resolve(name).normalize(); if(!target.startsWith(root)) throw new IllegalArgumentException();
            Files.createDirectories(target.getParent()); Files.write(target,bytes); paths.add(target);
        }
        int mains=input.readInt(); if(mains<1 || mains>2) throw new IllegalArgumentException();
        List<String> entrypoints=new ArrayList<>();
        for(int i=0;i<mains;i++){String main=input.readUTF();if(!safeMain(main)) throw new IllegalArgumentException();entrypoints.add(main);}
        if(input.read()!=-1) throw new IllegalArgumentException();
        var compiler=ToolProvider.getSystemJavaCompiler(); if(compiler==null) throw new IllegalArgumentException();
        try(var manager=compiler.getStandardFileManager(null,null,StandardCharsets.UTF_8)) {
            boolean compiled=compiler.getTask(java.io.Writer.nullWriter(),manager,diagnostic->{},
                    List.of("--release","17","-proc:none","-implicit:none","-classpath",classes.toString(),
                            "-sourcepath",root.toString(),"-d",classes.toString()),null,manager.getJavaFileObjectsFromPaths(paths)).call();
            if(!compiled) return "CLS1 COMPILE_FAILED -1";
        }
        for(int i=0;i<entrypoints.size();i++) {
            var builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/java").toString(),"-Xmx96m",
                    "-XX:+DisableAttachMechanism","-XX:-UsePerfData","-Djava.io.tmpdir=/tmp","-cp",classes.toString(),entrypoints.get(i));
            builder.directory(Path.of("/work").toFile()); builder.environment().clear();
            builder.environment().put("HOME","/tmp"); builder.environment().put("LANG","C.UTF-8");
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
            var process=builder.start();
            if(!process.waitFor(5,TimeUnit.SECONDS)){process.destroyForcibly();process.waitFor(1,TimeUnit.SECONDS);return "CLS1 TEST_TIMEOUT "+i;}
            if(process.exitValue()!=0) return "CLS1 TEST_FAILED "+i;
        }
        return "CLS1 TEST_PASSED -1";
    }
}
