package ai.codelens.semantic;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SemanticReusePlannerTest {
    private static final String BASE = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String HEAD = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final String CHANGED_PATH = "src/main/java/example/Target.java";

    @Test
    void retrievesEvidenceBoundCandidatesFromTheWholeRepositoryHeadGraph() {
        SemanticModels.Symbol changed = symbol("method:Target.value", "example.Target.value", CHANGED_PATH, false);
        SemanticModels.Symbol contract = symbol("method:Legacy.value", "example.Legacy.value",
                "src/main/java/example/Legacy.java", false);
        SemanticModels.Symbol caller = symbol("method:Caller.call", "example.Caller.call",
                "src/main/java/example/Caller.java", false);
        SemanticModels.Symbol helper = symbol("method:Helper.convert", "example.Helper.convert",
                "src/main/java/example/Helper.java", false);
        SemanticModels.Symbol test = symbol("method:TargetTest.verify", "example.TargetTest.verify",
                "src/test/java/example/TargetTest.java", true);
        List<SemanticModels.Relationship> edges = List.of(
                relationship(caller, changed, SemanticModels.RelationType.CALLS, caller.path(), 4),
                relationship(caller, helper, SemanticModels.RelationType.CALLS, caller.path(), 5),
                relationship(test, changed, SemanticModels.RelationType.TESTS, test.path(), 6));
        SemanticModels.Index base = index(BASE, List.of(changed, contract, caller, helper, test), edges, 0);
        SemanticModels.Index head = index(HEAD, List.of(changed, contract, caller, helper, test), edges, 0);

        SemanticReusePlanner.Investigation result = new SemanticReusePlanner()
                .investigate(base, head, Set.of(CHANGED_PATH));

        assertTrue(result.searchScope().semanticCoverageComplete());
        assertEquals(4, result.searchScope().searchedSymbols());
        assertEquals(3, result.searchScope().searchedRelationships());
        assertEquals(HEAD, result.provenance().headSha());
        assertEquals("same_contract", result.candidates().get(0).relationship());
        assertTrue(result.candidates().stream().anyMatch(candidate -> candidate.relationship().equals("test_fixture")
                && candidate.evidence().stream().anyMatch(evidence -> evidence.relationshipType().equals("TESTS")
                        && evidence.commitSha().equals(HEAD))));
        assertTrue(result.candidates().stream().anyMatch(candidate -> candidate.stableKey().equals(helper.stableKey())
                && candidate.relationship().equals("same_role")
                && candidate.evidence().stream().filter(evidence -> evidence.kind().equals("relationship")).count() == 2
                && candidate.evidence().stream().anyMatch(evidence -> evidence.toStableKey().equals(changed.stableKey()))
                && candidate.evidence().stream().anyMatch(evidence -> evidence.toStableKey().equals(helper.stableKey()))));
        assertFalse(result.patchGate().allowed());
        assertEquals(1, result.patchGate().reasons().size());
    }

    @Test
    void incompleteResolvedCoverageBlocksAnyNewImplementationProof() {
        SemanticModels.Symbol changed = symbol("method:Target.value", "example.Target.value", CHANGED_PATH, false);
        SemanticModels.Symbol similar = symbol("method:Alternative.value", "example.Alternative.value",
                "src/main/java/example/Alternative.java", false);
        SemanticModels.Index base = index(BASE, List.of(changed, similar), List.of(), 0);
        SemanticModels.Index head = index(HEAD, List.of(changed, similar), List.of(), 2);

        SemanticReusePlanner.Investigation result = new SemanticReusePlanner()
                .investigate(base, head, Set.of(CHANGED_PATH));

        assertFalse(result.searchScope().semanticCoverageComplete());
        assertTrue(result.searchScope().limitations().stream().anyMatch(value -> value.contains("unresolved")));
        assertTrue(result.patchGate().reasons().stream().anyMatch(value -> value.contains("cannot prove")));
    }

    @Test
    void cannotClaimCompleteReuseCoverageWithoutAChangedSemanticAnchor() {
        SemanticModels.Symbol unchanged = symbol("method:Existing.run", "example.Existing.run",
                "src/main/java/example/Existing.java", false);
        SemanticModels.Index base = index(BASE, List.of(unchanged), List.of(), 0);
        SemanticModels.Index head = index(HEAD, List.of(unchanged), List.of(), 0);

        SemanticReusePlanner.Investigation result = new SemanticReusePlanner()
                .investigate(base, head, Set.of("pom.xml"));

        assertFalse(result.searchScope().semanticCoverageComplete());
        assertTrue(result.searchScope().limitations().stream().anyMatch(value -> value.contains("no semantic anchor")));
    }

    private static SemanticModels.Symbol symbol(String key, String name, String path, boolean test) {
        return new SemanticModels.Symbol(key, SemanticModels.SymbolKind.METHOD, name, name + "()", path,
                3, 5, test, true);
    }

    private static SemanticModels.Relationship relationship(SemanticModels.Symbol from, SemanticModels.Symbol to,
                                                             SemanticModels.RelationType type, String path, int line) {
        return new SemanticModels.Relationship(from.stableKey(), to.stableKey(), type, path, line, .99, true);
    }

    private static SemanticModels.Index index(String sha, List<SemanticModels.Symbol> symbols,
                                               List<SemanticModels.Relationship> relationships, int unresolved) {
        List<SemanticModels.FileStatus> files = symbols.stream().map(SemanticModels.Symbol::path).distinct()
                .map(path -> new SemanticModels.FileStatus(path, "indexed", "", "hash", false)).toList();
        return new SemanticModels.Index(sha, "java-semantic-test-v1", "build-model-test", files, symbols,
                relationships, new SemanticModels.Coverage(SemanticModels.CoverageLevel.SEMANTIC,
                files.size(), files.size(), 0, 0, 0, relationships.size(), unresolved, Map.of()));
    }
}
