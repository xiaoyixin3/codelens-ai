package ai.codelens.llm;

import ai.codelens.contracts.Models;
import ai.codelens.intelligence.CodeIntelligenceService;
import ai.codelens.security.Redactor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Deterministic single-block planning. Redact values before JSON encoding, never encoded JSON. */
final class ModelContextPlan {
    record Omission(String path, String reason, int omittedChars) {}
    record FileReference(String path, String status, int availablePatchChars, int selectedPatchChars,
                         String availablePatchHash, String selectedPatchHash) {}
    record Selection(List<Models.ChangedFile> files, List<Omission> omissions, List<FileReference> references, int totalFiles) {
        Selection { files = List.copyOf(files); omissions = List.copyOf(omissions); references = List.copyOf(references); }
        boolean incomplete() { return !omissions.isEmpty(); }
    }
    record Metadata(int schemaVersion, String task, String baseSha, String headSha, String policyHash,
                    int blockIndex, int blockCount, int includedFiles, int totalFiles,
                    List<FileReference> files, List<Omission> omissions, int requestBytes, String requestHash) {}
    static final class Request {
        private final byte[] payload;
        private final Metadata metadata;
        Request(byte[] payload, Metadata metadata) { this.payload = payload.clone(); this.metadata = metadata; }
        byte[] payload() { return payload.clone(); }
        Metadata metadata() { return metadata; }
        @Override public String toString() { return "ModelRequest[body=redacted]"; }
    }

    static Selection select(List<Models.ChangedFile> input, int maxFiles, int maxPatchChars) {
        if (maxFiles < 1 || maxPatchChars < 1) throw new IllegalArgumentException("Invalid context limits");
        List<Models.ChangedFile> files = new ArrayList<>(); List<Omission> omissions = new ArrayList<>();
        List<FileReference> references = new ArrayList<>();
        int remaining = maxPatchChars;
        for (int index = 0; index < input.size(); index++) {
            var file = input.get(index);
            // Redact the entire source before cutting: a cut must not expose a partial credential.
            String patch = Redactor.redact(file.patch()); String safePath = Redactor.redact(file.path());
            if (index >= maxFiles || remaining <= 0) {
                omissions.add(new Omission(safePath, index >= maxFiles ? "file_limit" : "patch_budget", patch.length()));
                continue;
            }
            int end = Math.min(patch.length(), remaining);
            if (end < patch.length()) {
                // A partial added line cannot serve as evidence for a complete line.
                end = patch.lastIndexOf('\n', end - 1) + 1;
                omissions.add(new Omission(safePath, "patch_truncated", patch.length() - end));
            } else if (patch.isEmpty()) omissions.add(new Omission(safePath, "patch_unavailable", 0));
            String selected = patch.substring(0, end); remaining -= selected.length();
            files.add(new Models.ChangedFile(file.path(), file.status(), file.additions(), file.deletions(), selected, file.previousPath()));
            references.add(new FileReference(safePath,file.status(),patch.length(),selected.length(),
                    CodeIntelligenceService.digest(patch),CodeIntelligenceService.digest(selected)));
        }
        return new Selection(files, omissions, references, input.size());
    }

    static Request request(ObjectMapper json, String model, String task, String system, Map<String,Object> input,
                           Selection selection, Models.PullRequest pull, Models.RepositoryPolicy policy) {
        try {
            Map<String,Object> context = new LinkedHashMap<>(input);
            context.put("baseSha", pull.baseSha()); context.put("headSha", pull.headSha());
            context.put("contextCoverage", Map.of("includedFiles", selection.files().size(), "totalFiles", selection.totalFiles(),
                    "omissions", selection.omissions(), "complete", !selection.incomplete()));
            Map<String,Object> body = new LinkedHashMap<>();
            body.put("model", model); body.put("temperature", 0); body.put("response_format", Map.of("type", "json_object"));
            body.put("messages", List.of(Map.of("role", "system", "content", Redactor.redact(system)),
                    Map.of("role", "user", "content", json.writeValueAsString(redact(json.valueToTree(context))))));
            byte[] bytes = json.writeValueAsBytes(transform(json.valueToTree(body), false));
            String hash = CodeIntelligenceService.digest(new String(bytes, StandardCharsets.UTF_8));
            return new Request(bytes, new Metadata(1, task, pull.baseSha(), pull.headSha(), policy.hash(), 0, 1,
                    selection.files().size(), selection.totalFiles(), selection.references(), selection.omissions(), bytes.length, hash));
        } catch (Exception invalid) { throw new IllegalStateException("Model context could not be serialized"); }
    }

    static JsonNode redact(JsonNode node) {
        return transform(node, true);
    }

    private static JsonNode transform(JsonNode node, boolean redactText) {
        if (node.isTextual()) return redactText ? com.fasterxml.jackson.databind.node.TextNode.valueOf(Redactor.redact(node.textValue())) : node;
        if (node.isObject()) {
            ObjectNode result = ((ObjectNode) node).objectNode();
            var fields = new java.util.TreeMap<String,JsonNode>();
            node.fields().forEachRemaining(field -> fields.put(field.getKey(),field.getValue()));
            fields.forEach((key,value) -> result.set(key,transform(value,redactText))); return result;
        }
        if (node.isArray()) {
            ArrayNode result = ((ArrayNode) node).arrayNode(); node.forEach(value -> result.add(transform(value,redactText))); return result;
        }
        return node;
    }
}
