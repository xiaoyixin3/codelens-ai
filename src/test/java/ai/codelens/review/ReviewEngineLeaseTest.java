package ai.codelens.review;

import ai.codelens.config.RuntimeConfig;
import ai.codelens.contracts.Models;
import ai.codelens.github.GitHubClient;
import ai.codelens.github.PublicationUncertainException;
import ai.codelens.intelligence.CodeIntelligenceService;
import ai.codelens.llm.LlmClient;
import ai.codelens.policy.RepositoryPolicyService;
import ai.codelens.semantic.SemanticReviewService;
import ai.codelens.store.JdbcStore;
import ai.codelens.store.LeaseLostException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class ReviewEngineLeaseTest {
    private final JdbcStore store = mock(JdbcStore.class);
    private final GitHubClient github = mock(GitHubClient.class);
    private final CodeIntelligenceService intelligence = mock(CodeIntelligenceService.class);
    private final SemanticReviewService semantics = mock(SemanticReviewService.class);
    private final RepositoryPolicyService policies = mock(RepositoryPolicyService.class);
    private final RuntimeConfig config = mock(RuntimeConfig.class);
    private final LlmClient llm = mock(LlmClient.class);
    private final ObjectMapper json = new ObjectMapper();
    private final String publicationKey = java.util.Base64.getEncoder().encodeToString(new byte[32]);
    private final AtomicBoolean owned = new AtomicBoolean(true);
    private final Models.ReviewJob job = new Models.ReviewJob("run", 1, "owner", "repo", 7, "base1234", "head1234");
    private final SemanticReviewService.Result fallback = new SemanticReviewService.Result(false, false,
            "disabled", "S0", null, null, null, null, null);
    private final ReviewExecutionGuard guard = new ReviewExecutionGuard() {
        public void check() { if (!owned.get()) throw new LeaseLostException(); }
        public void write(Runnable action) { check(); action.run(); }
    };
    private ReviewEngine engine;

    @BeforeEach void setup() {
        when(store.getReviewRun("run")).thenReturn(new Models.ReviewRun("run", 42, 7,
                "base1234", "head1234", "queued", "test", "enqueue", "webhook", "automatic", null));
        when(store.getPublication("run")).thenReturn(Optional.empty());
        when(github.getReviewCheck(any(), anyLong(), anyString())).thenReturn(new ObjectMapper().createObjectNode().put("status", "in_progress"));
        when(github.startCheck(eq(1L), eq("owner"), eq("repo"), eq("head1234"), isNull(), anyString())).thenReturn(9L);
        when(github.getPullRequest(1, "owner", "repo", 7)).thenReturn(new Models.PullRequest(
                7, "change", "", "base1234", "head1234", List.of()));
        when(github.currentRevision(1, "owner", "repo", 7)).thenReturn(new Models.PullRequestRevision("base1234", "head1234"));
        Models.RepositoryPolicy policy = new Models.RepositoryPolicy("resolved", "head1234", "en", false,
                10, Map.of(), List.of(), List.of(), List.of(), "", List.of());
        when(policies.load(42, 1, "owner", "repo", "head1234")).thenReturn(policy);
        when(policies.filterFiles(anyList(), eq(policy))).thenReturn(List.of());
        when(intelligence.analyze(eq("run"), eq(42L), eq(job), any(), eq(guard))).thenReturn(
                new CodeIntelligenceService.Result(null, null, List.of(), List.of(),
                        new Models.ImpactSummary("low", 0, 0, 0, List.of(), "fallback")));
        when(semantics.analyze(eq(42L), eq(job), any(), eq(guard))).thenReturn(fallback);
        engine = new ReviewEngine(store, github, policies, intelligence, semantics,
                llm, config, json);
    }

    private FrozenReviewOutput frozenOutput() {
        when(config.frozenPublicationsEnabled()).thenReturn(true);
        when(config.publicationKey()).thenReturn(publicationKey);
        FrozenReviewOutput output = new FrozenReviewOutput(1, job, 42, 9, "neutral",
                "Original title", "Original explanation and solution", List.of(new Models.Annotation(
                "src/Main.java", 3, 3, "notice", "Original issue", "Original suggestion", "Original evidence")),
                "{\"original\":true}");
        FrozenReviewOutput.Sealed sealed = output.seal(publicationKey, json);
        when(store.getFrozenReviewOutput("run")).thenReturn(Optional.of(
                new JdbcStore.FrozenOutput(1, 1, sealed.hash(), sealed.ciphertext())));
        CheckPublisher publisher = new CheckPublisher(store, github, json);
        when(store.getCheckPublicationEffect("run", "check_start")).thenReturn(Optional.of(
                new JdbcStore.CheckPublicationEffect(
                        publisher.fingerprint(List.of("check-start-v1", publisher.identity(job))), "confirmed", 9L)));
        when(github.findReviewCheck(eq(job), anyString(), any())).thenReturn(Optional.of(9L));
        return output;
    }

    @Test void frozenReplayReusesOriginalOutputWithoutAnalysisOrModel() {
        FrozenReviewOutput output = frozenOutput();
        java.util.concurrent.atomic.AtomicReference<String> comment = new java.util.concurrent.atomic.AtomicReference<>();
        when(github.writeSummaryComment(eq(job), anyString(), isNull(), any())).thenAnswer(call -> {
            comment.set(call.getArgument(1)); return 11L;
        });
        when(github.getSummaryComment(eq(job), eq(11L), any())).thenAnswer(call ->
                json.createObjectNode().put("body", comment.get()));
        engine.execute(job, guard);
        verifyNoInteractions(policies, intelligence, semantics, llm);
        verify(github, never()).getPullRequest(anyLong(), anyString(), anyString(), anyInt());
        verify(github, never()).startCheck(anyLong(), anyString(), anyString(), anyString(), any(), anyString());
        verify(github).completeCheck(eq(1L), eq("owner"), eq("repo"), eq(9L), eq("neutral"),
                eq(output.title()), startsWith(output.markdown()), eq(output.annotations()));
        verify(store).updateReviewRun("run", "completed", output.summaryJson(), "", "");
        verify(store, never()).freezeReviewOutput(any(), anyLong(), anyString(), anyString());
    }

    @Test void frozenRecordCannotBeRegeneratedWhenFeatureIsDisabled() {
        frozenOutput();
        when(config.frozenPublicationsEnabled()).thenReturn(false);
        assertThrows(PublicationUncertainException.class, () -> engine.execute(job, guard));
        verifyNoInteractions(github, policies, intelligence, semantics, llm);
    }

    @Test void damagedFrozenRecordStopsBeforeRemoteWork() {
        frozenOutput();
        when(config.publicationKey()).thenReturn(java.util.Base64.getEncoder().encodeToString(new byte[31]));
        assertThrows(PublicationUncertainException.class, () -> engine.execute(job, guard));
        verifyNoInteractions(github, policies, intelligence, semantics, llm);
    }

    @Test void publishedIntentWithoutFrozenRecordMustNotBeBackfilled() {
        when(config.frozenPublicationsEnabled()).thenReturn(true);
        when(store.getCheckPublicationEffect("run", "check_result")).thenReturn(Optional.of(
                new JdbcStore.CheckPublicationEffect("old-hash", "intent", 9L)));
        assertThrows(PublicationUncertainException.class, () -> engine.execute(job, guard));
        verifyNoInteractions(github, policies, intelligence, semantics, llm);
    }

    @Test void staleFrozenRevisionCannotPublishOriginalResult() {
        frozenOutput();
        when(github.currentRevision(1, "owner", "repo", 7)).thenReturn(new Models.PullRequestRevision("changed-base", "head1234"));
        engine.execute(job, guard);
        verify(github).completeCheck(eq(1L), eq("owner"), eq("repo"), eq(9L), eq("cancelled"), anyString(), anyString(), eq(List.of()));
        verify(github, never()).writeSummaryComment(any(), anyString(), any(), any());
        verifyNoInteractions(policies, intelligence, semantics, llm);
    }

    @Test void frozenPersistenceFailureCannotPublishResult() {
        when(config.frozenPublicationsEnabled()).thenReturn(true);
        when(config.publicationKey()).thenReturn(publicationKey);
        doThrow(new IllegalStateException("database failure")).when(store)
                .freezeReviewOutput(eq(job), eq(42L), anyString(), anyString());
        assertThrows(PublicationUncertainException.class, () -> engine.execute(job, guard));
        verify(github, never()).completeCheck(anyLong(), anyString(), anyString(), anyLong(), anyString(), anyString(), anyString(), anyList());
        verify(github, never()).writeSummaryComment(any(), anyString(), any(), any());
    }

    @Test void newOutputIsFrozenBeforeAnyResultWrite() {
        when(config.frozenPublicationsEnabled()).thenReturn(true);
        when(config.publicationKey()).thenReturn(publicationKey);
        when(github.writeSummaryComment(any(), anyString(), any(), any()))
                .thenThrow(new IllegalStateException("stop after Check"));
        assertThrows(PublicationUncertainException.class, () -> engine.execute(job, guard));
        var order = inOrder(store, github);
        order.verify(store).freezeReviewOutput(eq(job), eq(42L), anyString(), anyString());
        order.verify(github).completeCheck(anyLong(), anyString(), anyString(), anyLong(), anyString(), anyString(), anyString(), anyList());
    }

    @Test void remoteCheckSuccessFollowedByLocalFailureRequiresReconciliation() {
        doThrow(new IllegalStateException("database unavailable")).when(store).savePublication(any());
        assertThrows(PublicationUncertainException.class, () -> engine.execute(job, guard));
        verify(github).startCheck(eq(1L), eq("owner"), eq("repo"), eq("head1234"), isNull(), anyString());
        verifyNoInteractions(intelligence, semantics);
    }

    @Test void commentFailureAfterSuccessfulCheckMustNotReplayAnnotations() {
        when(github.writeSummaryComment(any(), anyString(), any(), any()))
                .thenThrow(new IllegalStateException("comment permission denied"));
        assertThrows(PublicationUncertainException.class, () -> engine.execute(job, guard));
        verify(github, times(1)).completeCheck(anyLong(), anyString(), anyString(), anyLong(), anyString(), anyString(), anyString(), anyList());
        verify(store, never()).updateReviewRun(eq("run"), eq("completed"), any(), anyString(), anyString());
    }

    @Test void lostOwnerCannotStartRemoteWork() {
        owned.set(false);
        assertThrows(LeaseLostException.class, () -> engine.execute(job, guard));
        verifyNoInteractions(github);
    }

    @Test void lossDuringRemoteCheckCreationStopsLocalPublicationWrites() {
        when(github.startCheck(eq(1L), eq("owner"), eq("repo"), eq("head1234"), isNull(), anyString())).thenAnswer(call -> { owned.set(false); return 9L; });
        assertThrows(LeaseLostException.class, () -> engine.execute(job, guard));
        verify(store, never()).savePublication(any());
        verify(store, never()).updateReviewRun(anyString(), anyString(), any(), anyString(), anyString());
        verifyNoInteractions(intelligence, semantics);
    }

    @Test void lossDuringSemanticWorkStopsFindingsAndPublication() {
        when(semantics.analyze(eq(42L), eq(job), any(), eq(guard))).thenAnswer(call -> { owned.set(false); return fallback; });
        assertThrows(LeaseLostException.class, () -> engine.execute(job, guard));
        verify(store, never()).saveFindings(anyString(), anyList());
        verify(github, never()).completeCheck(anyLong(), anyString(), anyString(), anyLong(), anyString(), anyString(), anyString(), anyList());
    }

    @Test void lossAfterTheFinalRevisionReadStopsRemoteResultPublication() {
        when(github.currentRevision(1, "owner", "repo", 7)).thenAnswer(call -> new Models.PullRequestRevision("base1234", "head1234"))
                .thenAnswer(call -> { owned.set(false); return new Models.PullRequestRevision("base1234", "head1234"); });
        assertThrows(LeaseLostException.class, () -> engine.execute(job, guard));
        verify(github, never()).completeCheck(anyLong(), anyString(), anyString(), anyLong(), anyString(), anyString(), anyString(), anyList());
        verify(github, never()).writeSummaryComment(any(), anyString(), any(), any());
    }

    @Test void lossDuringCheckPublicationStopsTheNextCommentAndTerminalWrite() {
        doAnswer(call -> { owned.set(false); return null; }).when(github).completeCheck(
                anyLong(), anyString(), anyString(), anyLong(), anyString(), anyString(), anyString(), anyList());
        assertThrows(LeaseLostException.class, () -> engine.execute(job, guard));
        verify(github, never()).writeSummaryComment(any(), anyString(), any(), any());
        verify(store, never()).updateReviewRun(eq("run"), eq("completed"), any(), anyString(), anyString());
    }
}
