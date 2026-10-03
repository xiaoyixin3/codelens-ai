package ai.codelens.semantic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Builds an evidence-bound reuse investigation directly from the production Java repository graph. */
public final class SemanticReusePlanner {
    private static final int MAX_CANDIDATES = 12;

    public Investigation investigate(SemanticModels.Index base, SemanticModels.Index head, Set<String> changedPaths) {
        Set<String> normalizedPaths = new LinkedHashSet<>();
        changedPaths.forEach(path -> normalizedPaths.add(normalize(path)));
        List<SemanticModels.Symbol> changedSymbols = head.symbols().stream()
                .filter(symbol -> normalizedPaths.contains(symbol.path()))
                .sorted(Comparator.comparing(SemanticModels.Symbol::stableKey))
                .toList();
        Set<String> changedKeys = changedSymbols.stream()
                .map(SemanticModels.Symbol::stableKey)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<SemanticModels.SymbolKind> changedKinds = changedSymbols.stream()
                .map(SemanticModels.Symbol::kind)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> changedNames = changedSymbols.stream()
                .map(symbol -> simpleName(symbol.qualifiedName()))
                .collect(java.util.stream.Collectors.toSet());
        Set<String> changedTokens = new LinkedHashSet<>();
        changedSymbols.forEach(symbol -> changedTokens.addAll(tokens(symbol.qualifiedName())));

        Map<String, List<IndexedRelationship>> direct = directRelationships(head, changedKeys);
        Map<String, List<IndexedRelationship>> sharedCallers = sharedCallerRelationships(head, changedKeys);
        Map<String, Candidate> candidates = new LinkedHashMap<>();
        head.symbols().stream()
                .filter(symbol -> !normalizedPaths.contains(symbol.path()))
                .filter(symbol -> !changedKeys.contains(symbol.stableKey()))
                .forEach(symbol -> candidate(head, symbol, changedKinds, changedNames, changedTokens,
                                direct.getOrDefault(symbol.stableKey(), List.of()),
                                sharedCallers.getOrDefault(symbol.stableKey(), List.of()))
                        .ifPresent(value -> candidates.merge(symbol.stableKey(), value,
                                (left, right) -> left.score() >= right.score() ? left : right)));

        List<Candidate> ranked = candidates.values().stream()
                .sorted(Comparator.comparingDouble(Candidate::score).reversed()
                        .thenComparing(Candidate::qualifiedName)
                        .thenComparing(Candidate::stableKey))
                .limit(MAX_CANDIDATES)
                .toList();
        List<String> limitations = new ArrayList<>(coverageLimitations(base, head));
        if (changedSymbols.isEmpty()) {
            limitations.add("No changed symbol exists in the head graph, so reuse search has no semantic anchor.");
        }
        limitations = List.copyOf(limitations);
        boolean complete = limitations.isEmpty();
        List<String> gateReasons = new ArrayList<>();
        gateReasons.add("No audited ReuseDecision and selected SolutionOption exist for this production review.");
        if (!complete) gateReasons.add("Semantic coverage is incomplete, so the system cannot prove that a new implementation is necessary.");

        return new Investigation(
                "reuse:" + head.commitSha(),
                new Provenance(base.commitSha(), head.commitSha(), head.adapterVersion(),
                        base.buildModelHash(), head.buildModelHash()),
                new SearchScope(List.copyOf(normalizedPaths),
                        Math.max(0, head.symbols().size() - changedSymbols.size()),
                        head.relationships().size(), complete, limitations),
                changedSymbols.stream().map(SemanticReusePlanner::symbolRef).toList(),
                ranked,
                new PatchGate(false, gateReasons));
    }

    private static java.util.Optional<Candidate> candidate(
            SemanticModels.Index head,
            SemanticModels.Symbol symbol,
            Set<SemanticModels.SymbolKind> changedKinds,
            Set<String> changedNames,
            Set<String> changedTokens,
            List<IndexedRelationship> direct,
            List<IndexedRelationship> sharedCallers
    ) {
        String relationship;
        String fit;
        double score;
        String rationale;
        if (symbol.testSource() && !direct.isEmpty()) {
            relationship = "test_fixture";
            fit = "extendable";
            score = .90;
            rationale = "An unchanged test symbol is directly connected to a changed symbol and should be extended before a new fixture is created.";
        } else if (direct.stream().anyMatch(item -> Set.of(SemanticModels.RelationType.IMPLEMENTS,
                SemanticModels.RelationType.EXTENDS, SemanticModels.RelationType.OVERRIDES)
                .contains(item.relationship().type()))) {
            relationship = "same_contract";
            fit = "direct";
            score = .96;
            rationale = "A resolved inheritance or override relationship connects this candidate to the changed contract.";
        } else if (changedNames.contains(simpleName(symbol.qualifiedName())) && changedKinds.contains(symbol.kind())) {
            relationship = "same_contract";
            fit = "direct";
            score = .95;
            rationale = "An unchanged symbol has the same simple name and semantic kind as a changed symbol; its existing contract must be checked first.";
        } else if (!sharedCallers.isEmpty() && changedKinds.contains(symbol.kind())) {
            relationship = "same_role";
            fit = "extendable";
            score = .84;
            rationale = "The candidate and a changed symbol are used by the same resolved caller and have the same semantic kind.";
        } else if (!sharedCallers.isEmpty()) {
            relationship = "caller_pattern";
            fit = "partial";
            score = .72;
            rationale = "A resolved caller already uses this candidate alongside the changed behavior, providing an in-repository usage pattern.";
        } else {
            int overlap = 0;
            for (String token : tokens(symbol.qualifiedName())) if (changedTokens.contains(token)) overlap++;
            if (overlap == 0 || !changedKinds.contains(symbol.kind())) return java.util.Optional.empty();
            relationship = "similar_logic";
            fit = "partial";
            score = Math.min(.69, .50 + overlap * .08);
            rationale = "The candidate has matching name tokens and semantic kind; this is recall-only evidence and does not prove reuse.";
        }

        List<Evidence> evidence = new ArrayList<>();
        evidence.add(new Evidence("symbol:head:" + symbol.stableKey(), "symbol", head.commitSha(), symbol.path(),
                symbol.startLine(), symbol.stableKey(), "", "", "", symbol.typeResolved()));
        for (IndexedRelationship item : direct) evidence.add(evidence(head, item));
        for (IndexedRelationship item : sharedCallers) evidence.add(evidence(head, item));
        return java.util.Optional.of(new Candidate(
                "candidate:" + head.commitSha() + ":" + symbol.stableKey(), symbol.stableKey(), symbol.qualifiedName(),
                symbol.path(), symbol.kind().name().toLowerCase(Locale.ROOT), relationship, fit, score,
                List.copyOf(new LinkedHashSet<>(evidence)), rationale));
    }

    private static Map<String, List<IndexedRelationship>> directRelationships(
            SemanticModels.Index index, Set<String> changedKeys
    ) {
        Map<String, List<IndexedRelationship>> result = new LinkedHashMap<>();
        for (int position = 0; position < index.relationships().size(); position++) {
            SemanticModels.Relationship edge = index.relationships().get(position);
            if (!edge.typeResolved()) continue;
            String candidate = changedKeys.contains(edge.fromStableKey()) ? edge.toStableKey()
                    : changedKeys.contains(edge.toStableKey()) ? edge.fromStableKey() : null;
            if (candidate != null && !changedKeys.contains(candidate)) {
                result.computeIfAbsent(candidate, ignored -> new ArrayList<>()).add(new IndexedRelationship(position, edge));
            }
        }
        return result;
    }

    private static Map<String, List<IndexedRelationship>> sharedCallerRelationships(
            SemanticModels.Index index, Set<String> changedKeys
    ) {
        Set<String> callers = new LinkedHashSet<>();
        for (SemanticModels.Relationship edge : index.relationships()) {
            if (edge.typeResolved() && edge.type() == SemanticModels.RelationType.CALLS
                    && changedKeys.contains(edge.toStableKey())) callers.add(edge.fromStableKey());
        }
        Map<String, List<IndexedRelationship>> callerAnchors = new LinkedHashMap<>();
        for (int position = 0; position < index.relationships().size(); position++) {
            SemanticModels.Relationship edge = index.relationships().get(position);
            if (edge.typeResolved() && edge.type() == SemanticModels.RelationType.CALLS
                    && changedKeys.contains(edge.toStableKey())) {
                callerAnchors.computeIfAbsent(edge.fromStableKey(), ignored -> new ArrayList<>())
                        .add(new IndexedRelationship(position, edge));
            }
        }
        Map<String, List<IndexedRelationship>> result = new LinkedHashMap<>();
        for (int position = 0; position < index.relationships().size(); position++) {
            SemanticModels.Relationship edge = index.relationships().get(position);
            if (edge.typeResolved() && edge.type() == SemanticModels.RelationType.CALLS
                    && callers.contains(edge.fromStableKey()) && !changedKeys.contains(edge.toStableKey())) {
                List<IndexedRelationship> evidence = result.computeIfAbsent(edge.toStableKey(), ignored -> new ArrayList<>());
                evidence.addAll(callerAnchors.getOrDefault(edge.fromStableKey(), List.of()));
                evidence.add(new IndexedRelationship(position, edge));
            }
        }
        return result;
    }

    private static Evidence evidence(SemanticModels.Index index, IndexedRelationship item) {
        SemanticModels.Relationship edge = item.relationship();
        return new Evidence("relationship:head:" + item.position(), "relationship", index.commitSha(),
                edge.sourcePath(), edge.sourceLine(), "", edge.type().name(), edge.fromStableKey(),
                edge.toStableKey(), edge.typeResolved());
    }

    private static List<String> coverageLimitations(SemanticModels.Index base, SemanticModels.Index head) {
        List<String> limitations = new ArrayList<>();
        addCoverageLimitations("base", base.coverage(), limitations);
        addCoverageLimitations("head", head.coverage(), limitations);
        if (!base.adapterVersion().equals(head.adapterVersion())) limitations.add("Base and head adapter versions differ.");
        return List.copyOf(limitations);
    }

    private static void addCoverageLimitations(String revision, SemanticModels.Coverage coverage, List<String> limitations) {
        if (coverage.level() != SemanticModels.CoverageLevel.SEMANTIC) limitations.add(revision + " index is not fully semantic.");
        if (coverage.indexedFiles() != coverage.eligibleFiles()) {
            limitations.add(revision + " indexed " + coverage.indexedFiles() + "/" + coverage.eligibleFiles() + " eligible files.");
        }
        if (coverage.failedFiles() > 0) limitations.add(revision + " has " + coverage.failedFiles() + " failed file(s).");
        if (coverage.skippedFiles() > 0) limitations.add(revision + " has " + coverage.skippedFiles() + " skipped file(s).");
        if (coverage.unresolvedRelationships() > 0) {
            limitations.add(revision + " has " + coverage.unresolvedRelationships() + " unresolved relationship(s).");
        }
    }

    private static SymbolRef symbolRef(SemanticModels.Symbol symbol) {
        return new SymbolRef(symbol.stableKey(), symbol.qualifiedName(), symbol.path(), symbol.startLine(),
                symbol.kind().name().toLowerCase(Locale.ROOT), symbol.typeResolved());
    }

    private static String normalize(String path) {
        return path.replace('\\', '/').replaceFirst("^\\./", "");
    }

    private static String simpleName(String value) {
        String[] parts = value.split("[.#:$]");
        return parts.length == 0 ? value.toLowerCase(Locale.ROOT) : parts[parts.length - 1].toLowerCase(Locale.ROOT);
    }

    private static Set<String> tokens(String value) {
        String separated = value.replaceAll("([a-z])([A-Z])", "$1 $2").toLowerCase(Locale.ROOT);
        Set<String> result = new LinkedHashSet<>();
        for (String token : separated.split("[^a-z0-9]+")) if (token.length() >= 3) result.add(token);
        return result;
    }

    private record IndexedRelationship(int position, SemanticModels.Relationship relationship) {}

    public record Provenance(String baseSha, String headSha, String adapterVersion,
                             String baseBuildModelHash, String headBuildModelHash) {}
    public record SearchScope(List<String> changedFiles, int searchedSymbols, int searchedRelationships,
                              boolean semanticCoverageComplete, List<String> limitations) {
        public SearchScope { changedFiles = List.copyOf(changedFiles); limitations = List.copyOf(limitations); }
    }
    public record SymbolRef(String stableKey, String qualifiedName, String path, int line, String kind,
                            boolean typeResolved) {}
    public record Evidence(String id, String kind, String commitSha, String path, int line, String stableKey,
                           String relationshipType, String fromStableKey, String toStableKey,
                           boolean typeResolved) {}
    public record Candidate(String id, String stableKey, String qualifiedName, String path, String kind,
                            String relationship, String fit, double score, List<Evidence> evidence,
                            String rationale) {
        public Candidate { evidence = List.copyOf(evidence); }
    }
    public record PatchGate(boolean allowed, List<String> reasons) {
        public PatchGate { reasons = List.copyOf(reasons); }
    }
    public record Investigation(String id, Provenance provenance, SearchScope searchScope,
                                List<SymbolRef> changedSymbols, List<Candidate> candidates,
                                PatchGate patchGate) {
        public Investigation {
            changedSymbols = List.copyOf(changedSymbols);
            candidates = List.copyOf(candidates);
        }
    }
}
