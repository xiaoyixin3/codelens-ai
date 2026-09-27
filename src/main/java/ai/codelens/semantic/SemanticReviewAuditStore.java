package ai.codelens.semantic;

import ai.codelens.contracts.Models;

@FunctionalInterface
public interface SemanticReviewAuditStore {
    void save(String reviewRunId, long repositoryId, SemanticModels.Index base, SemanticModels.Index head,
              Models.ImpactSummary impact, Models.Coverage coverage);
}
