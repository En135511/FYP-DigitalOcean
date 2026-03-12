package com.engine.brailleai.api.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class BrailleTranslatorTest {

    @Test
    void defaultTextToBrailleMethodThrowsWhenNotImplemented() {
        BrailleTranslator translator = new BrailleTranslator() {
            @Override
            public String translate(String brailleUnicode) {
                return brailleUnicode;
            }
        };

        assertThrows(
                UnsupportedOperationException.class,
                () -> translator.translateTextToBraille("hello")
        );
    }
}
