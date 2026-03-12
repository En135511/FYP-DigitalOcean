package com.engine.brailleai.core.normalize;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class WhitespaceNormalizerTest {

    @Test
    void replacesTabsAndCollapsesRepeatedSpaces() {
        WhitespaceNormalizer normalizer = new WhitespaceNormalizer();

        String normalized = normalizer.normalize("\u2801\t  \u2803   \u2809");

        assertEquals("\u2801 \u2803 \u2809", normalized);
    }

    @Test
    void returnsNullWhenInputIsNull() {
        WhitespaceNormalizer normalizer = new WhitespaceNormalizer();

        assertNull(normalizer.normalize(null));
    }
}
