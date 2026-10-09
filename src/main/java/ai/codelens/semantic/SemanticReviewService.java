package ai.codelens.semantic;

import ai.codelens.contracts.Models;
import ai.codelens.review.ReviewExecutionGuard;
import ai.codelens.store.LeaseLostException;
import ai.codelens.workspace.GitHubRepositoryWorkspace;
import ai.codelens.workspace.RepositoryWorkspace;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Runs fail-closed Java whole-repository impact analysis for the production worker. */
public final class SemanticReviewService {
    private static final Set<SemanticModels.RelationType> IMPACT_RELATIONSHIPS = Set.of(
            SemanticModels.RelationType.CALLS,
            SemanticModels.RelationType.READS,
            SemanticModels.RelationType.WRITES,
            SemanticModels.RelationType.EXTENDS,
            SemanticModels.RelationType.IMPLEMENTS,
            SemanticModels.RelationType.OVERRIDES,
            SemanticModels.RelationType.TESTS
    );
    private final boolean enabled;
    private final Set<String> enabledRepositories;
    private final GitHubRepositoryWorkspace workspaces;
    private final SemanticIndexService indexes;
    private final SemanticReviewAuditStore audits;
    private final SemanticReusePlanner reusePlanner;

    public SemanticReviewService(boolean enabled, Set<String> enabledRepositories,
                                 GitHubRepositoryWorkspace workspaces, SemanticIndexService indexes,
                                 SemanticReviewAuditStore audits) {
        this.enabled = enabled;
        this.enabledRepositories = enabledRepositories == null ? Set.of() : enabledRepositories.stream()
                .map(value -> value.toLowerCase(java.util.Locale.ROOT))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        this.workspaces = java.util.Objects.requireNonNull(workspaces);
        this.indexes = java.util.Objects.requireNonNull(indexes);
        this.audits = java.util.Objects.requireNonNull(audits);
        this.reusePlanner = new SemanticReusePlanner();
    }

    public Result analyze(long repositoryId, Models.ReviewJob job, Models.PullRequest pull, ReviewExecutionGuard guard) {
        guard.check();
        String repository = (job.owner() + "/" + job.repo()).toLowerCase(java.util.Locale.ROOT);
        if (!enabled || !enabledRepositories.contains(repository)) return Result.notAttempted("semantic_disabled");
        if (!relevantChange(pull.files())) return Result.notAttempted("no_java_or_build_change");
        RepositoryWorkspace.MaterializedWorkspace materialized;
        try {
            materialized = workspaces.materialize(job.installationId(), job.owner(), job.repo(), pull.baseSha(), pull.headSha());
        } catch (RuntimeException exception) {
            return Result.fallback("semantic_materialization_failed", "S0");
        }
        try (RepositoryWorkspace.MaterializedWorkspace workspace = materialized) {
            guard.check();
            String repositoryKey = Long.toString(repositoryId);
            Set<String> changedPaths = changedPaths(pull.files());
            SemanticModels.Index base = indexes.base(repositoryKey, workspace.base(), pull.baseSha());
            guard.check();
            SemanticModels.Index head = indexes.head(repositoryKey, workspace.head(), pull.headSha(), base, changedPaths);
            if (base.coverage().indexedFiles() == 0 || head.coverage().indexedFiles() == 0) {
                return Result.fallback("semantic_no_indexed_files", workspace.executionLevel());
            }
            Models.ImpactSummary impact = impact(base, head, changedPaths);
            Models.Coverage coverage = coverage(base, head, workspace.executionLevel());
            SemanticReusePlanner.Investigation reuse = reusePlanner.investigate(base, head, changedPaths);
            guard.write(() -> audits.save(job.reviewRunId(), repositoryId, base, head, impact, coverage, reuse));
            return Result.applied(impact, coverage, base.coverage(), head.coverage(), reuse);
        } catch (LeaseLostException exception) { throw exception;
        } catch (RuntimeException exception) {
            return Result.fallback("semantic_index_failed", "S1");
        }
    }

    private static boolean relevantChange(List<Models.ChangedFile> files) {
        return files.stream().map(Models.ChangedFile::path).anyMatch(path -> path.endsWith(".java")
                || path.endsWith("pom.xml") || path.endsWith("build.gradle") || path.endsWith("build.gradle.kts")
                || path.endsWith("settings.gradle") || path.endsWith("settings.gradle.kts"));
    }

    private static Set<String> changedPaths(List<Models.ChangedFile> files) {
        Set<String> paths = new LinkedHashSet<>();
        for (Models.ChangedFile file : files) {
            if (file.path() != null && !file.path().isBlank()) paths.add(normalize(file.path()));
            if (file.previousPath() != null && !file.previousPath().isBlank()) paths.add(normalize(file.previousPath()));
        }
        return Set.copyOf(paths);
    }

    private static Models.ImpactSummary impact(
            SemanticModels.Index base,
            SemanticModels.Index head,
            Set<String> changedPaths
    ) {
        Set<String> changedKeys = new LinkedHashSet<>();
        base.symbols().stream().filter(symbol -> changedPaths.contains(symbol.path()))
                .map(SemanticModels.Symbol::stableKey).forEach(changedKeys::add);
        head.symbols().stream().filter(symbol -> changedPaths.contains(symbol.path()))
                .map(SemanticModels.Symbol::stableKey).forEach(changedKeys::add);

        Map<String, SemanticModels.Symbol> symbols = new LinkedHashMap<>();
        base.symbols().forEach(symbol -> symbols.putIfAbsent(symbol.stableKey(), symbol));
        head.symbols().forEach(symbol -> symbols.put(symbol.stableKey(), symbol));
        Map<String, Models.ImpactPath> paths = new LinkedHashMap<>();
        List<SemanticModels.Relationship> relationships = new ArrayList<>(base.relationships());
        relationships.addAll(head.relationships());
        relationships.stream()
                .filter(SemanticModels.Relationship::typeResolved)
                .filter(edge -> IMPACT_RELATIONSHIPS.contains(edge.type()))
                .filter(edge -> changedKeys.contains(edge.toStableKey()))
                .forEach(edge -> {
                    SemanticModels.Symbol changed = symbols.get(edge.toStableKey());
                    SemanticModels.Symbol impacted = symbols.get(edge.fromStableKey());
                    if (changed == null || impacted == null || changedPaths.contains(impacted.path())) return;
                    double score = edge.type() == SemanticModels.RelationType.TESTS ? 1.0 : edge.confidence();
                    String key = changed.stableKey() + "\n" + impacted.stableKey();
                    paths.putIfAbsent(key, new Models.ImpactPath(changed.qualifiedName(), impacted.qualifiedName(), 1, score));
                });
        List<Models.ImpactPath> top = paths.values().stream()
                .sorted(Comparator.comparingDouble(Models.ImpactPath::score).reversed()
                        .thenComparing(Models.ImpactPath::changedName)
                        .thenComparing(Models.ImpactPath::impactedName))
                .limit(12).toList();
        int score = Math.min(100, changedKeys.size() * 2 + paths.size() * 8);
        String level = score >= 70 ? "high" : score >= 30 ? "medium" : "low";
        String warning = head.coverage().unresolvedRelationships() == 0
                ? "Whole-repository Java semantic relationships were used; repository code was not executed."
                : "Whole-repository Java semantics were used with %d unresolved relationship(s); repository code was not executed."
                .formatted(head.coverage().unresolvedRelationships());
        return new Models.ImpactSummary(level, score, changedKeys.size(), paths.size(), top, warning);
    }

    private static Models.Coverage coverage(
            SemanticModels.Index base,
            SemanticModels.Index head,
            String executionLevel
    ) {
        SemanticModels.Coverage current = head.coverage();
        List<String> limitations = new ArrayList<>();
        limitations.add("Repository code, builds, annotation processors, and tests were not executed.");
        if (current.unresolvedRelationships() > 0) {
            limitations.add("%d relationship(s) remain explicitly unresolved, commonly because dependency classpaths are unavailable."
                    .formatted(current.unresolvedRelationships()));
        }
        int failed = base.coverage().failedFiles() + current.failedFiles();
        int skipped = base.coverage().skippedFiles() + current.skippedFiles();
        if (failed > 0 || skipped > 0) {
            limitations.add("Base/head indexing reported %d failed and %d skipped file(s).".formatted(failed, skipped));
        }
        String level = current.level() == SemanticModels.CoverageLevel.SEMANTIC ? "semantic" : "semantic/partial";
        return new Models.Coverage(current.indexedFiles(), current.eligibleFiles(),
                current.failedFiles() > 0 || current.skippedFiles() > 0, level, executionLevel, limitations);
    }

    private static String normalize(String path) {
        return path.replace('\\', '/').replaceFirst("^\\./", "");
    }

    public record Result(
            boolean attempted,
            boolean applied,
            String reason,
            String executionLevel,
            Models.ImpactSummary impact,
            Models.Coverage coverage,
            SemanticModels.Coverage baseCoverage,
            SemanticModels.Coverage headCoverage,
            SemanticReusePlanner.Investigation reuseInvestigation
    ) {
        private static Result notAttempted(String reason) {
            return new Result(false, false, reason, "S0", null, null, null, null, null);
        }
        private static Result fallback(String reason, String executionLevel) {
            return new Result(true, false, reason, executionLevel, null, null, null, null, null);
        }
        private static Result applied(Models.ImpactSummary impact, Models.Coverage coverage,
                                      SemanticModels.Coverage base, SemanticModels.Coverage head,
                                      SemanticReusePlanner.Investigation reuse) {
            return new Result(true, true, "", coverage.executionLevel(), impact, coverage, base, head, reuse);
        }
    }
}
