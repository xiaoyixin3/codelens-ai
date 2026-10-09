package ai.codelens.llm;

import ai.codelens.config.RuntimeConfig;
import ai.codelens.contracts.Models;
import ai.codelens.store.JdbcStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LlmClientTest {
    private static Models.ImpactSummary impact() { return new Models.ImpactSummary("low",0,0,0,List.of(),"fixture"); }
    @Test void eachPhysicalRetryHasItsOwnDurableReservationAndSameNonemptyHash() throws Exception {
        try(Fixture f=new Fixture(503)) {
            assertThrows(IllegalStateException.class,()->f.client.generateSummary("run",f.pull,ModelContextPlanTest.policy(),impact()));
            assertEquals(3,f.sends.get());
            var reservations=org.mockito.ArgumentCaptor.forClass(JdbcStore.LlmCall.class);
            verify(f.store,times(3)).reserveLlmCall(reservations.capture(),anyString(),eq(4),eq(250000));
            var outcomes=org.mockito.ArgumentCaptor.forClass(JdbcStore.LlmCall.class);
            verify(f.store,times(3)).finishLlmCall(outcomes.capture());
            assertEquals(3,reservations.getAllValues().stream().map(JdbcStore.LlmCall::id).distinct().count());
            assertEquals(1,reservations.getAllValues().stream().map(JdbcStore.LlmCall::promptHash).distinct().count());
            for(int i=0;i<3;i++) { assertEquals(reservations.getAllValues().get(i).inputChars(),outcomes.getAllValues().get(i).inputChars()); assertTrue(outcomes.getAllValues().get(i).inputChars()>0); }
        }
    }
    @Test void refusedReservationMakesZeroRequests() throws Exception {
        try(Fixture f=new Fixture(200)) {
            doThrow(new IllegalStateException("budget refused")).when(f.store).reserveLlmCall(any(),anyString(),anyInt(),anyInt());
            assertThrows(IllegalStateException.class,()->f.client.generateSummary("run",f.pull,ModelContextPlanTest.policy(),impact()));
            assertEquals(0,f.sends.get()); verify(f.store,never()).finishLlmCall(any());
        }
    }
    @Test void auditFinishFailureDoesNotRetryOrFallbackAfterSuccessfulHttp() throws Exception {
        try(Fixture f=new Fixture(200)) {
            doThrow(new IllegalStateException("audit unavailable")).when(f.store).finishLlmCall(any());
            assertThrows(IllegalStateException.class,()->f.client.generateSummary("run",f.pull,ModelContextPlanTest.policy(),impact()));
            assertEquals(1,f.sends.get()); verify(f.store,times(1)).reserveLlmCall(any(),anyString(),anyInt(),anyInt());
        }
    }
    @Test void successfulSummaryReportsPartialPatchesAndSerializesRedactionSafely() throws Exception {
        try(Fixture f=new Fixture(200)) {
            when(f.config.maxPatchChars()).thenReturn(15);
            var summary=f.client.generateSummary("run",f.pull,ModelContextPlanTest.policy(),impact());
            assertTrue(summary.coverage().truncated()); assertTrue(summary.coverage().limitations().stream().anyMatch(v->v.startsWith("Model input:")));
            assertEquals(1,f.sends.get());
            var reserved=org.mockito.ArgumentCaptor.forClass(JdbcStore.LlmCall.class);
            verify(f.store).reserveLlmCall(reserved.capture(),anyString(),anyInt(),anyInt());
            assertEquals(f.received.length,reserved.getValue().inputChars());
            assertEquals(ai.codelens.intelligence.CodeIntelligenceService.digest(new String(f.received,java.nio.charset.StandardCharsets.UTF_8)),reserved.getValue().promptHash());
        }
    }
    @Test void lostOwnerAfterReservationCannotSendOrRefund() throws Exception {
        try(Fixture f=new Fixture(200)) {
            var checks=new AtomicInteger();
            assertThrows(ai.codelens.store.LeaseLostException.class,()->f.client.generateSummary("run",f.pull,ModelContextPlanTest.policy(),impact(),()->{
                if(checks.incrementAndGet()==2) throw new ai.codelens.store.LeaseLostException();
            }));
            assertEquals(0,f.sends.get()); verify(f.store,times(1)).reserveLlmCall(any(),anyString(),anyInt(),anyInt());
            verify(f.store,never()).finishLlmCall(any());
        }
    }
    static final class Fixture implements AutoCloseable {
        final HttpServer server; final AtomicInteger sends=new AtomicInteger();
        volatile byte[] received;
        final RuntimeConfig config=mock(RuntimeConfig.class); final JdbcStore store=mock(JdbcStore.class);
        final Models.PullRequest pull=ModelContextPlanTest.pull(List.of(ModelContextPlanTest.file("a.java","@@ -1 +1 @@\n+password=syntheticcredential\n")));
        final LlmClient client;
        Fixture(int status) throws Exception {
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/chat/completions",exchange->{
                sends.incrementAndGet(); var json=new ObjectMapper(); received=exchange.getRequestBody().readAllBytes(); var body=json.readTree(received);
                assertEquals("fixture",body.path("model").asText());
                String input=body.path("messages").get(1).path("content").asText(); assertFalse(input.contains("syntheticcredential")); json.readTree(input);
                String answer="{\"intent\":\"change behavior\",\"overview\":\"review\",\"files\":[],\"riskLevel\":\"low\",\"riskReasons\":[]}";
                byte[] response=json.writeValueAsBytes(java.util.Map.of("choices",List.of(java.util.Map.of("message",java.util.Map.of("content",answer)))));
                exchange.sendResponseHeaders(status,response.length); exchange.getResponseBody().write(response); exchange.close();
            }); server.start();
            when(config.llmBaseUrl()).thenReturn("http://127.0.0.1:"+server.getAddress().getPort());
            when(config.llmFallbackBaseUrl()).thenReturn(""); when(config.llmApiKey()).thenReturn("fixture-only"); when(config.llmModel()).thenReturn("fixture");
            when(config.maxChangedFiles()).thenReturn(3); when(config.maxPatchChars()).thenReturn(1000);
            when(config.llmMaxCalls()).thenReturn(4); when(config.llmMaxInputChars()).thenReturn(250000);
            client=new LlmClient(config,store,new ObjectMapper());
        }
        public void close(){ server.stop(0); }
    }
}
