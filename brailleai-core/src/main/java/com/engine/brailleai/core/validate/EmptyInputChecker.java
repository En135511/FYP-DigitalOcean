package com.engine.brailleai.core.validate;


import com.engine.brailleai.api.exception.InvalidBrailleInputException;

/**
 * Validates that Braille input is not null, empty, or whitespace-only.
 */
public class EmptyInputChecker {

    /**
     * Checks whether the provided Braille input is empty.
     *
     * @param brailleInput raw Braille Unicode input
     * @throws InvalidBrailleInputException if input is null or empty
     */
    public void check(String brailleInput) {
        if (brailleInput == null || brailleInput.trim().isEmpty()) {
            throw new InvalidBrailleInputException("Braille input must not be empty");
        }
    }
}
