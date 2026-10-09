package ai.codelens.review;

import ai.codelens.contracts.Models;
import ai.codelens.store.PublicationRecoveryAuditStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.Base64;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RecoveryApprovalServiceTest {
    static final String BEARER = encoded(1), KEY = encoded(2);
    static String encoded(int value) { byte[] bytes = new byte[32]; bytes[0]=(byte)value; return Base64.getEncoder().encodeToString(bytes); }
    final RecoveryOperatorAuthenticator operators = new RecoveryOperatorAuthenticator("fixture-operator", BEARER);
    final RecoveryOperatorAuthenticator.Principal principal = operators.authenticate(BEARER);
    final MutableClock clock = new MutableClock();
    final RecoveryApprovalService service = new RecoveryApprovalService(operators, KEY, new ObjectMapper(), clock);
    final UUID run = UUID.randomUUID(), job = UUID.randomUUID();
    RecoveryApprovalService.Binding binding(long generation, String head) {
        return new RecoveryApprovalService.Binding(run, job, 1, 42, 7, "owner", "repo", "b".repeat(40), head,
                Models.PIPELINE_VERSION, "a".repeat(64), "b".repeat(64), "c".repeat(64), "d".repeat(64), 9, 11, generation, run, 9L, 11L);
    }
    @Test void validBoundApprovalIsOpaqueAndProducesOnlyBoundedAuditMetadata() {
        var binding = binding(1, "a".repeat(40)); var issued = service.issue(principal, binding);
        assertFalse(issued.bearer().contains("owner"));
        assertFalse(issued.toString().contains(issued.bearer()));
        var audit = mock(PublicationRecoveryAuditStore.class);
        var attempt = service.reserveAttempt(service.verify(principal, binding, issued.bearer()), audit);
        assertEquals(run, attempt.runId()); assertEquals(principal.actorHash(), attempt.actorHash());
        assertTrue(attempt.evidenceHash().matches("[0-9a-f]{64}")); verify(audit).requested(attempt);
    }
    @Test void wrongCredentialAndManuallyMintedForeignPrincipalAreRejected() {
        assertThrows(SecurityException.class, () -> operators.authenticate("typed operator name"));
        var foreign = new RecoveryOperatorAuthenticator("fixture-operator", encoded(3)).authenticate(encoded(3));
        assertThrows(SecurityException.class, () -> service.issue(foreign, binding(1,"a".repeat(40))));
        assertThrows(SecurityException.class, () -> service.issue(null, binding(1,"a".repeat(40))));
    }
    @Test void rotatedKeyAndTamperingDoNotExposeTheBearerOrInnerError() {
        var binding = binding(1,"a".repeat(40)); var issued = service.issue(principal,binding);
        var other = new RecoveryApprovalService(operators, encoded(4), new ObjectMapper(), clock);
        var error = assertThrows(SecurityException.class, () -> other.verify(principal,binding,issued.bearer()));
        assertNull(error.getCause()); assertFalse(error.getMessage().contains(issued.bearer()));
        byte[] bytes=Base64.getDecoder().decode(issued.bearer()); bytes[bytes.length-1]^=1;
        assertThrows(SecurityException.class, () -> service.verify(principal,binding,Base64.getEncoder().encodeToString(bytes)));
    }
    @Test void changedRevisionAndLeaseGenerationInvalidateApproval() {
        var binding = binding(1,"a".repeat(40)); var issued=service.issue(principal,binding);
        assertThrows(SecurityException.class, () -> service.verify(principal,binding(2,"a".repeat(40)),issued.bearer()));
        assertThrows(SecurityException.class, () -> service.verify(principal,binding(1,"c".repeat(40)),issued.bearer()));
    }
    @Test void everyCriticalIdentityAndJournalFieldIsBound() throws Exception {
        var mapper = new ObjectMapper(); var original=binding(1,"a".repeat(40)); var issued=service.issue(principal,original);
        var replacements=java.util.Map.ofEntries(
            java.util.Map.entry("jobId",UUID.randomUUID().toString()), java.util.Map.entry("installationId",2),
            java.util.Map.entry("repositoryId",43), java.util.Map.entry("pullNumber",8),
            java.util.Map.entry("owner","other-owner"), java.util.Map.entry("repository","other-repo"),
            java.util.Map.entry("outputHash","e".repeat(64)), java.util.Map.entry("startHash","e".repeat(64)),
            java.util.Map.entry("resultHash","e".repeat(64)), java.util.Map.entry("summaryHash","e".repeat(64)),
            java.util.Map.entry("checkId",10), java.util.Map.entry("commentId",12));
        for(var entry: replacements.entrySet()) {
            com.fasterxml.jackson.databind.node.ObjectNode tree=mapper.valueToTree(original);
            tree.set(entry.getKey(),mapper.valueToTree(entry.getValue()));
            if(entry.getKey().equals("checkId")) tree.put("localCheckId",10);
            if(entry.getKey().equals("commentId")) tree.put("localCommentId",12);
            var changed=mapper.treeToValue(tree,RecoveryApprovalService.Binding.class);
            assertThrows(SecurityException.class, () -> service.verify(principal,changed,issued.bearer()),entry.getKey());
        }
    }
    @Test void expiryAndClockRollbackInvalidateApprovalAndItsVerifiedReceipt() {
        var binding=binding(1,"a".repeat(40)); var issued=service.issue(principal,binding);
        var verified=service.verify(principal,binding,issued.bearer());
        clock.now=issued.expiresAtMillis();
        assertThrows(SecurityException.class, () -> service.verify(principal,binding,issued.bearer()));
        var audit=mock(PublicationRecoveryAuditStore.class);
        assertThrows(SecurityException.class, () -> service.reserveAttempt(verified,audit)); verifyNoInteractions(audit);
        clock.now=1;
        assertThrows(SecurityException.class, () -> service.verify(principal,binding,issued.bearer()));
    }
    @Test void identityDigestChangesAcrossConfiguredOperatorsAndCannotCrossUseApproval() {
        var otherOperators=new RecoveryOperatorAuthenticator("another-operator", BEARER);
        var otherPrincipal=otherOperators.authenticate(BEARER);
        assertNotEquals(principal.actorHash(),otherPrincipal.actorHash());
        var other=new RecoveryApprovalService(otherOperators,KEY,new ObjectMapper(),clock);
        var binding=binding(1,"a".repeat(40)); var issued=service.issue(principal,binding);
        assertThrows(SecurityException.class, () -> other.verify(otherPrincipal,binding,issued.bearer()));
    }
    @Test void auditFailurePropagatesRatherThanApprovingRecovery() {
        var binding=binding(1,"a".repeat(40)); var issued=service.issue(principal,binding);
        var verified=service.verify(principal,binding,issued.bearer());
        var audit=mock(PublicationRecoveryAuditStore.class);
        doThrow(new IllegalStateException("unavailable")).when(audit).requested(any());
        assertThrows(IllegalStateException.class, () -> service.reserveAttempt(verified,audit));
    }
    @Test void invalidBindingAndReusedOperatorKeyAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new RecoveryApprovalService(operators,BEARER,new ObjectMapper(),clock));
        assertThrows(IllegalArgumentException.class, () -> binding(0,"a".repeat(40)));
        assertThrows(IllegalArgumentException.class, () -> binding(1,"not-a-revision"));
    }
    static final class MutableClock extends Clock {
        long now=1_800_000_000_000L;
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return Instant.ofEpochMilli(now); }
    }
}
