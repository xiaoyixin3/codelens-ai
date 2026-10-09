package ai.codelens.review;

import ai.codelens.contracts.Models;
import ai.codelens.config.RuntimeConfig;
import ai.codelens.github.GitHubClient;
import ai.codelens.store.JdbcStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PublicationInspectorTest {
    private final JdbcStore store = mock(JdbcStore.class);
    private final GitHubClient github = mock(GitHubClient.class);
    private final ObjectMapper json = new ObjectMapper();
    private final String id = "00000000-0000-0000-0000-000000000020";
    private final Models.ReviewJob job = new Models.ReviewJob(id, 1, "owner", "repo", 7, "base1234", "head1234");
    private final CheckPublisher hashes = new CheckPublisher(store, github, json);
    private final PublicationInspector inspector = new PublicationInspector(store, github, json);
    private final RuntimeConfig config = mock(RuntimeConfig.class);
    private final String key = java.util.Base64.getEncoder().encodeToString(new byte[32]);

    @BeforeEach void setup() {
        when(store.getReviewRun(id)).thenReturn(new Models.ReviewRun(id, 42, 7, "base1234", "head1234", "failed",
                Models.PIPELINE_VERSION, "test", "webhook", "automatic", null));
        when(store.getPublicationInspectionInput(id)).thenReturn(new JdbcStore.PublicationInspectionInput(job, "failed", "PUBLICATION_UNCERTAIN", true, true));
        when(github.currentRevision(1, "owner", "repo", 7)).thenReturn(new Models.PullRequestRevision("base1234", "head1234"));
    }

    private void result(String text, List<Models.Annotation> annotations) {
        String identity = hashes.identity(job);
        String startHash = hashes.fingerprint(List.of("check-start-v1", identity));
        when(store.getCheckPublicationEffect(id, "check_start")).thenReturn(Optional.of(new JdbcStore.CheckPublicationEffect(startHash, "confirmed", 9L)));
        when(github.findReviewCheck(eq(job), eq(identity), any())).thenReturn(Optional.of(9L));
        String hash = hashes.fingerprint(List.of("check-result-v1", identity, 9L, "success", "title", text, annotations));
        when(store.getCheckPublicationEffect(id, "check_result")).thenReturn(Optional.of(new JdbcStore.CheckPublicationEffect(hash, "sent", null)));
        when(github.getReviewCheck(job, 9, identity)).thenReturn(json.createObjectNode().put("status", "completed").put("conclusion", "success")
                .set("output", json.createObjectNode().put("title", "title").put("summary", text + "\n\n<!-- " + identity + ":result:" + hash + " -->")
                        .put("annotations_count", annotations.size())));
        when(github.getReviewAnnotations(eq(job), eq(9L), any())).thenReturn(annotations);
    }

    @Test void verifiedResultsNeverAuthorizeRecoveryOrExposeRepositoryContent() throws Exception {
        result("private source excerpt", List.of());
        var report = inspector.inspect(id);
        assertEquals("verified_remote", report.operations().get(1).result());
        assertFalse(report.automaticRecoveryAllowed());
        assertFalse(json.writeValueAsString(report).contains("private source excerpt"));
        verifyNoWrites();
    }

    @Test void forgedResultMarkerWithChangedAnnotationContentDoesNotPass() {
        result("summary", List.of(new Models.Annotation("src/F.java", 1, 1, "warning", "title", "original", "details")));
        when(github.getReviewAnnotations(eq(job), eq(9L), any())).thenReturn(
                List.of(new Models.Annotation("src/F.java", 1, 1, "warning", "title", "tampered", "details")));
        assertEquals("content_conflict", inspector.inspect(id).operations().get(1).result());
        verifyNoWrites();
    }

    @Test void summaryBodyIsRehashedInsteadOfTrustingItsMarker() {
        String identity = hashes.identity(job);
        String hash = hashes.fingerprint(List.of("summary-comment-v1", identity, "original"));
        when(store.getCheckPublicationEffect(id, "summary_comment")).thenReturn(Optional.of(new JdbcStore.CheckPublicationEffect(hash, "sent", 11L)));
        when(github.findSummaryComment(eq(job), isNull(), any())).thenReturn(Optional.of(11L));
        when(github.getSummaryComment(eq(job), eq(11L), any())).thenReturn(json.createObjectNode().put("body",
                GitHubClient.SUMMARY_MARKER + "\n<!-- " + identity + ":summary:" + hash + " -->\nchanged"));
        assertEquals("content_conflict", inspector.inspect(id).operations().get(2).result());
        verifyNoWrites();
    }

    @Test void verifiedSummaryDoesNotReleaseItsPendingSlot() {
        String identity = hashes.identity(job);
        String hash = hashes.fingerprint(List.of("summary-comment-v1", identity, "original"));
        when(store.getCheckPublicationEffect(id, "summary_comment")).thenReturn(Optional.of(new JdbcStore.CheckPublicationEffect(hash, "sent", null)));
        when(github.findSummaryComment(eq(job), isNull(), any())).thenReturn(Optional.of(11L));
        when(github.getSummaryComment(eq(job), eq(11L), any())).thenReturn(json.createObjectNode().put("body",
                GitHubClient.SUMMARY_MARKER + "\n<!-- " + identity + ":summary:" + hash + " -->\noriginal"));
        var report = inspector.inspect(id);
        assertEquals("verified_remote", report.operations().get(2).result());
        assertTrue(report.holdsSummarySlot());
        verifyNoWrites();
    }

    @Test void networkFailureIsNotReportedAsAbsenceAndDoesNotLeakErrorBody() throws Exception {
        result("summary", List.of());
        when(github.findReviewCheck(eq(job), anyString(), any())).thenThrow(new IllegalStateException("private response"));
        var report = inspector.inspect(id);
        assertEquals("lookup_failed", report.operations().get(0).result());
        assertFalse(json.writeValueAsString(report).contains("private response"));
        verifyNoWrites();
    }

    @Test void mismatchedJobCannotChooseAnotherRepositoryForInspection() {
        when(store.getPublicationInspectionInput(id)).thenReturn(new JdbcStore.PublicationInspectionInput(
                new Models.ReviewJob("another", 1, "evil", "repo", 7, "base1234", "head1234"), "failed", "", false, false));
        assertThrows(IllegalArgumentException.class, () -> inspector.inspect(id));
        verifyNoInteractions(github);
    }

    private void frozenCandidate(String frozenBody) {
        result("original", List.of());
        String identity = hashes.identity(job);
        String hash = hashes.fingerprint(List.of("summary-comment-v1", identity, "original"));
        when(store.getCheckPublicationEffect(id, "summary_comment")).thenReturn(Optional.of(
                new JdbcStore.CheckPublicationEffect(hash, "sent", 11L)));
        when(github.findSummaryComment(eq(job), isNull(), any())).thenReturn(Optional.of(11L));
        when(github.getSummaryComment(eq(job), eq(11L), any())).thenReturn(json.createObjectNode().put("body",
                GitHubClient.SUMMARY_MARKER + "\n<!-- " + identity + ":summary:" + hash + " -->\noriginal"));
        when(config.frozenPublicationsEnabled()).thenReturn(true);
        when(config.publicationKey()).thenReturn(key);
        var sealed = new FrozenReviewOutput(1, job, 42, 9, "success", "title", frozenBody,
                List.of(), "{}").seal(key, json);
        when(store.getFrozenReviewOutput(id)).thenReturn(Optional.of(
                new JdbcStore.FrozenOutput(1, 1, sealed.hash(), sealed.ciphertext())));
    }

    private PublicationInspector.Report assessed() {
        var report = new PublicationInspector(store, github, json, config).inspect(id);
        assertFalse(report.automaticRecoveryAllowed());
        assertFalse(report.recovery().authorized());
        assertFalse(report.recovery().writesAllowed());
        verifyNoWrites();
        return report;
    }

    @Test void exactFrozenRemoteMatchIsOnlyAnUnauthorizedReadOnlyCandidate() throws Exception {
        frozenCandidate("original");
        var report = assessed();
        assertEquals("candidate_for_authorized_local_confirmation", report.recovery().status());
        assertEquals("verified", report.recovery().artifact());
        assertTrue(report.recovery().blockers().isEmpty());
        assertNotNull(report.observedAt());
        assertFalse(json.writeValueAsString(report).contains("original"));
    }

    @Test void VerifiedRemoteOutputDifferentFromFrozenIntentBlocksRecovery() {
        frozenCandidate("different original intent");
        var report = assessed();
        assertEquals("journal_conflict", report.recovery().artifact());
        assertTrue(report.recovery().blockers().contains("frozen_output_journal_conflict"));
    }

    @Test void WrongKeyCannotExposeOrReconstructTheFrozenOutput() throws Exception {
        frozenCandidate("private frozen excerpt");
        byte[] wrong = new byte[32]; wrong[0] = 1;
        when(config.publicationKey()).thenReturn(java.util.Base64.getEncoder().encodeToString(wrong));
        var report = assessed();
        assertEquals("unavailable", report.recovery().artifact());
        assertEquals("blocked", report.recovery().status());
        assertFalse(json.writeValueAsString(report).contains("private frozen excerpt"));
    }

    @Test void ChangedRevisionOrSupersededRunCannotBecomeCandidate() {
        frozenCandidate("original");
        when(github.currentRevision(1, "owner", "repo", 7)).thenReturn(new Models.PullRequestRevision("new-base", "head1234"));
        when(store.getPublicationInspectionInput(id)).thenReturn(new JdbcStore.PublicationInspectionInput(
                job, "failed", "PUBLICATION_UNCERTAIN", true, false));
        var report = assessed();
        assertTrue(report.recovery().blockers().contains("superseded_run"));
        assertTrue(report.recovery().blockers().contains("revision_not_verified_current"));
    }

    @Test void ProcessingQueueAndReleasedSlotBlockRecovery() {
        frozenCandidate("original");
        when(store.getPublicationInspectionInput(id)).thenReturn(new JdbcStore.PublicationInspectionInput(
                job, "processing", "PUBLICATION_UNCERTAIN", false, true));
        var report = assessed();
        assertTrue(report.recovery().blockers().contains("not_paused_uncertain_job"));
        assertTrue(report.recovery().blockers().contains("summary_slot_not_owned"));
    }

    @Test void MissingArtifactAndUnavailableRemoteRemainBlocked() {
        frozenCandidate("original");
        when(store.getFrozenReviewOutput(id)).thenReturn(Optional.empty());
        when(github.findSummaryComment(eq(job), isNull(), any())).thenThrow(new IllegalStateException("private remote error"));
        var report = assessed();
        assertTrue(report.recovery().blockers().contains("frozen_output_missing"));
        assertTrue(report.recovery().blockers().contains("remote_effects_not_fully_verified"));
    }

    @Test void LocalCheckIdConflictBlocksRecovery() {
        frozenCandidate("original");
        when(store.getPublication(id)).thenReturn(Optional.of(new Models.Publication(id, "head1234", 10L, 11L)));
        assertEquals("journal_conflict", assessed().recovery().artifact());
    }

    @Test void localSummaryPointerConflictBlocksRecovery() {
        frozenCandidate("original");
        when(store.getPublication(id)).thenReturn(Optional.of(new Models.Publication(id, "head1234", 9L, 99L)));
        assertEquals("journal_conflict", assessed().recovery().artifact());
    }

    @Test void disabledArtifactValidationNeverAdmitsAReadOnlyCandidate() {
        frozenCandidate("original");
        when(config.frozenPublicationsEnabled()).thenReturn(false);
        assertEquals("disabled", assessed().recovery().artifact());
    }

    @Test void historicalPipelineCannotUseTheNewRecoveryProtocol() {
        frozenCandidate("original");
        when(store.getReviewRun(id)).thenReturn(new Models.ReviewRun(id, 42, 7, "base1234", "head1234", "failed",
                "java.13", "test", "webhook", "automatic", null));
        assertTrue(assessed().recovery().blockers().contains("unsupported_pipeline"));
    }

    private void verifyNoWrites() {
        verify(store, never()).beginCheckPublication(anyString(), anyString(), anyString());
        verify(store, never()).confirmCheckPublication(anyString(), anyString(), anyString(), anyLong());
        verify(store, never()).beginSummaryPublication(anyString(), anyString(), any());
        verify(store, never()).claimSummaryPublication(any());
        verify(store, never()).releaseSummaryPublication(anyString());
        verify(store, never()).updateReviewRun(anyString(), anyString(), any(), anyString(), anyString());
        verify(store, never()).freezeReviewOutput(any(), anyLong(), anyString(), anyString());
        verify(store, never()).completeJob(any());
        verify(store, never()).retryJob(any(), anyString());
        verify(store, never()).stopUncertainPublication(any(), anyString());
        verify(github, never()).startCheck(anyLong(), anyString(), anyString(), anyString(), any(), anyString());
        verify(github, never()).completeCheck(anyLong(), anyString(), anyString(), anyLong(), anyString(), anyString(), anyString(), anyList());
        verify(github, never()).writeSummaryComment(any(), anyString(), any(), any());
    }
}
