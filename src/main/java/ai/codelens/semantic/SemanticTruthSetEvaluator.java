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
    public static final double DEFAULT_MIN_CALIBRATION_SCORE = 0.80;
    private static final Set<ContextMaterial> REQUIRED_CONTEXT = Set.of(
            ContextMaterial.FULL_REPOSITORY,
            ContextMaterial.BUILD_DESCRIPTORS,
            ContextMaterial.PROJECT_DOCUMENTATION,
            ContextMaterial.TESTS
    );

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
        Set<CallFact> first = Set.copyOf(dataset.blindReviews().get(0).calls());
        Set<CallFact> second = Set.copyOf(dataset.blindReviews().get(1).calls());
        Set<CallFact> reviewerUnion = new HashSet<>(first);
        reviewerUnion.addAll(second);
        double reviewerAgreement = reviewerUnion.isEmpty() ? 1.0
                : (double) intersection(first, second).size() / reviewerUnion.size();
        return new Evaluation(dataset.repository(), dataset.commitSha(), dataset.adapterVersion(), expected.size(), actual.size(),
                truePositives.size(), precision, recall, reviewerAgreement, falsePositives, falseNegatives);
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
        validateContext(dataset.context());
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
            if (!dataset.context().id().equals(review.contextPacketId())) {
                throw new IllegalArgumentException("Every blind review must use the frozen context packet");
            }
            if (review.submittedAt().isBefore(dataset.context().frozenAt())) {
                throw new IllegalArgumentException("Blind review cannot predate the frozen context packet");
            }
            if (!review.independent()) {
                throw new IllegalArgumentException("Blind reviews must be completed independently");
            }
            if (review.predictionVisible()) {
                throw new IllegalArgumentException("Reviewers must not see semantic predictions before labelling");
            }
            validateQualification(review.qualification(), review.submittedAt());
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

    private static void validateContext(ContextPacket context) {
        if (context == null || blank(context.id()) || context.frozenAt() == null
                || context.digest() == null || !context.digest().matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("A frozen, content-addressed context packet is required");
        }
        if (!context.materials().containsAll(REQUIRED_CONTEXT)) {
            throw new IllegalArgumentException("Context packet must include repository source, builds, project docs, and tests");
        }
    }

    private static void validateQualification(ReviewerQualification qualification, Instant submittedAt) {
        if (qualification == null || qualification.primaryLanguages().stream().noneMatch("java"::equalsIgnoreCase)
                || qualification.yearsExperience() < 0 || qualification.repositoryFamiliarity() == null
                || blank(qualification.calibrationSetId())
                || qualification.calibrationCompletedAt() == null
                || qualification.calibrationCompletedAt().isAfter(submittedAt)
                || !Double.isFinite(qualification.calibrationScore())
                || qualification.calibrationScore() < DEFAULT_MIN_CALIBRATION_SCORE
                || qualification.calibrationScore() > 1) {
            throw new IllegalArgumentException("Reviewer must be Java-qualified and pass an earlier calibration set at 80% or above");
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

    public enum ContextMaterial {
        FULL_REPOSITORY, BUILD_DESCRIPTORS, PROJECT_DOCUMENTATION, TESTS, PULL_REQUEST, LINKED_ISSUE
    }

    public enum RepositoryFamiliarity { MAINTAINER, CONTRIBUTOR, CALIBRATED_EXTERNAL }

    public record ContextPacket(String id, String digest, Instant frozenAt, Set<ContextMaterial> materials) {
        public ContextPacket {
            materials = materials == null ? Set.of() : Set.copyOf(materials);
        }
    }

    public record ReviewerQualification(
            Set<String> primaryLanguages,
            int yearsExperience,
            RepositoryFamiliarity repositoryFamiliarity,
            String calibrationSetId,
            double calibrationScore,
            Instant calibrationCompletedAt
    ) {
        public ReviewerQualification {
            primaryLanguages = primaryLanguages == null ? Set.of() : Set.copyOf(primaryLanguages);
        }
    }

    public record BlindReview(
            String reviewerId,
            Instant submittedAt,
            String contextPacketId,
            boolean independent,
            boolean predictionVisible,
            ReviewerQualification qualification,
            List<CallFact> calls
    ) {
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
            ContextPacket context,
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
            double reviewerAgreement,
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
