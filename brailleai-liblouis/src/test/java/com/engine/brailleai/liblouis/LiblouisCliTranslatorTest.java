package com.engine.brailleai.liblouis;

import com.engine.brailleai.liblouis.exception.LiblouisTranslationException;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiblouisCliTranslatorTest {

    @Test
    void returnsEmptyStringForNullOrBlankInput() {
        LiblouisCliTranslator translator = new LiblouisCliTranslator(null, null);

        assertEquals("", translator.translate(null));
        assertEquals("", translator.translate("   "));
    }

    @Test
    void throwsClearFailureWhenLiblouisPathIsMissing() {
        LiblouisCliTranslator translator = new LiblouisCliTranslator(null, null);

        LiblouisTranslationException ex = assertThrows(
                LiblouisTranslationException.class,
                () -> translator.translate("\u2801")
        );

        assertTrue(ex.getMessage().contains("translation failed"));
        assertNotNull(ex.getCause());
        assertTrue(ex.getCause().getMessage().contains("LOUIS_CLI_PATH is not set"));
    }

    @Test
    void normalizesSmartPunctuationBeforeForwardTranslation() throws Exception {
        LiblouisCliTranslator translator = new LiblouisCliTranslator("cli", "table");
        Method method = LiblouisCliTranslator.class.getDeclaredMethod(
                "normalizeTextForForwardTranslation",
                String.class
        );
        method.setAccessible(true);

        String normalized = (String) method.invoke(translator, "It\u2019s \u201ctext\u201d\u2026");

        assertEquals("It's \"text\"...", normalized);
    }

    @Test
    void doesNotExposeLegacyAndToYRemapHook() {
        assertThrows(
                NoSuchMethodException.class,
                () -> LiblouisCliTranslator.class.getDeclaredMethod(
                        "rewriteAndContractionsAsY",
                        String.class
                )
        );
    }
}
