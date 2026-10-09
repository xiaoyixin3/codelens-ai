package ai.codelens.sandbox;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Prototype local append-only metadata journal. No source, diff, command output or credential is saved. */
public final class ValidationJournal {
    private final Path root;
    public ValidationJournal(Path root) throws IOException {
        this.root=root.toAbsolutePath().normalize();
        for(Path cursor=this.root;cursor!=null;cursor=cursor.getParent()) {
            if(Files.isSymbolicLink(cursor))throw new IOException("journal_symlink_refused");
        }
        Files.createDirectories(this.root);
        if(!Files.isDirectory(this.root,LinkOption.NOFOLLOW_LINKS))throw new IOException("journal_directory_required");
    }
    public void register(JavaValidationPlan plan) throws IOException {
        Path directory=root.resolve(plan.manifest().id());Files.createDirectory(directory);
        append(directory.resolve("plan.json"),plan.canonical());
    }
    public void reserve(JavaValidationPlan plan,JavaValidationPlan.Phase phase) throws IOException {
        Path directory=directory(plan);
        for(int i=0;i<phase.ordinal();i++) {
            if(!Files.isRegularFile(directory.resolve(JavaValidationPlan.Phase.values()[i]+".finished.json"),LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("previous_validation_step_unresolved");
            }
        }
        append(directory.resolve(phase+".started.json"),"{\"planHash\":\""+plan.hash()+"\"}");
    }
    public void finish(JavaValidationPlan plan,DockerJavaSandbox.StepResult result) throws IOException {
        Path directory=directory(plan);
        Path started=directory.resolve(result.phase()+".started.json");
        if(Files.isSymbolicLink(started) || !Files.isRegularFile(started,LinkOption.NOFOLLOW_LINKS) || Files.size(started)>128
                || !Files.readString(started).equals("{\"planHash\":\""+plan.hash()+"\"}"))throw new IOException("validation_reservation_required");
        append(directory.resolve(result.phase()+".finished.json"),"{\"planHash\":\""+plan.hash()+"\",\"status\":\""+result.status()
                +"\",\"failedTestIndex\":"+result.failedTestIndex()+"}");
    }
    private Path directory(JavaValidationPlan plan) throws IOException {
        Path directory=root.resolve(plan.manifest().id());
        if(Files.isSymbolicLink(directory) || !Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS))throw new IOException("validation_plan_required");
        Path manifest=directory.resolve("plan.json");
        if(Files.isSymbolicLink(manifest) || !Files.isRegularFile(manifest,LinkOption.NOFOLLOW_LINKS)
                || Files.size(manifest)>64*1024 || !Files.readString(manifest).equals(plan.canonical()))throw new IOException("validation_plan_changed");
        return directory;
    }
    private static void append(Path path,String metadata) throws IOException {
        try(var channel=FileChannel.open(path,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,StandardOpenOption.SYNC)) {
            var bytes=ByteBuffer.wrap(metadata.getBytes(StandardCharsets.UTF_8));while(bytes.hasRemaining())channel.write(bytes);channel.force(true);
        }
    }
}
