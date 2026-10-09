package ai.codelens.review;

import ai.codelens.config.RuntimeConfig;
import ai.codelens.contracts.Models;
import ai.codelens.github.GitHubClient;
import ai.codelens.store.JdbcStore;
import ai.codelens.store.PublicationRecoveryAuditStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.mockito.Mockito.*;

/** Test-classpath-only child. Approval enters via private stdin, never arguments or stdout. */
public final class RecoveryCrashChild {
    enum Point { AFTER_RESERVATION, BEFORE_COMMIT, AFTER_COMMIT }
    public static void main(String[] args) throws Exception {
        if (args.length!=2 || !"true".equals(System.getenv("CODELENS_CRASH_TESTS"))
                || !args[1].equals(System.getenv("CODELENS_CRASH_FIXTURE_RUN"))) throw new IllegalArgumentException("Isolated crash fixture required");
        Point point=Point.valueOf(args[0]); java.util.UUID.fromString(args[1]);
        var runtime=RuntimeConfig.fromEnvironment();
        var uri=java.net.URI.create(runtime.jdbcUrl().substring("jdbc:".length()));
        if (!"127.0.0.1".equals(uri.getHost())) throw new IllegalArgumentException("Loopback test database required");
        var settings=new HikariConfig(); settings.setJdbcUrl(runtime.jdbcUrl());
        settings.setUsername(runtime.databaseUser()); settings.setPassword(runtime.databasePassword()); settings.setMaximumPoolSize(3);
        var pool=new HikariDataSource(settings); var armed=new AtomicBoolean();
        DataSource source=point==Point.AFTER_COMMIT ? afterCommitSource(pool,armed,point) : pool;
        var jdbc=new org.springframework.jdbc.core.JdbcTemplate(source); var json=new ObjectMapper();
        var store=new JdbcStore(jdbc,source,json); var audit=spy(new PublicationRecoveryAuditStore(jdbc,source));
        var run=store.getReviewRun(args[1]); var job=store.getPublicationInspectionInput(args[1]).job();
        if (!job.owner().equals("recovery-test") || !job.repo().equals("fixture") || job.installationId()!=1
                || !run.pipelineVersion().equals(Models.PIPELINE_VERSION)) throw new IllegalArgumentException("Test run identity required");
        var github=mock(GitHubClient.class,invocation -> { throw new AssertionError("Unexpected remote operation in crash fixture"); });
        var config=mock(RuntimeConfig.class); when(config.frozenPublicationsEnabled()).thenReturn(true);
        when(config.publicationKey()).thenReturn(PublicationRecoveryIntegrationTest.key(3));
        var hashes=new CheckPublisher(store,github,json); String identity=hashes.identity(job);
        String result=store.getCheckPublicationEffect(run.id(),"check_result").orElseThrow().requestHash();
        String summary=store.getCheckPublicationEffect(run.id(),"summary_comment").orElseThrow().requestHash();
        doReturn(new Models.PullRequestRevision(job.baseSha(),job.headSha())).when(github)
                .currentRevision(job.installationId(),job.owner(),job.repo(),job.pullNumber());
        doReturn(Optional.of(9L)).when(github).findReviewCheck(eq(job),eq(identity),any());
        doReturn(json.createObjectNode().put("status","completed").put("conclusion","neutral")
                .set("output",json.createObjectNode().put("title","title").put("annotations_count",0)
                        .put("summary","original\n\n<!-- "+identity+":result:"+result+" -->")))
                .when(github).getReviewCheck(job,9,identity);
        doReturn(List.of()).when(github).getReviewAnnotations(eq(job),eq(9L),any());
        doReturn(Optional.of(11L)).when(github).findSummaryComment(eq(job),isNull(),any());
        doReturn(json.createObjectNode().put("body",GitHubClient.SUMMARY_MARKER+"\n<!-- "+identity+":summary:"+summary+" -->\noriginal"))
                .when(github).getSummaryComment(eq(job),eq(11L),any());
        if (point==Point.AFTER_RESERVATION) doAnswer(invocation -> {
            invocation.callRealMethod(); pause(point); return null;
        }).when(audit).requested(any());
        else doAnswer(invocation -> {
            invocation.callRealMethod();
            if (point==Point.BEFORE_COMMIT) pause(point); else armed.set(true);
            return null;
        }).when(audit).committedWithinTransaction(any());
        var operators=new RecoveryOperatorAuthenticator("fixture",PublicationRecoveryIntegrationTest.key(1));
        var principal=operators.authenticate(PublicationRecoveryIntegrationTest.key(1));
        var approvals=new RecoveryApprovalService(operators,PublicationRecoveryIntegrationTest.key(2),json);
        var service=new PublicationRecoveryService(store,github,json,config,approvals,audit);
        byte[] bytes=System.in.readNBytes(8193);
        if (bytes.length<1 || bytes.length>8192) throw new IllegalArgumentException("Bounded private approval input required");
        String bearer=new String(bytes,java.nio.charset.StandardCharsets.UTF_8);
        service.recover(args[1],principal,bearer);
        throw new AssertionError("Crash checkpoint was not reached");
    }
    private static void pause(Point point) throws InterruptedException {
        System.out.println("RECOVERY_CRASH_POINT:"+point.name()); System.out.flush();
        new java.util.concurrent.CountDownLatch(1).await();
    }
    private static DataSource afterCommitSource(DataSource source,AtomicBoolean armed,Point point) {
        return (DataSource)Proxy.newProxyInstance(DataSource.class.getClassLoader(),new Class<?>[]{DataSource.class},(proxy,method,args) -> {
            try {
                Object result=method.invoke(source,args);
                if (!method.getName().equals("getConnection")) return result;
                Connection connection=(Connection)result;
                return Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(p,m,a) -> {
                    try {
                        Object value=m.invoke(connection,a);
                        if (m.getName().equals("commit") && armed.compareAndSet(true,false)) pause(point);
                        return value;
                    } catch (InvocationTargetException failed) { throw failed.getCause(); }
                });
            } catch (InvocationTargetException failed) { throw failed.getCause(); }
        });
    }
}
