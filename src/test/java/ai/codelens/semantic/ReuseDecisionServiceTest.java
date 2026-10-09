package ai.codelens.semantic;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReuseDecisionServiceTest {
    private final ReuseDecisionService service = new ReuseDecisionService();

    @Test
    void acceptsAProvenanceBoundReuseDecisionAndDerivesFrozenEvidence() {
        SemanticReusePlanner.Investigation investigation = investigation(true, true);
        SemanticReusePlanner.Candidate candidate = investigation.candidates().get(0);
        ReuseDecisionService.Submission input = submission(investigation, "reuse", candidate.id(), Map.of(),
                new ReuseDecisionService.OptionSubmission("reuse", "Reuse existing helper",
                        "Call the existing helper through its current contract.", candidate.id(),
                        List.of(candidate.path(), "src/main/java/example/Target.java"),
                        List.of(candidate.qualifiedName()), List.of("Run the affected unit tests."),
                        List.of("The existing helper contract remains unchanged.")));

        ReuseDecisionService.ValidatedDecision result = service.validate(input, investigation);

        assertEquals(candidate.id(), result.selectedCandidateId());
        assertEquals("reuse", result.selectedOption().strategy());
        assertTrue(result.selectedOption().evidenceIds().contains(candidate.evidence().get(0).id()));
    }

    @Test
    void rejectsForgedProvenanceUnknownCandidatesAndUnsafePaths() {
        SemanticReusePlanner.Investigation investigation = investigation(true, true);
        ReuseDecisionService.Submission valid = submission(investigation, "reuse", investigation.candidates().get(0).id(), Map.of(),
                option("reuse", investigation.candidates().get(0).id(), List.of("src/main/java/example/Target.java")));
        ReuseDecisionService.Submission forged = new ReuseDecisionService.Submission(valid.expectedRevision(), valid.investigationId(), valid.baseSha(),
                "cccccccccccccccccccccccccccccccccccccccc", valid.adapterVersion(), valid.baseBuildModelHash(),
                valid.headBuildModelHash(), valid.goal(), valid.decision(), valid.selectedCandidateId(),
                valid.candidateRejections(), valid.justification(), valid.changeBudget(), valid.option());
        assertThrows(IllegalArgumentException.class, () -> service.validate(forged, investigation));

        ReuseDecisionService.Submission unknown = submission(investigation, "reuse", "candidate:forged", Map.of(),
                option("reuse", "candidate:forged", List.of("src/main/java/example/Target.java")));
        assertThrows(IllegalArgumentException.class, () -> service.validate(unknown, investigation));

        ReuseDecisionService.Submission unsafe = submission(investigation, "reuse", investigation.candidates().get(0).id(), Map.of(),
                option("reuse", investigation.candidates().get(0).id(), List.of("../outside.java")));
        assertThrows(IllegalArgumentException.class, () -> service.validate(unsafe, investigation));
    }

    @Test
    void newRequiresCompleteSearchAndAnExplicitReasonForEveryCandidate() {
        SemanticReusePlanner.Investigation complete = investigation(true, true);
        ReuseDecisionService.Submission missingRejection = submission(complete, "new", "", Map.of(),
                option("new", "", List.of("src/main/java/example/NewHelper.java")));
        assertThrows(IllegalArgumentException.class, () -> service.validate(missingRejection, complete));

        String candidateId = complete.candidates().get(0).id();
        ReuseDecisionService.Submission accepted = submission(complete, "new", "",
                Map.of(candidateId, "The existing helper has an incompatible return contract."),
                option("new", "", List.of("src/main/java/example/NewHelper.java")));
        assertEquals("new", service.validate(accepted, complete).decision());

        SemanticReusePlanner.Investigation incomplete = investigation(false, true);
        ReuseDecisionService.Submission blocked = submission(incomplete, "new", "",
                Map.of(incomplete.candidates().get(0).id(), "The candidate cannot satisfy the required contract."),
                option("new", "", List.of("src/main/java/example/NewHelper.java")));
        assertThrows(IllegalArgumentException.class, () -> service.validate(blocked, incomplete));
    }

    private static ReuseDecisionService.Submission submission(SemanticReusePlanner.Investigation investigation,
                                                               String decision, String candidate,
                                                               Map<String, String> rejections,
                                                               ReuseDecisionService.OptionSubmission option) {
        var provenance = investigation.provenance();
        return new ReuseDecisionService.Submission(0, investigation.id(), provenance.baseSha(), provenance.headSha(),
                provenance.adapterVersion(), provenance.baseBuildModelHash(), provenance.headBuildModelHash(),
                "Implement the behavior with the smallest repository-native change", decision, candidate, rejections,
                "The selected approach follows the frozen repository evidence.",
                new ReuseDecisionService.ChangeBudget(3, 8, false), option);
    }

    private static ReuseDecisionService.OptionSubmission option(String strategy, String candidate, List<String> files) {
        return new ReuseDecisionService.OptionSubmission(strategy, "Selected implementation approach",
                "Apply a bounded change that preserves the existing public contract.", candidate, files, List.of(),
                List.of("Run the directly affected tests."), List.of("No public contract change is allowed."));
    }

    private static SemanticReusePlanner.Investigation investigation(boolean complete, boolean withCandidate) {
        String head = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
        SemanticReusePlanner.Evidence evidence = new SemanticReusePlanner.Evidence("symbol:head:helper", "symbol", head,
                "src/main/java/example/Helper.java", 3, "method:Helper.run", "", "", "", true);
        SemanticReusePlanner.Candidate candidate = new SemanticReusePlanner.Candidate("candidate:helper", "method:Helper.run",
                "example.Helper.run", "src/main/java/example/Helper.java", "method", "same_role", "extendable", .84,
                List.of(evidence), "Shared resolved caller");
        return new SemanticReusePlanner.Investigation("reuse:" + head,
                new SemanticReusePlanner.Provenance("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", head,
                        "java-semantic-v1", "base-build", "head-build"),
                new SemanticReusePlanner.SearchScope(List.of("src/main/java/example/Target.java"), 12, 20, complete,
                        complete ? List.of() : List.of("head has unresolved relationships")),
                List.of(new SemanticReusePlanner.SymbolRef("method:Target.run", "example.Target.run",
                        "src/main/java/example/Target.java", 3, "method", true)),
                withCandidate ? List.of(candidate) : List.of(),
                new SemanticReusePlanner.PatchGate(false, List.of("decision missing")));
    }
}
