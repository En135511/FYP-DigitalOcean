package com.engine.brailleai.vision.braille.unicode;

import com.engine.brailleai.vision.braille.BrailleCell;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BrailleUnicodeEncoderTest {

    private final BrailleUnicodeEncoder encoder = new BrailleUnicodeEncoder();

    @Test
    void encodesCellUsingUnicodeBrailleBitMapping() {
        BrailleCell cell = new BrailleCell(0, 0, Set.of(1, 4));

        char encoded = encoder.encode(cell);

        assertEquals('\u2809', encoded);
    }

    @Test
    void rejectsUnsupportedDotIndexes() {
        BrailleCell invalid = new BrailleCell(0, 0, Set.of(7));

        assertThrows(IllegalArgumentException.class, () -> encoder.encode(invalid));
    }
}
