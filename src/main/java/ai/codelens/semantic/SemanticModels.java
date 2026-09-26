package ai.codelens.semantic;

import java.util.List;
import java.util.Map;
import java.util.Set;

public final class SemanticModels {
    private SemanticModels() {}

    public enum SymbolKind { TYPE, METHOD, CONSTRUCTOR, FIELD }

    public enum RelationType {
        CALLS, READS, WRITES, IMPLEMENTS, EXTENDS, OVERRIDES, THROWS, CATCHES, ANNOTATED_WITH, TESTS
    }

    public enum CoverageLevel { SEMANTIC, SEMANTIC_PARTIAL, DIFF_ONLY, FAILED }

    public record Symbol(
            String stableKey,
            SymbolKind kind,
            String qualifiedName,
            String signature,
            String path,
            int startLine,
            int endLine,
            boolean testSource,
            boolean typeResolved
    ) {}

    public record Relationship(
            String fromStableKey,
            String toStableKey,
            RelationType type,
            String sourcePath,
            int sourceLine,
            double confidence,
            boolean typeResolved
    ) {}

    public record FileStatus(String path, String status, String reason, String contentHash) {}

    public record Coverage(
            CoverageLevel level,
            int eligibleFiles,
            int indexedFiles,
            int failedFiles,
            int skippedFiles,
            int reusedFiles,
            int resolvedRelationships,
            int unresolvedRelationships,
            Map<String, Long> degradationReasons
    ) {
        public Coverage {
            degradationReasons = Map.copyOf(degradationReasons);
        }
    }

    public record Index(
            String commitSha,
            String adapterVersion,
            String buildModelHash,
            List<FileStatus> files,
            List<Symbol> symbols,
            List<Relationship> relationships,
            Coverage coverage
    ) {
        public Index {
            files = List.copyOf(files);
            symbols = List.copyOf(symbols);
            relationships = List.copyOf(relationships);
        }

        public List<Relationship> incoming(String stableKey, RelationType... types) {
            Set<RelationType> accepted = Set.of(types);
            return relationships.stream()
                    .filter(relationship -> relationship.toStableKey().equals(stableKey))
                    .filter(relationship -> accepted.isEmpty() || accepted.contains(relationship.type()))
                    .toList();
        }

        public List<Symbol> symbolsAtPath(String path) {
            return symbols.stream().filter(symbol -> symbol.path().equals(path)).toList();
        }
    }
}
