package com.engine.brailleai.core.normalize;


/**
 * Normalizes horizontal whitespace in Braille Unicode input.
 *
 * <p>Collapses consecutive spaces and tabs into a single space
 * while preserving line breaks and paragraph structure.
 */
public class WhitespaceNormalizer {

    /**
     * Normalizes whitespace within the given input.
     *
     * @param input Braille Unicode input
     * @return input with normalized whitespace
     */
    public String normalize(String input) {
        if (input == null) {
            return null;
        }

        // Replace tabs with spaces, then collapse multiple spaces
        return input
                .replace('\t', ' ')
                .replaceAll(" {2,}", " ");
    }
}
