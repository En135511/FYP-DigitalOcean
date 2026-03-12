package com.engine.brailleai.core.validate;

import com.engine.brailleai.api.exception.InvalidBrailleInputException;
import com.engine.brailleai.core.pipeline.BrailleTranslationContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BrailleValidatorTest {

    private final BrailleValidator validator = new BrailleValidator();

    @Test
    void rejectsEmptyInput() {
        BrailleTranslationContext context = new BrailleTranslationContext("   ");

        assertThrows(InvalidBrailleInputException.class, () -> validator.validate(context));
    }

    @Test
    void rejectsNonBrailleCharacter() {
        BrailleTranslationContext context = new BrailleTranslationContext("\u2801A");

        assertThrows(InvalidBrailleInputException.class, () -> validator.validate(context));
    }

    @Test
    void acceptsBrailleAndWhitespace() {
        BrailleTranslationContext context = new BrailleTranslationContext("\u2801 \n\t\u2803");

        assertDoesNotThrow(() -> validator.validate(context));
    }
}
