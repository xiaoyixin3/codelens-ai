package ai.codelens.intelligence;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeIntelligenceServiceTest {
    @Test
    void recognizesAllSupportedMainstreamLanguages() {
        Map<String, String> cases = Map.ofEntries(
                Map.entry("service.go", "go"), Map.entry("Service.java", "java"), Map.entry("Service.kt", "kotlin"),
                Map.entry("service.py", "python"), Map.entry("service.ts", "typescript"), Map.entry("service.js", "javascript"),
                Map.entry("Service.cs", "csharp"), Map.entry("service.c", "c"), Map.entry("service.cpp", "cpp"),
                Map.entry("service.rs", "rust"), Map.entry("service.php", "php"), Map.entry("service.rb", "ruby"), Map.entry("Service.swift", "swift"));
        cases.forEach((path, language) -> assertEquals(language, CodeIntelligenceService.language(path), path));
    }

    @Test
    void extractsSymbolsAndCallsFromJavaAndRust() {
        var java = CodeIntelligenceService.parse("Service.java", "java", "public class Service {\n public void validate() {}\n public void handle() { validate(); }\n}");
        var rust = CodeIntelligenceService.parse("service.rs", "rust", "pub fn validate() -> bool { true }\npub fn handle() { validate(); }");
        assertTrue(java.symbols().stream().anyMatch(symbol -> symbol.name().equals("handle")));
        assertTrue(java.edges().stream().anyMatch(edge -> edge.toStableKey().contains("validate")));
        assertTrue(rust.symbols().stream().anyMatch(symbol -> symbol.name().equals("handle")));
        assertTrue(rust.edges().stream().anyMatch(edge -> edge.toStableKey().contains("validate")));
    }
}
