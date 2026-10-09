package ai.codelens.store;

import ai.codelens.config.RuntimeConfig;
import ai.codelens.contracts.Models;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="CODELENS_INTEGRATION_TESTS",matches="true")
class LlmBudgetIntegrationTest {
    @Test void crashReservationsAreNotRefundedAndNewStoreInstanceCannotResetRunBudget() throws Exception {
        try(Fixture f=new Fixture()) {
            var first=f.call(60); f.reserve(f.store,first,3,100);
            var restarted=new JdbcStore(f.jdbc,f.pool,f.json);
            assertThrows(IllegalStateException.class,()->f.reserve(restarted,f.call(41),3,100));
            f.store.finishLlmCall(f.outcome(first,60));
            assertThrows(IllegalStateException.class,()->f.reserve(restarted,f.call(41),3,100));
            var second=f.call(40); f.reserve(restarted,second,3,100); f.store.finishLlmCall(f.outcome(second,40));
            assertThrows(IllegalStateException.class,()->f.reserve(f.store,f.call(1),3,100));
            assertEquals(2,f.count("llm_calls")); assertEquals(2,f.count("llm_request_plans"));
            assertEquals(100L,f.jdbc.queryForObject("SELECT sum(request_bytes) FROM llm_request_plans WHERE review_run_id=?::uuid",Long.class,f.id));
        }
    }
    @Test void unknownOutcomeStopsRestartEvenWhenBudgetStillHasRoom() throws Exception {
        try(Fixture f=new Fixture()) {
            f.reserve(f.store,f.call(10),4,100);
            var restarted=new JdbcStore(f.jdbc,f.pool,f.json);
            assertThrows(IllegalStateException.class,()->f.reserve(restarted,f.call(10),4,100));
            assertEquals(1,f.count("llm_calls")); assertEquals(1,f.count("llm_request_plans"));
        }
    }
    @Test void telemetryRetentionCannotEraseSpendAndRunRetentionRemovesMetadata() throws Exception {
        try(Fixture f=new Fixture()) {
            var first=f.call(10); f.reserve(f.store,first,4,100); f.store.finishLlmCall(f.outcome(first,10));
            f.jdbc.update("DELETE FROM llm_calls WHERE id=?::uuid",first.id());
            assertEquals(0,f.count("llm_calls")); assertEquals(1,f.count("llm_request_plans"));
            assertThrows(IllegalStateException.class,()->f.reserve(f.store,f.call(10),4,100));
            f.jdbc.update("DELETE FROM review_runs WHERE id=?::uuid",f.id);
            assertEquals(0,f.count("llm_request_plans"));
        }
    }
    @Test void oversizedFirstRequestNeverCreatesReservation() throws Exception {
        try(Fixture f=new Fixture()) {
            assertThrows(IllegalStateException.class,()->f.reserve(f.store,f.call(101),4,100));
            assertEquals(0,f.count("llm_calls")); assertEquals(0,f.count("llm_request_plans"));
        }
    }
    @Test void concurrentReservationsCannotBothSpendLastCall() throws Exception {
        try(Fixture f=new Fixture()) {
            var barrier=new CyclicBarrier(2); var executor=Executors.newFixedThreadPool(2);
            try {
                Callable<Boolean> send=()->{barrier.await(5,TimeUnit.SECONDS); try{f.reserve(f.store,f.call(10),1,100);return true;}catch(IllegalStateException exhausted){return false;}};
                var first=executor.submit(send); var second=executor.submit(send);
                assertNotEquals(first.get(10,TimeUnit.SECONDS),second.get(10,TimeUnit.SECONDS));
                assertEquals(1,f.count("llm_calls")); assertEquals(1,f.count("llm_request_plans"));
            }finally{executor.shutdownNow();}
        }
    }
    @Test void finishingAttemptKeepsOriginalChargeAndRejectsSecondOrAlteredOutcome() throws Exception {
        try(Fixture f=new Fixture()) {
            var call=f.call(75); f.reserve(f.store,call,1,100);
            assertThrows(IllegalStateException.class,()->f.store.finishLlmCall(f.outcome(call,74)));
            f.store.finishLlmCall(f.outcome(call,75));
            assertThrows(IllegalStateException.class,()->f.store.finishLlmCall(f.outcome(call,75)));
            assertThrows(IllegalStateException.class,()->f.reserve(f.store,f.call(1),1,100));
            assertEquals(75,f.jdbc.queryForObject("SELECT input_chars FROM llm_calls WHERE id=?::uuid",Integer.class,call.id()));
            assertThrows(org.springframework.dao.DataAccessException.class,()->f.jdbc.update("UPDATE llm_request_plans SET request_bytes=1 WHERE call_id=?::uuid",call.id()));
        }
    }
    @Test void wrongSnapshotChangedLimitsLegacyUnknownUsageAndInactiveRunsFailClosed() throws Exception {
        try(Fixture f=new Fixture()) {
            var call=f.call(10); f.reserve(f.store,call,4,100);
            f.store.finishLlmCall(f.outcome(call,10));
            assertThrows(IllegalStateException.class,()->f.reserve(f.store,f.call(10),5,100));
            f.store.updateReviewRunConfig(f.id,"changed");
            assertThrows(IllegalStateException.class,()->f.reserve(f.store,f.call(10),4,100));
            f.store.updateReviewRunConfig(f.id,"fixture"); f.store.updateReviewRun(f.id,"stale",null,"","");
            assertThrows(IllegalStateException.class,()->f.reserve(f.store,f.call(10),4,100));
            f.store.updateReviewRun(f.id,"in_progress",null,"","");
            f.store.recordLlmCall(f.call(1)); // Pre-plan telemetry cannot be used as a reliable spend snapshot.
            assertThrows(IllegalStateException.class,()->f.reserve(f.store,f.call(10),4,100));
            assertEquals(1,f.count("llm_request_plans"));
        }
    }
    @Test void reservationCannotHideInsideRollbackOrPersistMismatchedPlan() throws Exception {
        try(Fixture f=new Fixture()) {
            var transaction=new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(f.pool));
            transaction.executeWithoutResult(status -> assertThrows(IllegalStateException.class,()->f.reserve(f.store,f.call(10),4,100)));
            var call=f.call(10);
            assertThrows(IllegalArgumentException.class,()->f.store.reserveLlmCall(call,"{}",4,100));
            assertEquals(0,f.count("llm_calls")); assertEquals(0,f.count("llm_request_plans"));
        }
    }
    static final class Fixture implements AutoCloseable {
        final HikariDataSource pool; final JdbcTemplate jdbc; final ObjectMapper json=new ObjectMapper(); final JdbcStore store;
        final long repository=UUID.randomUUID().getLeastSignificantBits()&Long.MAX_VALUE; final String id;
        Fixture(){
            var config=RuntimeConfig.fromEnvironment(); var settings=new HikariConfig(); settings.setJdbcUrl(config.jdbcUrl());
            settings.setUsername(config.databaseUser()); settings.setPassword(config.databasePassword()); settings.setMaximumPoolSize(3);
            pool=new HikariDataSource(settings); jdbc=new JdbcTemplate(pool); store=new JdbcStore(jdbc,pool,json);
            id=store.createOrGetAndEnqueue(new JdbcStore.CreateReviewInput(repository,7,"base1234","head1234",Models.PIPELINE_VERSION,"fixture","webhook","fixture"),
                    new Models.ReviewJob("pending",1,"llm-test","fixture",7,"base1234","head1234")).run().id();
            store.updateReviewRun(id,"in_progress",null,"","");
        }
        JdbcStore.LlmCall call(int bytes){return new JdbcStore.LlmCall(UUID.randomUUID().toString(),id,"fixture","fixture","summary","a".repeat(64),"reserved",bytes,0,null,null,0,null,"","",Instant.now());}
        JdbcStore.LlmCall outcome(JdbcStore.LlmCall call,int bytes){return new JdbcStore.LlmCall(call.id(),id,"fixture","fixture","summary",call.promptHash(),"failed",bytes,0,null,null,1,503,"HTTP_503","",call.createdAt());}
        void reserve(JdbcStore target,JdbcStore.LlmCall call,int calls,int bytes) throws Exception {
            String plan=json.writeValueAsString(Map.of("schemaVersion",1,"requestBytes",call.inputChars(),"requestHash",call.promptHash(),"task",call.task(),
                    "baseSha","base1234","headSha","head1234","policyHash","fixture"));
            target.reserveLlmCall(call,plan,calls,bytes);
        }
        int count(String table){assertTrue(java.util.Set.of("llm_calls","llm_request_plans").contains(table));return jdbc.queryForObject("SELECT count(*) FROM "+table+" WHERE review_run_id=?::uuid",Integer.class,id);}
        public void close(){try{jdbc.update("DELETE FROM llm_calls WHERE review_run_id=?::uuid",id);jdbc.update("DELETE FROM review_runs WHERE id=?::uuid",id);}finally{pool.close();}}
    }
}
