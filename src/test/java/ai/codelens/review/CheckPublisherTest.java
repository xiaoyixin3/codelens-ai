package ai.codelens.review;

import ai.codelens.contracts.Models;
import ai.codelens.github.GitHubClient;
import ai.codelens.github.PublicationUncertainException;
import ai.codelens.store.JdbcStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CheckPublisherTest {
    private final JdbcStore store = mock(JdbcStore.class);
    private final GitHubClient github = mock(GitHubClient.class);
    private final ObjectMapper json = new ObjectMapper();
    private final Models.ReviewJob job = new Models.ReviewJob("run", 1, "owner", "repo", 7, "base1234", "head1234");
    private final Map<String, JdbcStore.CheckPublicationEffect> journal = new HashMap<>();
    private final AtomicReference<JsonNode> remote = new AtomicReference<>(json.createObjectNode().put("status", "in_progress"));
    private final AtomicBoolean failConfirmation = new AtomicBoolean();
    private final AtomicReference<String> summaryBody = new AtomicReference<>();
    private final CheckPublisher publisher = new CheckPublisher(store, github, json);
    private final ReviewExecutionGuard guard = TestReviewGuard.INSTANCE;

    @BeforeEach void setup() {
        doAnswer(call -> {
            journal.put("summary_comment", new JdbcStore.CheckPublicationEffect(call.getArgument(1), "sent", call.getArgument(2)));
            return null;
        }).when(store).beginSummaryPublication(eq("run"), anyString(), any());
        when(github.findSummaryComment(eq(job), any(), any())).thenAnswer(call -> {
            String expected = call.getArgument(1);
            return expected != null && expected.equals(summaryBody.get()) ? Optional.of(11L) : Optional.empty();
        });
        when(github.writeSummaryComment(eq(job), anyString(), any(), any())).thenAnswer(call -> {
            summaryBody.set(call.getArgument(1)); return 11L;
        });
        when(github.getSummaryComment(eq(job), anyLong(), any())).thenAnswer(call -> json.createObjectNode().put("body", summaryBody.get()));
        when(store.getCheckPublicationEffect(eq("run"), anyString())).thenAnswer(call -> Optional.ofNullable(journal.get(call.getArgument(1))));
        doAnswer(call -> {
            String operation = call.getArgument(1), hash = call.getArgument(2);
            if (journal.putIfAbsent(operation, new JdbcStore.CheckPublicationEffect(hash, "sent", null)) != null) {
                throw new PublicationUncertainException("JOURNAL", operation, null);
            }
            return null;
        }).when(store).beginCheckPublication(eq("run"), anyString(), anyString());
        doAnswer(call -> {
            if (failConfirmation.getAndSet(false)) throw new IllegalStateException("DB failed after remote success");
            journal.put(call.getArgument(1), new JdbcStore.CheckPublicationEffect(call.getArgument(2), "confirmed", call.getArgument(3)));
            return null;
        }).when(store).confirmCheckPublication(eq("run"), anyString(), anyString(), anyLong());
        when(github.findReviewCheck(eq(job), anyString(), any())).thenReturn(Optional.empty());
        when(github.startCheck(eq(1L), eq("owner"), eq("repo"), eq("head1234"), isNull(), anyString())).thenReturn(9L);
        when(github.getReviewCheck(eq(job), eq(9L), any())).thenAnswer(call -> remote.get());
        doAnswer(call -> {
            remote.set(json.createObjectNode().put("status", "completed").put("conclusion", call.<String>getArgument(4))
                    .set("output", json.createObjectNode().put("title", call.<String>getArgument(5))
                            .put("summary", call.<String>getArgument(6)).put("annotations_count", call.<List<?>>getArgument(7).size())));
            return null;
        }).when(github).completeCheck(anyLong(), anyString(), anyString(), anyLong(), anyString(), anyString(), anyString(), anyList());
    }

    @Test void identityBindsRunInstallationAndBothRevisions() {
        String identity = publisher.identity(job);
        assertEquals(identity, publisher.identity(job));
        assertTrue(identity.matches("codelens-ai:review:[0-9a-f]{64}"));
        assertNotEquals(identity, publisher.identity(new Models.ReviewJob("run", 1, "owner", "repo", 7, "base5678", "head1234")));
        assertNotEquals(identity, publisher.identity(new Models.ReviewJob("run", 2, "owner", "repo", 7, "base1234", "head1234")));
        assertNotEquals(identity, publisher.identity(new Models.ReviewJob("other", 1, "owner", "repo", 7, "base1234", "head1234")));
    }

    @Test void intentCommitsBeforeCreationAndConfirmationAfterwards() {
        assertEquals(9, publisher.start(job, null, guard));
        var order = inOrder(store, github);
        order.verify(store).beginCheckPublication(eq("run"), eq("check_start"), anyString());
        order.verify(github).startCheck(eq(1L), eq("owner"), eq("repo"), eq("head1234"), isNull(), eq(publisher.identity(job)));
        order.verify(store).confirmCheckPublication(eq("run"), eq("check_start"), anyString(), eq(9L));
    }

    @Test void remoteCreateSuccessThenLocalFailureRecoversWithoutAnotherPost() {
        failConfirmation.set(true);
        assertThrows(PublicationUncertainException.class, () -> publisher.start(job, null, guard));
        assertEquals("sent", journal.get("check_start").state());
        when(github.findReviewCheck(eq(job), anyString(), any())).thenReturn(Optional.of(9L));
        assertEquals(9, publisher.start(job, null, guard));
        assertEquals("confirmed", journal.get("check_start").state());
        verify(github, times(1)).startCheck(anyLong(), anyString(), anyString(), anyString(), any(), anyString());
    }

    @Test void createTimeoutAfterRemoteCommitIsRecoveredByReadOnlyLookup() {
        when(github.startCheck(anyLong(), anyString(), anyString(), anyString(), any(), anyString())).thenAnswer(call -> {
            when(github.findReviewCheck(eq(job), anyString(), any())).thenReturn(Optional.of(9L));
            throw new PublicationUncertainException("POST", "/check-runs", null);
        });
        assertEquals(9, publisher.start(job, null, guard));
        assertEquals("confirmed", journal.get("check_start").state());
        verify(github, times(1)).startCheck(anyLong(), anyString(), anyString(), anyString(), any(), anyString());
    }

    @Test void recordedSendWithNoRemoteEvidenceNeverResends() {
        failConfirmation.set(true);
        assertThrows(PublicationUncertainException.class, () -> publisher.start(job, null, guard));
        assertThrows(PublicationUncertainException.class, () -> publisher.start(job, null, guard));
        verify(github, times(1)).startCheck(anyLong(), anyString(), anyString(), anyString(), any(), anyString());
    }

    @Test void rerunValidatesOldCheckButCreatesANewOneWithoutOldAnnotations() {
        assertEquals(9, publisher.start(job, 5L, guard));
        verify(github).getReviewCheck(job, 5L, null);
        verify(github).startCheck(eq(1L), eq("owner"), eq("repo"), eq("head1234"), isNull(), anyString());
    }

    @Test void resultLocalConfirmationFailureRecoversWithoutAppendingAgain() {
        failConfirmation.set(true);
        assertThrows(PublicationUncertainException.class, this::complete);
        assertEquals("sent", journal.get("check_result").state());
        complete();
        assertEquals("confirmed", journal.get("check_result").state());
        verify(github, times(1)).completeCheck(anyLong(), anyString(), anyString(), anyLong(), anyString(), anyString(), anyString(), anyList());
    }

    @Test void timeoutAfterResultCommitIsRecoveredWithoutAnotherPatch() {
        doAnswer(call -> {
            remote.set(json.createObjectNode().put("status", "completed").put("conclusion", "success")
                    .set("output", json.createObjectNode().put("title", "title").put("summary", call.<String>getArgument(6))
                            .put("annotations_count", 0)));
            throw new PublicationUncertainException("PATCH", "/check-runs/9", null);
        }).when(github).completeCheck(anyLong(), anyString(), anyString(), anyLong(), anyString(), anyString(), anyString(), anyList());
        complete();
        assertEquals("confirmed", journal.get("check_result").state());
        verify(github, times(1)).completeCheck(anyLong(), anyString(), anyString(), anyLong(), anyString(), anyString(), anyString(), anyList());
    }

    @Test void alreadyConfirmedResultIsReadBackAndNotPatchedAgain() {
        complete(); complete();
        verify(github, times(1)).completeCheck(anyLong(), anyString(), anyString(), anyLong(), anyString(), anyString(), anyString(), anyList());
    }

    @Test void changedReplayContentStopsBeforeAnotherRemoteWrite() {
        complete();
        assertThrows(PublicationUncertainException.class, () -> publisher.complete(job, 9, "success", "title", "changed", List.of(), guard));
        verify(github, times(1)).completeCheck(anyLong(), anyString(), anyString(), anyLong(), anyString(), anyString(), anyString(), anyList());
    }

    @Test void missingRecordedResultNeverReappendsAnnotations() {
        complete();
        remote.set(json.createObjectNode().put("status", "in_progress"));
        assertThrows(PublicationUncertainException.class, this::complete);
        verify(github, times(1)).completeCheck(anyLong(), anyString(), anyString(), anyLong(), anyString(), anyString(), anyString(), anyList());
    }

    @Test void remoteResultCountMismatchStopsInsteadOfPretendingRecoverySucceeded() {
        complete();
        ((com.fasterxml.jackson.databind.node.ObjectNode) remote.get().path("output")).put("annotations_count", 1);
        assertThrows(PublicationUncertainException.class, this::complete);
    }

    @Test void summaryIntentPrecedesWriteAndConfirmationReleasesSlot() {
        assertEquals(11, publisher.summary(job, "summary", null, guard));
        var order = inOrder(store, github);
        order.verify(store).claimSummaryPublication(job);
        order.verify(store).beginSummaryPublication(eq("run"), anyString(), isNull());
        order.verify(github).writeSummaryComment(eq(job), anyString(), isNull(), any());
        order.verify(store).confirmCheckPublication(eq("run"), eq("summary_comment"), anyString(), eq(11L));
        order.verify(store).releaseSummaryPublication("run");
    }

    @Test void remoteSummarySuccessThenLocalFailureRecoversWithoutASecondWrite() {
        failConfirmation.set(true);
        assertThrows(PublicationUncertainException.class, () -> publisher.summary(job, "summary", null, guard));
        verify(store, never()).releaseSummaryPublication(anyString());
        assertEquals(11, publisher.summary(job, "summary", null, guard));
        verify(github, times(1)).writeSummaryComment(eq(job), anyString(), any(), any());
    }

    @Test void timeoutAfterSummaryCommitUsesReadOnlyReconciliation() {
        when(github.writeSummaryComment(eq(job), anyString(), any(), any())).thenAnswer(call -> {
            summaryBody.set(call.getArgument(1)); throw new PublicationUncertainException("POST", "comments", null);
        });
        assertEquals(11, publisher.summary(job, "summary", null, guard));
        verify(github, times(1)).writeSummaryComment(eq(job), anyString(), any(), any());
    }

    @Test void missingSummaryEvidenceStopsReplayAndRetainsTheReservation() {
        failConfirmation.set(true);
        assertThrows(PublicationUncertainException.class, () -> publisher.summary(job, "summary", null, guard));
        summaryBody.set("changed remotely");
        assertThrows(PublicationUncertainException.class, () -> publisher.summary(job, "summary", null, guard));
        verify(github, times(1)).writeSummaryComment(eq(job), anyString(), any(), any());
        verify(store, never()).releaseSummaryPublication(anyString());
    }

    @Test void summaryReplayWithNewModelContentNeverWritesOverTheRecordedRequest() {
        publisher.summary(job, "summary", null, guard);
        assertThrows(PublicationUncertainException.class, () -> publisher.summary(job, "different", null, guard));
        verify(github, times(1)).writeSummaryComment(eq(job), anyString(), any(), any());
    }

    @Test void conflictingPrReservationStopsBeforeAnyGitHubCall() {
        doThrow(new PublicationUncertainException("RESERVE", "comments", null)).when(store).claimSummaryPublication(job);
        assertThrows(PublicationUncertainException.class, () -> publisher.summary(job, "summary", null, guard));
        verifyNoInteractions(github);
    }

    private void complete() { publisher.complete(job, 9, "success", "title", "summary", List.of(), guard); }
}
