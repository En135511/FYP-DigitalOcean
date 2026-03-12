package com.engine.brailleai.core.validate;


import com.engine.brailleai.api.exception.InvalidBrailleInputException;
import com.engine.brailleai.core.pipeline.BrailleTranslationContext;

/**
 * Coordinates all Braille input validation checks.
 *
 * <p>This class represents the validation phase of the translation pipeline.
 * It performs fail-fast validation and throws domain-specific exceptions
 * when invalid input is detected.
 */
public class BrailleValidator {

    private final EmptyInputChecker emptyInputChecker;
    private final UnicodeRangeChecker unicodeRangeChecker;

    public BrailleValidator() {
        this.emptyInputChecker = new EmptyInputChecker();
        this.unicodeRangeChecker = new UnicodeRangeChecker();
    }

    /**
     * Validates the Braille input contained in the translation context.
     *
     * @param context the current translation context
     * @throws InvalidBrailleInputException if validation fails
     */
    public void validate(BrailleTranslationContext context) {
        String brailleInput = context.getOriginalBrailleUnicode();

        emptyInputChecker.check(brailleInput);
        unicodeRangeChecker.check(brailleInput);
    }
}
