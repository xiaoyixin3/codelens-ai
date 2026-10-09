package ai.codelens.semantic;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Validates a human-approved reuse decision against the frozen production investigation. */
public final class ReuseDecisionService {
    private static final Set<String> STRATEGIES = Set.of("reuse", "extend", "extract", "new");

    public ValidatedDecision validate(Submission input, SemanticReusePlanner.Investigation investigation) {
        if (input == null || investigation == null) throw invalid("decision and investigation are required");
        if (input.expectedRevision() < 0) throw invalid("expectedRevision cannot be negative");
        requireEqual("investigationId", input.investigationId(), investigation.id());
        requireEqual("baseSha", input.baseSha(), investigation.provenance().baseSha());
        requireEqual("headSha", input.headSha(), investigation.provenance().headSha());
        requireEqual("adapterVersion", input.adapterVersion(), investigation.provenance().adapterVersion());
        requireEqual("baseBuildModelHash", input.baseBuildModelHash(), investigation.provenance().baseBuildModelHash());
        requireEqual("headBuildModelHash", input.headBuildModelHash(), investigation.provenance().headBuildModelHash());

        String goal = text(input.goal(), 8, 1_000, "goal");
        String strategy = text(input.decision(), 1, 20, "decision").toLowerCase(java.util.Locale.ROOT);
        if (!STRATEGIES.contains(strategy)) throw invalid("decision is unsupported");
        String justification = text(input.justification(), 12, 4_000, "justification");
        ChangeBudget budget = budget(input.changeBudget());
        Map<String, SemanticReusePlanner.Candidate> candidates = new LinkedHashMap<>();
        investigation.candidates().forEach(candidate -> candidates.put(candidate.id(), candidate));
        String selectedId = trim(input.selectedCandidateId());
        SemanticReusePlanner.Candidate selected = selectedId.isBlank() ? null : candidates.get(selectedId);
        Map<String, String> rejections = rejections(input.candidateRejections(), candidates.keySet());

        if (strategy.equals("new")) {
            if (!selectedId.isBlank()) throw invalid("new decisions cannot select a reuse candidate");
            if (!investigation.searchScope().semanticCoverageComplete()
                    || investigation.searchScope().searchedSymbols() == 0) {
                throw invalid("new implementation cannot be justified with incomplete or empty semantic search");
            }
            for (String candidateId : candidates.keySet()) {
                if (!rejections.containsKey(candidateId)) {
                    throw invalid("every candidate requires an explicit rejection before choosing new");
                }
            }
        } else {
            if (selected == null) throw invalid("reuse, extend, and extract decisions require a candidate from the frozen investigation");
            if (rejections.containsKey(selected.id())) throw invalid("the selected candidate cannot also be rejected");
        }

        SelectedOption option = option(input.option(), strategy, selected, budget, investigation);
        return new ValidatedDecision(investigation.id(), investigation.provenance(), goal, strategy,
                selected == null ? "" : selected.id(), rejections, justification, budget, option);
    }

    private static SelectedOption option(OptionSubmission input, String strategy,
                                         SemanticReusePlanner.Candidate selected, ChangeBudget budget,
                                         SemanticReusePlanner.Investigation investigation) {
        if (input == null) throw invalid("a selected solution option is required");
        String optionStrategy = text(input.strategy(), 1, 20, "option.strategy").toLowerCase(java.util.Locale.ROOT);
        if (!optionStrategy.equals(strategy)) throw invalid("solution option strategy must match the reuse decision");
        String optionCandidate = trim(input.candidateId());
        if (selected == null && !optionCandidate.isBlank()) throw invalid("new solution options cannot select a candidate");
        if (selected != null && !selected.id().equals(optionCandidate)) {
            throw invalid("solution option candidate must match the selected reuse candidate");
        }
        List<String> files = paths(input.expectedFiles(), budget.maxFiles());
        List<String> symbols = strings(input.expectedSymbols(), 0, budget.maxChangedSymbols(), 500, "option.expectedSymbols");
        List<String> verification = strings(input.verification(), 1, 20, 1_000, "option.verification");
        List<String> tradeoffs = strings(input.tradeoffs(), 1, 20, 1_000, "option.tradeoffs");
        List<String> evidenceIds = selected == null
                ? investigation.changedSymbols().stream().map(symbol -> "symbol:head:" + symbol.stableKey()).toList()
                : selected.evidence().stream().map(SemanticReusePlanner.Evidence::id).distinct().toList();
        if (evidenceIds.isEmpty()) throw invalid("solution option requires frozen semantic evidence");
        return new SelectedOption(optionStrategy, text(input.title(), 1, 240, "option.title"),
                text(input.summary(), 12, 2_000, "option.summary"), optionCandidate,
                files, symbols, verification, tradeoffs, evidenceIds);
    }

    private static ChangeBudget budget(ChangeBudget input) {
        if (input == null || input.maxFiles() < 1 || input.maxFiles() > 50
                || input.maxChangedSymbols() < 1 || input.maxChangedSymbols() > 200) {
            throw invalid("changeBudget limits are invalid");
        }
        return input;
    }

    private static Map<String, String> rejections(Map<String, String> input, Set<String> candidateIds) {
        Map<String, String> result = new LinkedHashMap<>();
        if (input == null) return Map.of();
        for (Map.Entry<String, String> entry : input.entrySet()) {
            String id = trim(entry.getKey());
            if (!candidateIds.contains(id)) throw invalid("candidate rejection references an unknown candidate");
            result.put(id, text(entry.getValue(), 12, 1_000, "candidate rejection"));
        }
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(result));
    }

    private static List<String> paths(List<String> values, int max) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        strings(values, 1, max, 1_000, "option.expectedFiles").stream()
                .map(value -> value.replace('\\', '/')).forEach(normalized::add);
        List<String> paths = List.copyOf(normalized);
        for (String path : paths) {
            List<String> segments = List.of(path.split("/", -1));
            if (path.startsWith("/") || path.matches("^[A-Za-z]:.*")
                    || segments.stream().anyMatch(segment -> segment.isBlank() || segment.equals(".") || segment.equals(".."))) {
                throw invalid("solution option contains an unsafe repository path");
            }
        }
        if (paths.size() > max) throw invalid("option.expectedFiles count is invalid");
        return paths;
    }

    private static List<String> strings(List<String> values, int min, int max, int maxLength, String name) {
        if (values == null) values = List.of();
        List<String> result = new ArrayList<>();
        for (String value : values) result.add(text(value, 1, maxLength, name));
        result = new ArrayList<>(new LinkedHashSet<>(result));
        if (result.size() < min || result.size() > max) throw invalid(name + " count is invalid");
        return List.copyOf(result);
    }

    private static String text(String value, int min, int max, String name) {
        String normalized = trim(value);
        if (normalized.length() < min || normalized.length() > max) throw invalid(name + " length is invalid");
        return normalized;
    }

    private static String trim(String value) { return value == null ? "" : value.trim(); }
    private static void requireEqual(String field, String supplied, String expected) {
        if (!trim(supplied).equals(expected)) throw invalid(field + " does not match the frozen investigation");
    }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }

    public record ChangeBudget(int maxFiles, int maxChangedSymbols, boolean publicContractChangeAllowed) {}
    public record OptionSubmission(String strategy, String title, String summary, String candidateId,
                                   List<String> expectedFiles, List<String> expectedSymbols,
                                   List<String> verification, List<String> tradeoffs) {}
    public record Submission(int expectedRevision, String investigationId, String baseSha, String headSha, String adapterVersion,
                             String baseBuildModelHash, String headBuildModelHash, String goal, String decision,
                             String selectedCandidateId, Map<String, String> candidateRejections,
                             String justification, ChangeBudget changeBudget, OptionSubmission option) {}
    public record SelectedOption(String strategy, String title, String summary, String candidateId,
                                 List<String> expectedFiles, List<String> expectedSymbols,
                                 List<String> verification, List<String> tradeoffs, List<String> evidenceIds) {
        public SelectedOption {
            expectedFiles = List.copyOf(expectedFiles); expectedSymbols = List.copyOf(expectedSymbols);
            verification = List.copyOf(verification); tradeoffs = List.copyOf(tradeoffs);
            evidenceIds = List.copyOf(evidenceIds);
        }
    }
    public record ValidatedDecision(String investigationId, SemanticReusePlanner.Provenance provenance,
                                    String goal, String decision, String selectedCandidateId,
                                    Map<String, String> candidateRejections, String justification,
                                    ChangeBudget changeBudget, SelectedOption selectedOption) {
        public ValidatedDecision {
            candidateRejections = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(candidateRejections));
        }
    }
}
