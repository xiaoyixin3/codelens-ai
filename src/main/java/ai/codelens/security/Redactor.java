package ai.codelens.security;

import java.util.regex.Pattern;

public final class Redactor {
    private static final Pattern BEARER = Pattern.compile("(?i)bearer\\s+[a-z0-9._~+/-]{8,}");
    private static final Pattern KEY_VALUE = Pattern.compile("(?i)(api[_-]?key|token|secret|password|private[_-]?key)(\\s*[:=]\\s*)[^\\s,;]+", Pattern.MULTILINE);
    private static final Pattern PEM = Pattern.compile("-----BEGIN [^-]+-----[\\s\\S]*?-----END [^-]+-----");

    private Redactor() {}

    public static String redact(String input) {
        if (input == null) return "";
        String result = PEM.matcher(input).replaceAll("[REDACTED_PEM]");
        result = BEARER.matcher(result).replaceAll("Bearer [REDACTED]");
        return KEY_VALUE.matcher(result).replaceAll("$1$2[REDACTED]");
    }
}
