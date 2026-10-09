package ai.codelens.api;

import ai.codelens.semantic.LocalPatchPreview;
import ai.codelens.semantic.LocalPatchPreviewTest;
import ai.codelens.store.ReuseDecisionStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PatchPreviewControllerTest {
    @Test void authorizationAndCanonicalUuidAreCheckedBeforeReadingSource() throws Exception {
        try(var f=new Fixture()) {
            var request=spy(f.request());
            when(f.auth.authorize(any())).thenReturn(new AdminRequestAuthorizer.Result(0,"",ResponseEntity.notFound().build()));
            var denied=f.controller.preview(f.context.runId(),Map.of(),request);
            assertEquals(HttpStatus.NOT_FOUND,denied.getStatusCode());
            assertEquals("no-store",denied.getHeaders().getFirst("Cache-Control"));
            verify(request,never()).getInputStream(); verifyNoInteractions(f.store);
            when(f.auth.authorize(any())).thenReturn(new AdminRequestAuthorizer.Result(42,"test",null));
            assertEquals(HttpStatus.UNPROCESSABLE_ENTITY,f.controller.preview("1-1-1-1-1",Map.of(),request).getStatusCode());
            verify(request,never()).getInputStream(); verifyNoInteractions(f.store);
        }
    }
    @Test void returnsNoncacheablePreviewAfterSecondContextCheckAndDoesNotWrite() throws Exception {
        try(var f=new Fixture()) {
            var response=f.controller.preview(f.context.runId(),Map.of(),f.request());
            assertEquals(HttpStatus.OK,response.getStatusCode()); assertEquals("no-store",response.getHeaders().getFirst("Cache-Control"));
            var preview=(LocalPatchPreview.Preview)response.getBody(); assertNotNull(preview);
            assertFalse(preview.verifiedFix()); assertFalse(preview.publicationAllowed());
            verify(f.store,times(2)).previewContext(eq(42L),eq(f.context.runId()),eq(f.context.planning().currentDecision().id()),eq(1),eq(List.of(LocalPatchPreviewTest.PATH)));
            assertTrue(mockingDetails(f.store).getInvocations().stream().allMatch(call->call.getMethod().getName().equals("previewContext")));
        }
    }
    @Test void rejectsUntrustedJsonAndOversizedBodyBeforeDatabaseReads() throws Exception {
        try(var f=new Fixture()) {
            var oversized=new MockHttpServletRequest(); oversized.setContent(new byte[LocalPatchPreview.MAX_REQUEST_BYTES+1]);
            assertEquals(HttpStatus.PAYLOAD_TOO_LARGE,f.controller.preview(f.context.runId(),Map.of(),oversized).getStatusCode());
            var invalid=new MockHttpServletRequest(); invalid.setContent("not json with sensitive content".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var response=f.controller.preview(f.context.runId(),Map.of(),invalid);
            assertEquals(HttpStatus.UNPROCESSABLE_ENTITY,response.getStatusCode()); assertFalse(response.getBody().toString().contains("sensitive"));
            verifyNoInteractions(f.store);
        }
    }
    @Test void decisionSupersededAfterGenerationReturnsOnlyConflictNotDiff() throws Exception {
        try(var f=new Fixture()) {
            when(f.store.previewContext(anyLong(),anyString(),anyString(),anyInt(),anyList())).thenReturn(f.context)
                    .thenThrow(new ReuseDecisionStore.DecisionConflictException("secret diagnostics"));
            var response=f.controller.preview(f.context.runId(),Map.of(),f.request());
            assertEquals(HttpStatus.CONFLICT,response.getStatusCode()); assertEquals(Map.of("error","preview_context_changed"),response.getBody());
        }
    }
    @Test void forgedSourceProducesGenericRefusalWithoutLeakingSubmittedCode() throws Exception {
        try(var f=new Fixture()) {
            var input=LocalPatchPreviewTest.input("secret submitted code",LocalPatchPreviewTest.input().files().get(0).edits());
            var request=new MockHttpServletRequest(); request.setContent(f.json.writeValueAsBytes(input));
            var response=f.controller.preview(f.context.runId(),Map.of(),request);
            assertEquals(HttpStatus.UNPROCESSABLE_ENTITY,response.getStatusCode()); assertEquals(Map.of("error","preview_rejected"),response.getBody());
            verify(f.store,times(1)).previewContext(anyLong(),anyString(),anyString(),anyInt(),anyList());
        }
    }
    static final class Fixture implements AutoCloseable {
        final ObjectMapper json=new ObjectMapper(); final ReuseDecisionStore store=mock(ReuseDecisionStore.class);
        final AdminRequestAuthorizer auth=mock(AdminRequestAuthorizer.class);
        final LocalPatchPreview.Context context=LocalPatchPreviewTest.context(LocalPatchPreviewTest.SOURCE,2);
        final PatchPreviewController controller=new PatchPreviewController(store,auth,json);
        Fixture(){when(auth.authorize(any())).thenReturn(new AdminRequestAuthorizer.Result(42,"test",null));when(store.previewContext(anyLong(),anyString(),anyString(),anyInt(),anyList())).thenReturn(context);}
        MockHttpServletRequest request() throws Exception {var request=new MockHttpServletRequest();request.setContent(json.writeValueAsBytes(LocalPatchPreviewTest.input()));return request;}
        public void close(){}
    }
}
