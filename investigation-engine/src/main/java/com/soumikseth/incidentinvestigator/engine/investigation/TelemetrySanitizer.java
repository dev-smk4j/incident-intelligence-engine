package com.soumikseth.incidentinvestigator.engine.investigation;

import java.util.regex.Pattern;

/**
 * Normalizes untrusted telemetry before it is stored as evidence.
 *
 * Telemetry can contain arbitrary bytes: control characters, newlines that
 * forge extra fields, credentials accidentally logged by an application, and text
 * that looks like instructions. This class removes the parts that are structurally
 * unsafe and truncates the rest. It is deliberately not a prompt-injection filter:
 * no reliable filter exists. The real protections are that evidence is passed to a
 * planner as a clearly delimited data payload, that tool names are allowlisted, and
 * that no tool can execute commands.
 */
public final class TelemetrySanitizer {

    static final int MAX_CONTENT_LENGTH = 2_000;
    static final int MAX_SOURCE_LENGTH = 120;

    private static final Pattern CONTROL_CHARACTERS = Pattern.compile("[\\p{Cntrl}&&[^\\n\\t]]");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern CREDENTIAL_ASSIGNMENT = Pattern.compile(
            "(?i)(password|passwd|secret|api[_-]?key|access[_-]?token)(\\s*[:=]\\s*)(\\S+)");
    // Authorization headers commonly carry an optional scheme word in front of the
    // secret ("Bearer <token>"). Matching it separately avoids treating the scheme
    // as the credential and leaving the token behind.
    private static final Pattern AUTHORIZATION_HEADER = Pattern.compile(
            "(?i)(authorization)(\\s*[:=]\\s*)(?:[A-Za-z][A-Za-z0-9_-]*\\s+)?(\\S+)");

    private TelemetrySanitizer() {
    }

    public static String content(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String cleaned = CONTROL_CHARACTERS.matcher(raw).replaceAll(" ");
        cleaned = WHITESPACE.matcher(cleaned).replaceAll(" ").trim();
        cleaned = CREDENTIAL_ASSIGNMENT.matcher(cleaned).replaceAll("$1$2[REDACTED]");
        cleaned = AUTHORIZATION_HEADER.matcher(cleaned).replaceAll("$1$2[REDACTED]");
        return truncate(cleaned, MAX_CONTENT_LENGTH);
    }

    public static String source(String raw) {
        if (raw == null || raw.isBlank()) {
            return "unknown";
        }
        String cleaned = CONTROL_CHARACTERS.matcher(raw).replaceAll(" ").trim();
        return truncate(cleaned, MAX_SOURCE_LENGTH);
    }

    private static String truncate(String value, int limit) {
        if (value.length() <= limit) {
            return value;
        }
        return value.substring(0, limit) + "… [truncated " + (value.length() - limit) + " chars]";
    }
}
