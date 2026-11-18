package com.soumikseth.incidentinvestigator.engine.investigation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TelemetrySanitizerTest {

    @Test
    void removesControlCharactersAndCollapsesWhitespace() {
        String raw = "line one\u0000\r\nline\ttwo   with spaces";

        assertThat(TelemetrySanitizer.content(raw)).isEqualTo("line one line two with spaces");
    }

    @Test
    void redactsCommonCredentialPatterns() {
        String raw = "upstream call failed password=hunter2 and authorization: Bearer abc.def";

        String sanitized = TelemetrySanitizer.content(raw);

        assertThat(sanitized).doesNotContain("hunter2", "abc.def");
        assertThat(sanitized).contains("[REDACTED]");
    }

    @Test
    void truncatesOversizedContentWithAnExplicitMarker() {
        String raw = "x".repeat(TelemetrySanitizer.MAX_CONTENT_LENGTH + 50);

        String sanitized = TelemetrySanitizer.content(raw);

        assertThat(sanitized).hasSizeLessThan(raw.length());
        assertThat(sanitized).endsWith("chars]");
    }

    @Test
    void blankSourcesFallBackToAStableLabel() {
        assertThat(TelemetrySanitizer.source(null)).isEqualTo("unknown");
        assertThat(TelemetrySanitizer.source("  ")).isEqualTo("unknown");
    }
}
