package com.shifa.oms.whatsapp;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that a 4-byte emoji stored in {@code whatsapp_templates.body} reads
 * back INTACT through the real JDBC/JPA stack (exercises the Hikari
 * {@code SET NAMES utf8mb4} connection-init). If the connection returns results
 * in a 3-byte charset the emoji comes back mangled and this fails.
 */
@SpringBootTest
class WhatsappTemplateEncodingIT {

    /** ☘ U+2618 — the basic-plane shamrock seeded into the "confirm" body (V46). */
    private static final int HERB_CODEPOINT = 0x2618;

    @Autowired
    private WhatsappTemplateRepository repository;

    @Test
    void confirmTemplateEmojiSurvivesJdbcRoundTrip() {
        WhatsappTemplate confirm = repository.findByActiveTrueOrderBySortOrderAscIdAsc().stream()
                .filter(t -> "confirm".equals(t.getTemplateKey()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("confirm template not seeded"));

        String body = confirm.getBody();
        boolean hasHerb = body.codePoints().anyMatch(cp -> cp == HERB_CODEPOINT);
        boolean hasReplacementChar = body.indexOf('\uFFFD') >= 0;

        assertThat(hasReplacementChar)
                .as("body should not contain the Unicode replacement char (\uFFFD): [%s]", body)
                .isFalse();
        assertThat(hasHerb)
                .as("body should contain the ☘ symbol (U+2618): [%s]", body)
                .isTrue();
    }
}
