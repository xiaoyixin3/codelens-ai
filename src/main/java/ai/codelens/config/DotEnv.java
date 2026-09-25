package ai.codelens.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public final class DotEnv {
    private static final Map<String, String> VALUES = new HashMap<>();

    private DotEnv() {}

    public static void load(String filename) {
        Path path = Path.of(filename);
        if (!Files.isRegularFile(path)) return;
        try {
            for (String raw : Files.readAllLines(path)) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#") || !line.contains("=")) continue;
                int separator = line.indexOf('=');
                String key = line.substring(0, separator).trim().replaceFirst("^export\\s+", "");
                String value = line.substring(separator + 1).trim();
                if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\""))
                        || (value.startsWith("'") && value.endsWith("'")))) {
                    value = value.substring(1, value.length() - 1);
                }
                VALUES.putIfAbsent(key, value);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Could not load " + filename, exception);
        }
    }

    public static String get(String name, String fallback) {
        String process = System.getenv(name);
        if (process != null && !process.isBlank()) return process.trim();
        String local = VALUES.get(name);
        return local == null || local.isBlank() ? fallback : local.trim();
    }
}
