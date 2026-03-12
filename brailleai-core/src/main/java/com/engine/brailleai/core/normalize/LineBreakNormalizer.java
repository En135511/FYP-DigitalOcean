package com.engine.brailleai.core.normalize;

/**
 * Normalizes line endings and paragraph spacing in Braille Unicode input.
 *
 * <p>All line endings are converted to '\n', and excessive blank lines
 * are collapsed to preserve paragraph structure without noise.
 */
public class LineBreakNormalizer {

    /**
     * Normalizes line breaks in the given input.
     *
     * @param input Braille Unicode input
     * @return input with normalized line breaks
     */
    public String normalize(String input) {
        if (input == null) {
            return null;
        }

        // Normalize Windows and old Mac line endings to Unix style
        String normalized = input
                .replace("\r\n", "\n")
                .replace("\r", "\n");

        // Collapse more than one consecutive blank line into a single blank line
        normalized = normalized.replaceAll("\n{3,}", "\n\n");


        return normalized;
    }
}
