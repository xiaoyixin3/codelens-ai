package ai.codelens.api;

import ai.codelens.semantic.ReuseDecisionService;
import ai.codelens.store.ReuseDecisionStore;
import org.junit.jupiter.api.Test;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReusePlanningControllerTest {
    @Test
    void enforcesAuthorizationUuidAndInstallationOwnershipBeforeAcceptingADecision() {
        ReuseDecisionStore store = mock(ReuseDecisionStore.class);
        AdminRequestAuthorizer authorizer = mock(AdminRequestAuthorizer.class);
        when(authorizer.authorize(any())).thenReturn(new AdminRequestAuthorizer.Result(42, "reviewer", null));
        ReusePlanningController controller = new ReusePlanningController(store, authorizer);
        ReuseDecisionService.Submission submission = new ReuseDecisionService.Submission(0,
                "reuse:head", "base", "head", "adapter", "base-model", "head-model", "valid goal",
                "reuse", "candidate", Map.of(), "valid justification",
                new ReuseDecisionService.ChangeBudget(1, 1, false),
                new ReuseDecisionService.OptionSubmission("reuse", "title", "long enough summary", "candidate",
                        List.of("src/Main.java"), List.of(), List.of("test"), List.of("tradeoff")));

        var invalid = controller.decide("not-a-uuid", submission, Map.of());
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, invalid.getStatusCode());
        verify(store, never()).save(anyLong(), anyString(), any(), anyString());

        String review = "11111111-1111-1111-1111-111111111111";
        when(store.save(42, review, submission, "reviewer"))
                .thenThrow(new IllegalArgumentException("headSha does not match the frozen investigation"));
        var rejected = controller.decide(review, submission, Map.of());
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, rejected.getStatusCode());

        doThrow(new ReuseDecisionStore.DecisionConflictException("reload"))
                .when(store).save(42, review, submission, "reviewer");
        assertEquals(HttpStatus.CONFLICT, controller.decide(review, submission, Map.of()).getStatusCode());

        when(store.get(42, review)).thenThrow(new EmptyResultDataAccessException(1));
        assertEquals(HttpStatus.NOT_FOUND, controller.get(review, Map.of()).getStatusCode());
    }
}
