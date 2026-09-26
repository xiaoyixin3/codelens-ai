# Phase 1 independent semantic truth-set contract

Status: ready for external labelling; no qualifying datasets committed yet

- Gate implementation: `SemanticTruthSetEvaluator`
- Opt-in runner: `ExternalSemanticTruthSetGateTest`
- Development oracle: `JavacCallOracle`

## Purpose

This contract separates Java direct-call labelling from adapter implementation.
It allows reviewers to provide JSON labels and fixed local repository checkouts
without changing production or evaluator code. Normal builds do not download or
execute third-party repository code.

“Blind” means prediction-blind, not context-free. A Reviewer receives the full
frozen repository, build descriptors, tests, project documentation, and approved
PR/Issue intent. Only CodeLens predictions and the other Reviewer's answers are
hidden until adjudication.

Passing the runner is necessary but not sufficient to close Phase 1. Dataset
provenance, reviewer authority, repository selection, and the generated report
must also be reviewed and retained as evidence.

## Dataset shape

Each dataset JSON maps directly to `SemanticTruthSetEvaluator.Dataset`:

```json
{
  "repository": "owner/name",
  "commitSha": "40-character commit SHA",
  "license": "SPDX or reviewed license name",
  "adapterVersion": "javaparser-3.28.2-v1",
  "context": {
    "id": "repository-one-context-v1",
    "digest": "64-character SHA-256 digest",
    "frozenAt": "2026-09-25T23:00:00Z",
    "materials": [
      "FULL_REPOSITORY",
      "BUILD_DESCRIPTORS",
      "PROJECT_DOCUMENTATION",
      "TESTS",
      "PULL_REQUEST",
      "LINKED_ISSUE"
    ]
  },
  "scope": {
    "sourcePaths": ["src/main/java/example/Caller.java"],
    "targetPrefixes": ["java:method:example.", "java:constructor:example."]
  },
  "blindReviews": [
    {
      "reviewerId": "reviewer-a",
      "submittedAt": "2026-09-26T01:00:00Z",
      "contextPacketId": "repository-one-context-v1",
      "independent": true,
      "predictionVisible": false,
      "qualification": {
        "primaryLanguages": ["Java"],
        "yearsExperience": 3,
        "repositoryFamiliarity": "CALIBRATED_EXTERNAL",
        "calibrationSetId": "java-call-calibration-v1",
        "calibrationScore": 0.9,
        "calibrationCompletedAt": "2026-09-24T01:00:00Z"
      },
      "calls": [
        {
          "sourcePath": "src/main/java/example/Caller.java",
          "line": 10,
          "fromStableKey": "java:method:example.Caller#run()",
          "toStableKey": "java:method:example.Target#execute()"
        }
      ]
    },
    {
      "reviewerId": "reviewer-b",
      "submittedAt": "2026-09-26T02:00:00Z",
      "contextPacketId": "repository-one-context-v1",
      "independent": true,
      "predictionVisible": false,
      "qualification": {
        "primaryLanguages": ["Java"],
        "yearsExperience": 4,
        "repositoryFamiliarity": "CONTRIBUTOR",
        "calibrationSetId": "java-call-calibration-v1",
        "calibrationScore": 0.95,
        "calibrationCompletedAt": "2026-09-24T02:00:00Z"
      },
      "calls": [
        {
          "sourcePath": "src/main/java/example/Caller.java",
          "line": 10,
          "fromStableKey": "java:method:example.Caller#run()",
          "toStableKey": "java:method:example.Target#execute()"
        }
      ]
    }
  ],
  "adjudication": {
    "adjudicatedBy": "reviewer-c",
    "adjudicatedAt": "2026-09-26T03:00:00Z",
    "conflictCount": 0,
    "calls": [
      {
        "sourcePath": "src/main/java/example/Caller.java",
        "line": 10,
        "fromStableKey": "java:method:example.Caller#run()",
        "toStableKey": "java:method:example.Target#execute()"
      }
    ]
  }
}
```

The evaluator computes the conflict count from non-unanimous facts and rejects a
mismatch.

## Fail-closed rules

- Repository identifiers use `owner/name`; commits must be full 40-character SHAs.
- At least two distinct reviewers are required.
- Every Reviewer receives the same content-addressed context packet.
- Context must include full source, builds, tests, and project documentation.
- Reviewers must pass a separate Java calibration set at 80% or above.
- The project owner is not presumed qualified and does not need to label data.
- Independent work is required until adjudication.
- `predictionVisible` must be false for every blind review.
- Adjudication must happen after every blind submission.
- Any disagreement requires an adjudicator who is not one of the blind reviewers.
- Recorded conflict count must exactly equal the non-unanimous call facts.
- Calls must be unique, line-addressed, and within the declared source/target scope.
- Dataset commit and adapter version must match index provenance.
- Only type-resolved `CALLS` relationships inside the frozen scope are scored.
- At least two distinct repositories are required.
- Precision must be at least 90% for every repository and for the aggregate.
- Recall, false positives, false negatives, parse failures, and unresolved counts
  are still reported even though the formal Phase 1 exit threshold names precision.

## Local manifest and execution

The manifest contains explicit repository and dataset paths:

```json
{
  "entries": [
    {
      "repositoryPath": "C:/review-corpus/repository-one",
      "datasetPath": "datasets/repository-one.json"
    },
    {
      "repositoryPath": "C:/review-corpus/repository-two",
      "datasetPath": "datasets/repository-two.json"
    }
  ]
}
```

Paths relative to the manifest resolve from its parent directory. Run the Maven
test suite with system property `codelens.semantic.truthSetManifest` set to the
absolute manifest path. The runner verifies each checkout HEAD before indexing.

Do not commit private repository paths, source archives, Reviewer personal data,
or unapproved labels. Retain approved datasets according to the evidence and
repository data-handling policy.

## Low-cost development feedback

`JavacCallOracle` uses the JDK compiler's type attribution as an implementation
independent from JavaParser. It disables annotation processing, class generation,
and repository build execution. The daily workflow is:

1. compare CodeLens relationships with compiler-oracle silver labels;
2. automatically accept agreements for development diagnostics;
3. send disagreements, compiler-error scopes, unresolved calls, and a random
   agreement sample to qualified reviewers;
4. aggregate disagreement categories and fix the adapter against the development
   set;
5. keep the sealed gold holdout unavailable until the release decision.

Compiler-oracle agreement is not human gold and cannot pass the Phase 1 exit gate.
