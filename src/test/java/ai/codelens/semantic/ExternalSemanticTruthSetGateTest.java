package ai.codelens.semantic;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs independently supplied, local truth sets without downloading or executing repository code. */
@EnabledIfSystemProperty(named = "codelens.semantic.truthSetManifest", matches = ".+")
class ExternalSemanticTruthSetGateTest {
    @Test
    void selectedRepositoriesMeetThePhaseOneDirectCallGate() throws Exception {
        Path manifestPath = Path.of(System.getProperty("codelens.semantic.truthSetManifest"))
                .toAbsolutePath().normalize();
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        Manifest manifest = json.readValue(manifestPath.toFile(), Manifest.class);
        assertTrue(manifest.entries() != null && !manifest.entries().isEmpty(), "truth-set manifest must contain entries");

        SemanticTruthSetEvaluator evaluator = new SemanticTruthSetEvaluator();
        List<SemanticTruthSetEvaluator.Evaluation> evaluations = new ArrayList<>();
        for (Entry entry : manifest.entries()) {
            Path repository = resolve(manifestPath, entry.repositoryPath());
            Path datasetPath = resolve(manifestPath, entry.datasetPath());
            assertTrue(Files.isRegularFile(datasetPath), () -> "missing truth-set dataset: " + datasetPath);
            SemanticTruthSetEvaluator.Dataset dataset = json.readValue(datasetPath.toFile(), SemanticTruthSetEvaluator.Dataset.class);
            assertEquals(dataset.commitSha(), gitHead(repository),
                    () -> "repository checkout is not at truth-set commit: " + repository);

            BuildModel model = new BuildModelDetector().detect(repository);
            SemanticModels.Index index = new JavaSemanticAdapter().index(repository, dataset.commitSha(), model);
            SemanticTruthSetEvaluator.Evaluation evaluation = evaluator.evaluate(dataset, index);
            evaluations.add(evaluation);
            System.out.printf("SEMANTIC_TRUTH_SET repository=%s commit=%s expected=%d actual=%d correct=%d "
                            + "precision=%.4f recall=%.4f reviewerAgreement=%.4f indexedFiles=%d failedFiles=%d unresolved=%d%n",
                    evaluation.repository(), evaluation.commitSha(), evaluation.expected(), evaluation.actual(), evaluation.correct(),
                    evaluation.precision(), evaluation.recall(), evaluation.reviewerAgreement(), index.coverage().indexedFiles(),
                    index.coverage().failedFiles(), index.coverage().unresolvedRelationships());
        }

        SemanticTruthSetEvaluator.Gate gate = evaluator.gate(evaluations);
        System.out.printf("SEMANTIC_TRUTH_SET_GATE passed=%s repositories=%d expected=%d actual=%d correct=%d "
                        + "precision=%.4f recall=%.4f failures=%s%n",
                gate.passed(), gate.repositories(), gate.expected(), gate.actual(), gate.correct(),
                gate.precision(), gate.recall(), gate.failures());
        assertTrue(gate.passed(), () -> String.join("; ", gate.failures()));
    }

    private static Path resolve(Path manifest, String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("manifest paths must not be blank");
        Path path = Path.of(value);
        return (path.isAbsolute() ? path : manifest.getParent().resolve(path)).toAbsolutePath().normalize();
    }

    private static String gitHead(Path repository) throws Exception {
        Process process = new ProcessBuilder("git", "-C", repository.toString(), "rev-parse", "HEAD")
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        assertEquals(0, process.waitFor(), () -> "unable to read repository revision: " + output);
        return output;
    }

    private record Manifest(List<Entry> entries) {}
    private record Entry(String repositoryPath, String datasetPath) {}
}
