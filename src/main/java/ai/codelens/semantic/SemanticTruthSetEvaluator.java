package ai.codelens.semantic;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Evaluates independently labelled direct-call truth sets without altering the semantic adapter. */
public final class SemanticTruthSetEvaluator {
    public static final double DEFAULT_MIN_PRECISION = 0.90;
    public static final int DEFAULT_MIN_REPOSITORIES = 2;

    public Evaluation evaluate(Dataset dataset, SemanticModels.Index index) {
        validate(dataset);
        if (!dataset.commitSha().equals(index.commitSha())) {
            throw new IllegalArgumentException("Truth-set commit does not match semantic index provenance");
        }
        if (!dataset.adapterVersion().equals(index.adapterVersion())) {
            throw new IllegalArgumentException("Truth-set adapter does not match semantic index provenance");
        }

        Set<CallFact> expected = Set.copyOf(dataset.adjudication().calls());
        Set<CallFact> actual = index.relationships().stream()
                .filter(edge -> edge.type() == SemanticModels.RelationType.CALLS && edge.typeResolved())
                .filter(edge -> dataset.scope().sourcePaths().contains(edge.sourcePath()))
                .filter(edge -> dataset.scope().targetPrefixes().stream().anyMatch(edge.toStableKey()::startsWith))
                .map(edge -> new CallFact(edge.sourcePath(), edge.sourceLine(), edge.fromStableKey(), edge.toStableKey()))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<CallFact> truePositives = intersection(actual, expected);
        Set<CallFact> falsePositives = difference(actual, expected);
        Set<CallFact> falseNegatives = difference(expected, actual);
        double precision = actual.isEmpty() ? 0.0 : (double) truePositives.size() / actual.size();
        double recall = (double) truePositives.size() / expected.size();
        return new Evaluation(dataset.repository(), dataset.commitSha(), dataset.adapterVersion(), expected.size(), actual.size(),
                truePositives.size(), precision, recall, falsePositives, falseNegatives);
    }

    public Gate gate(List<Evaluation> evaluations) {
        return gate(evaluations, DEFAULT_MIN_REPOSITORIES, DEFAULT_MIN_PRECISION);
    }

    public Gate gate(List<Evaluation> evaluations, int minRepositories, double minPrecision) {
        if (minRepositories < 2) throw new IllegalArgumentException("At least two repositories are required");
        if (!Double.isFinite(minPrecision) || minPrecision <= 0 || minPrecision > 1) {
            throw new IllegalArgumentException("Minimum precision must be in (0, 1]");
        }
        List<String> failures = new ArrayList<>();
        Set<String> repositories = new HashSet<>();
        int expected = 0;
        int actual = 0;
        int correct = 0;
        for (Evaluation evaluation : evaluations) {
            if (!repositories.add(evaluation.repository().toLowerCase(Locale.ROOT))) {
                failures.add("repository appears more than once: " + evaluation.repository());
            }
            expected += evaluation.expected();
            actual += evaluation.actual();
            correct += evaluation.correct();
            if (evaluation.precision() < minPrecision) {
                failures.add("repository precision %.4f < %.4f: %s"
                        .formatted(evaluation.precision(), minPrecision, evaluation.repository()));
            }
        }
        if (repositories.size() < minRepositories) {
            failures.add("repositories %d < %d".formatted(repositories.size(), minRepositories));
        }
        double precision = actual == 0 ? 0.0 : (double) correct / actual;
        double recall = expected == 0 ? 0.0 : (double) correct / expected;
        if (precision < minPrecision) {
            failures.add("aggregate precision %.4f < %.4f".formatted(precision, minPrecision));
        }
        return new Gate(failures.isEmpty(), repositories.size(), expected, actual, correct,
                precision, recall, minRepositories, minPrecision, failures);
    }

    private static void validate(Dataset dataset) {
        if (dataset.repository() == null || !dataset.repository().matches("[^/\\s]+/[^/\\s]+")) {
            throw new IllegalArgumentException("Repository must be owner/name");
        }
        if (dataset.commitSha() == null || !dataset.commitSha().matches("[0-9a-fA-F]{40}")) {
            throw new IllegalArgumentException("Truth-set commit must be a full SHA");
        }
        if (blank(dataset.license()) || blank(dataset.adapterVersion())) {
            throw new IllegalArgumentException("License and adapter version are required");
        }
        if (dataset.scope() == null || dataset.scope().sourcePaths().isEmpty()
                || dataset.scope().targetPrefixes().isEmpty()) {
            throw new IllegalArgumentException("Truth-set scope must include source paths and target prefixes");
        }
        if (dataset.scope().sourcePaths().stream().anyMatch(SemanticTruthSetEvaluator::unsafeRelativePath)
                || dataset.scope().targetPrefixes().stream().anyMatch(SemanticTruthSetEvaluator::blank)) {
            throw new IllegalArgumentException("Truth-set scope contains an invalid source path or target prefix");
        }
        if (dataset.blindReviews() == null || dataset.blindReviews().size() < 2) {
            throw new IllegalArgumentException("Two blind reviews are required");
        }
        Set<String> reviewers = new HashSet<>();
        for (BlindReview review : dataset.blindReviews()) {
            if (blank(review.reviewerId()) || review.submittedAt() == null) {
                throw new IllegalArgumentException("Reviewer identity and submission time are required");
            }
            if (review.predictionVisible()) {
                throw new IllegalArgumentException("Reviewers must not see semantic predictions before labelling");
            }
            reviewers.add(review.reviewerId());
            validateFacts(review.calls(), dataset.scope());
        }
        if (reviewers.size() < 2) throw new IllegalArgumentException("Two distinct blind reviewers are required");
        if (dataset.adjudication() == null || blank(dataset.adjudication().adjudicatedBy())
                || dataset.adjudication().adjudicatedAt() == null) {
            throw new IllegalArgumentException("Adjudication identity and time are required");
        }
        if (dataset.blindReviews().stream()
                .anyMatch(review -> !dataset.adjudication().adjudicatedAt().isAfter(review.submittedAt()))) {
            throw new IllegalArgumentException("Adjudication must occur after all blind reviews");
        }
        if (dataset.adjudication().calls() == null || dataset.adjudication().calls().isEmpty()) {
            throw new IllegalArgumentException("Adjudicated truth set must contain at least one call");
        }
        validateFacts(dataset.adjudication().calls(), dataset.scope());
        List<Set<CallFact>> reviewedSets = dataset.blindReviews().stream().map(review -> Set.copyOf(review.calls())).toList();
        Set<CallFact> union = new HashSet<>();
        reviewedSets.forEach(union::addAll);
        Set<CallFact> unanimous = new HashSet<>(reviewedSets.get(0));
        reviewedSets.subList(1, reviewedSets.size()).forEach(unanimous::retainAll);
        int conflictCount = difference(union, unanimous).size();
        if (conflictCount > 0 && reviewers.contains(dataset.adjudication().adjudicatedBy())) {
            throw new IllegalArgumentException("A third reviewer must adjudicate conflicting labels");
        }
        if (dataset.adjudication().conflictCount() != conflictCount) {
            throw new IllegalArgumentException("Recorded conflict count does not match blind-label disagreement");
        }
    }

    private static void validateFacts(List<CallFact> facts, Scope scope) {
        if (facts == null || new HashSet<>(facts).size() != facts.size()) {
            throw new IllegalArgumentException("Call facts must be present and unique");
        }
        for (CallFact fact : facts) {
            if (!scope.sourcePaths().contains(fact.sourcePath()) || fact.line() < 1
                    || !isCallable(fact.fromStableKey()) || !isCallable(fact.toStableKey())
                    || scope.targetPrefixes().stream().noneMatch(fact.toStableKey()::startsWith)) {
                throw new IllegalArgumentException("Call fact is outside the declared scope: " + fact);
            }
        }
    }

    private static boolean isCallable(String key) {
        return key != null && (key.startsWith("java:method:") || key.startsWith("java:constructor:"));
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean unsafeRelativePath(String value) {
        return blank(value) || value.startsWith("/") || value.startsWith("\\") || value.contains("\\")
                || value.equals("..") || value.startsWith("../") || value.contains("/../");
    }

    private static Set<CallFact> intersection(Set<CallFact> left, Set<CallFact> right) {
        return left.stream().filter(right::contains).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static Set<CallFact> difference(Set<CallFact> left, Set<CallFact> right) {
        return left.stream().filter(fact -> !right.contains(fact)).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public record Scope(Set<String> sourcePaths, List<String> targetPrefixes) {
        public Scope {
            sourcePaths = sourcePaths == null ? Set.of() : Set.copyOf(sourcePaths);
            targetPrefixes = targetPrefixes == null ? List.of() : List.copyOf(targetPrefixes);
        }
    }

    public record CallFact(String sourcePath, int line, String fromStableKey, String toStableKey) {}

    public record BlindReview(String reviewerId, Instant submittedAt, boolean predictionVisible, List<CallFact> calls) {
        public BlindReview {
            calls = calls == null ? List.of() : List.copyOf(calls);
        }
    }

    public record Adjudication(String adjudicatedBy, Instant adjudicatedAt, int conflictCount, List<CallFact> calls) {
        public Adjudication {
            calls = calls == null ? List.of() : List.copyOf(calls);
        }
    }

    public record Dataset(
            String repository,
            String commitSha,
            String license,
            String adapterVersion,
            Scope scope,
            List<BlindReview> blindReviews,
            Adjudication adjudication
    ) {
        public Dataset {
            blindReviews = blindReviews == null ? List.of() : List.copyOf(blindReviews);
        }
    }

    public record Evaluation(
            String repository,
            String commitSha,
            String adapterVersion,
            int expected,
            int actual,
            int correct,
            double precision,
            double recall,
            Set<CallFact> falsePositives,
            Set<CallFact> falseNegatives
    ) {}

    public record Gate(
            boolean passed,
            int repositories,
            int expected,
            int actual,
            int correct,
            double precision,
            double recall,
            int minRepositories,
            double minPrecision,
            List<String> failures
    ) {
        public Gate {
            failures = List.copyOf(failures);
        }
    }
}
