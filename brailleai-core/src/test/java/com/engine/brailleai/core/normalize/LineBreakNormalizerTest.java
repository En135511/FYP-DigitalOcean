package com.engine.brailleai.core.normalize;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LineBreakNormalizerTest {

    @Test
    void normalizesLineEndingsAndCollapsesExcessBlankLines() {
        LineBreakNormalizer normalizer = new LineBreakNormalizer();

        String input = "\u2801\r\n\r\n\r\n\u2803\r\u2809";
        String normalized = normalizer.normalize(input);

        assertEquals("\u2801\n\n\u2803\n\u2809", normalized);
    }

    @Test
    void returnsNullWhenInputIsNull() {
        LineBreakNormalizer normalizer = new LineBreakNormalizer();

        assertNull(normalizer.normalize(null));
    }
}
