package ai.codelens.api;

import ai.codelens.semantic.LocalPatchPreview;
import ai.codelens.store.ReuseDecisionStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/** Uses the existing approval boundary; no model, GitHub client, storage of code, or publication. */
@RestController
@Profile("api")
@RequestMapping("/api/v2/reviews/{reviewRunId}/reuse-planning/patch-preview")
public final class PatchPreviewController {
    private final ReuseDecisionStore store;
    private final AdminRequestAuthorizer authorizer;
    private final ObjectMapper json;
    private final LocalPatchPreview generator=new LocalPatchPreview();
    public PatchPreviewController(ReuseDecisionStore store, AdminRequestAuthorizer authorizer, ObjectMapper json) {
        this.store=store; this.authorizer=authorizer; this.json=json;
    }
    @PostMapping
    public ResponseEntity<?> preview(@PathVariable String reviewRunId, @RequestHeader Map<String,String> headers,
                                     HttpServletRequest request) {
        var auth=authorizer.authorize(headers);
        if(auth.error()!=null) return ResponseEntity.status(auth.error().getStatusCode())
                .headers(auth.error().getHeaders()).header("Cache-Control","no-store").body(auth.error().getBody());
        if(!uuid(reviewRunId)) return error(HttpStatus.UNPROCESSABLE_ENTITY,"preview_invalid_run_id");
        LocalPatchPreview.Submission input;
        try {
            byte[] bytes=request.getInputStream().readNBytes(LocalPatchPreview.MAX_REQUEST_BYTES+1);
            if(bytes.length>LocalPatchPreview.MAX_REQUEST_BYTES) return error(HttpStatus.PAYLOAD_TOO_LARGE,"preview_request_too_large");
            input=json.readValue(bytes,LocalPatchPreview.Submission.class);
            if(input==null || !uuid(input.decisionId())) return error(HttpStatus.UNPROCESSABLE_ENTITY,"preview_invalid_decision_id");
        } catch(Exception invalid) {return error(HttpStatus.UNPROCESSABLE_ENTITY,"preview_invalid_body");}
        try {
            var paths=input.files().stream().map(LocalPatchPreview.FileInput::path).toList();
            var context=store.previewContext(auth.installationId(),reviewRunId,input.decisionId(),input.decisionRevision(),paths);
            var result=generator.generate(context,input);
            var current=store.previewContext(auth.installationId(),reviewRunId,input.decisionId(),input.decisionRevision(),paths);
            if(!current.equals(context)) return error(HttpStatus.CONFLICT,"preview_context_changed");
            return ResponseEntity.ok().header("Cache-Control","no-store").header("X-Content-Type-Options","nosniff").body(result);
        } catch(EmptyResultDataAccessException missing) {return error(HttpStatus.NOT_FOUND,"preview_context_unavailable");}
        catch(ReuseDecisionStore.DecisionConflictException changed) {return error(HttpStatus.CONFLICT,"preview_context_changed");}
        catch(IllegalArgumentException invalid) {return error(HttpStatus.UNPROCESSABLE_ENTITY,"preview_rejected");}
        catch(RuntimeException failed) {return error(HttpStatus.INTERNAL_SERVER_ERROR,"preview_unavailable");}
    }
    private static boolean uuid(String value) {try{return value!=null && UUID.fromString(value).toString().equals(value);}catch(RuntimeException invalid){return false;}}
    private static ResponseEntity<?> error(HttpStatus status,String code) {
        return ResponseEntity.status(status).header("Cache-Control","no-store").body(Map.of("error",code));
    }
}
