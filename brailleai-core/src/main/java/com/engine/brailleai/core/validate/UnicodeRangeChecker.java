package com.engine.brailleai.core.validate;


import com.engine.brailleai.api.exception.InvalidBrailleInputException;

/**
 * Ensures that input contains only valid Braille Unicode characters
 * and permitted whitespace.
 */
public class UnicodeRangeChecker {

    private static final int BRAILLE_UNICODE_START = 0x2800;
    private static final int BRAILLE_UNICODE_END   = 0x28FF;

    /**
     * Checks whether all non-whitespace characters fall within
     * the Braille Unicode block.
     *
     * @param brailleInput raw Braille Unicode input
     * @throws InvalidBrailleInputException if invalid characters are found
     */
    public void check(String brailleInput) {
        for (int i = 0; i < brailleInput.length(); i++) {
            char character = brailleInput.charAt(i);

            if (Character.isWhitespace(character)) {
                continue;
            }

            int codePoint = character;

            if (codePoint < BRAILLE_UNICODE_START || codePoint > BRAILLE_UNICODE_END) {
                throw new InvalidBrailleInputException(
                        "Input contains invalid non-Braille Unicode characters"
                );
            }
        }
    }
}
