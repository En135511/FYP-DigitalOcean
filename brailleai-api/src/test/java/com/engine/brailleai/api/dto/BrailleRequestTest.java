package com.engine.brailleai.api.dto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BrailleRequestTest {

    @Test
    void getResolvedInputPrefersInputWhenProvided() {
        BrailleRequest request = new BrailleRequest();
        request.setInput("plain text");
        request.setBrailleUnicode("\u2801\u2803");

        assertEquals("plain text", request.getResolvedInput());
    }

    @Test
    void getResolvedInputFallsBackToBrailleUnicodeWhenInputIsBlank() {
        BrailleRequest request = new BrailleRequest();
        request.setInput("   ");
        request.setBrailleUnicode("\u2801\u2803");

        assertEquals("\u2801\u2803", request.getResolvedInput());
    }
}
