package ai.codelens.semantic;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SemanticTruthSetEvaluatorTest {
    private static final String SHA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String ADAPTER = "adapter-v1";
    private static final String PATH = "src/main/java/example/Caller.java";
    private static final SemanticTruthSetEvaluator.CallFact CALL = new SemanticTruthSetEvaluator.CallFact(
            PATH, 10, "java:method:example.Caller#run()", "java:method:example.Target#execute()"
    );

    @Test
    void evaluatesOnlyResolvedCallsInsideTheFrozenScope() {
        SemanticTruthSetEvaluator evaluator = new SemanticTruthSetEvaluator();
        SemanticTruthSetEvaluator.Evaluation result = evaluator.evaluate(dataset("owner/one", List.of(CALL), List.of(CALL), "reviewer-c", 0),
                index(List.of(
                        relationship(CALL, true),
                        new SemanticModels.Relationship(CALL.fromStableKey(), "java:method:other.Target#execute()",
                                SemanticModels.RelationType.CALLS, PATH, 11, 1.0, true),
                        new SemanticModels.Relationship(CALL.fromStableKey(), CALL.toStableKey(),
                                SemanticModels.RelationType.CALLS, PATH, 12, 0.4, false)
                )));

        assertEquals(1, result.expected());
        assertEquals(1, result.actual());
        assertEquals(1.0, result.precision());
        assertEquals(1.0, result.recall());
        assertTrue(result.falsePositives().isEmpty());
        assertTrue(result.falseNegatives().isEmpty());
    }

    @Test
    void rejectsPredictionExposureAndRequiresThirdPersonConflictAdjudication() {
        SemanticTruthSetEvaluator evaluator = new SemanticTruthSetEvaluator();
        var base = dataset("owner/one", List.of(CALL), List.of(CALL), "reviewer-c", 0);
        var exposed = new SemanticTruthSetEvaluator.Dataset(base.repository(), base.commitSha(), base.license(),
                base.adapterVersion(), base.scope(), List.of(
                new SemanticTruthSetEvaluator.BlindReview("reviewer-a", Instant.parse("2026-09-25T01:00:00Z"), true, List.of(CALL)),
                base.blindReviews().get(1)), base.adjudication());
        assertThrows(IllegalArgumentException.class, () -> evaluator.evaluate(exposed, index(List.of(relationship(CALL, true)))));

        var conflict = dataset("owner/one", List.of(CALL), List.of(), "reviewer-a", 1);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> evaluator.evaluate(conflict, index(List.of(relationship(CALL, true)))));
        assertTrue(error.getMessage().contains("third reviewer"));
    }

    @Test
    void phaseGateFailsClosedForOneRepositoryOrAnyRepositoryBelowNinetyPercent() {
        SemanticTruthSetEvaluator evaluator = new SemanticTruthSetEvaluator();
        var perfect = evaluator.evaluate(dataset("owner/one", List.of(CALL), List.of(CALL), "reviewer-c", 0),
                index(List.of(relationship(CALL, true))));
        SemanticTruthSetEvaluator.Gate oneRepository = evaluator.gate(List.of(perfect));
        assertFalse(oneRepository.passed());
        assertTrue(oneRepository.failures().stream().anyMatch(value -> value.contains("repositories 1 < 2")));

        var falsePositive = new SemanticTruthSetEvaluator.CallFact(
                PATH, 11, CALL.fromStableKey(), "java:method:example.Target#wrong()"
        );
        var weak = evaluator.evaluate(dataset("owner/two", List.of(CALL), List.of(CALL), "reviewer-c", 0),
                index(List.of(relationship(CALL, true), relationship(falsePositive, true))));
        SemanticTruthSetEvaluator.Gate combined = evaluator.gate(List.of(perfect, weak));
        assertFalse(combined.passed());
        assertEquals(2, combined.repositories());
        assertTrue(combined.failures().stream().anyMatch(value -> value.contains("owner/two")));
    }

    private static SemanticTruthSetEvaluator.Dataset dataset(
            String repository,
            List<SemanticTruthSetEvaluator.CallFact> first,
            List<SemanticTruthSetEvaluator.CallFact> second,
            String adjudicator,
            int conflicts
    ) {
        Instant time = Instant.parse("2026-09-25T01:00:00Z");
        return new SemanticTruthSetEvaluator.Dataset(repository, SHA, "Apache-2.0", ADAPTER,
                new SemanticTruthSetEvaluator.Scope(Set.of(PATH), List.of("java:method:example.", "java:constructor:example.")),
                List.of(
                        new SemanticTruthSetEvaluator.BlindReview("reviewer-a", time, false, first),
                        new SemanticTruthSetEvaluator.BlindReview("reviewer-b", time.plusSeconds(60), false, second)
                ),
                new SemanticTruthSetEvaluator.Adjudication(adjudicator, time.plusSeconds(120), conflicts, List.of(CALL)));
    }

    private static SemanticModels.Index index(List<SemanticModels.Relationship> relationships) {
        long resolved = relationships.stream().filter(SemanticModels.Relationship::typeResolved).count();
        return new SemanticModels.Index(SHA, ADAPTER, "build-v1", List.of(), List.of(), relationships,
                new SemanticModels.Coverage(SemanticModels.CoverageLevel.SEMANTIC, 1, 1, 0, 0, 0,
                        Math.toIntExact(resolved), relationships.size() - Math.toIntExact(resolved), Map.of()));
    }

    private static SemanticModels.Relationship relationship(SemanticTruthSetEvaluator.CallFact call, boolean resolved) {
        return new SemanticModels.Relationship(call.fromStableKey(), call.toStableKey(), SemanticModels.RelationType.CALLS,
                call.sourcePath(), call.line(), resolved ? 1.0 : 0.4, resolved);
    }
}
