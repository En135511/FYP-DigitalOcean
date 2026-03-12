package com.engine.brailleai.output.brf;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class BrfGeneratorTest {

    private final BrfGenerator generator = new BrfGenerator();

    @Test
    void convertsBrailleUnicodeToBrailleAscii() {
        byte[] output = generator.generate("\u2801\u2803\u2809");

        assertEquals("ABC", new String(output, StandardCharsets.US_ASCII));
    }

    @Test
    void preservesLayoutAndReplacesUnsupportedCharacters() {
        byte[] output = generator.generate("\u2801\t\u2840\n\u00E9");

        assertEquals("A ?\n?", new String(output, StandardCharsets.US_ASCII));
    }

    @Test
    void returnsEmptyBytesForNullOrEmptyInput() {
        assertArrayEquals(new byte[0], generator.generate(null));
        assertArrayEquals(new byte[0], generator.generate(""));
    }
}
