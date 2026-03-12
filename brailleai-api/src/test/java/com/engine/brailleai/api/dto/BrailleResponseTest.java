package com.engine.brailleai.api.dto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class BrailleResponseTest {

    @Test
    void successFactorySetsSuccessfulPayload() {
        BrailleResponse response = BrailleResponse.success(
                "\u2801\u2803",
                TranslationDirection.TEXT_TO_BRAILLE,
                "text"
        );

        assertEquals("success", response.getStatus());
        assertEquals("\u2801\u2803", response.getTranslatedText());
        assertEquals(TranslationDirection.TEXT_TO_BRAILLE, response.getDirection());
        assertEquals("text", response.getDetectedInputType());
        assertNull(response.getMessage());
    }

    @Test
    void errorFactorySetsErrorPayload() {
        BrailleResponse response = BrailleResponse.error("Invalid input");

        assertEquals("error", response.getStatus());
        assertEquals("Invalid input", response.getMessage());
        assertNull(response.getTranslatedText());
        assertNull(response.getDirection());
        assertNull(response.getDetectedInputType());
    }
}
