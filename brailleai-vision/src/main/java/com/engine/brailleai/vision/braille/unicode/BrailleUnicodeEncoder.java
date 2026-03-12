package com.engine.brailleai.vision.braille.unicode;

import com.engine.brailleai.vision.braille.BrailleCell;

import java.util.Set;

public class BrailleUnicodeEncoder {

    private static final int BRAILLE_BASE = 0x2800;

    /**
     * Encodes a single BrailleCell into a Unicode Braille character.
     */
    public char encode(BrailleCell cell) {

        int bitmask = 0;
        Set<Integer> dots = cell.getActiveDots();

        for (int dot : dots) {
            bitmask |= dotToBit(dot);
        }

        return (char) (BRAILLE_BASE + bitmask);
    }

    /**
     * Maps Braille dot number (1–6) to Unicode bit value.
     */
    private int dotToBit(int dot) {
        return switch (dot) {
            case 1 -> 0x01;
            case 2 -> 0x02;
            case 3 -> 0x04;
            case 4 -> 0x08;
            case 5 -> 0x10;
            case 6 -> 0x20;
            default -> throw new IllegalArgumentException("Invalid Braille dot index: " + dot);
        };
    }
}