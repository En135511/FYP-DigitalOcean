package com.engine.brailleai.api.service;

/**
 * Contract for translating Braille Unicode into plain text.
 *
 * <p>This interface defines the translation boundary of the system.
 * Implementations may use Liblouis or any future engine.
 */
public interface BrailleTranslator {

    /**
     * Translates Braille Unicode into readable text.
     *
     * @param brailleUnicode validated and normalized Braille Unicode input
     * @return translated plain text
     */
    String translate(String brailleUnicode);

    /**
     * Translates plain text into Braille Unicode.
     *
     * <p>Implementations that only support back-translation may keep the default behavior.
     *
     * @param plainText plain text input
     * @return translated Braille Unicode output
     */
    default String translateTextToBraille(String plainText) {
        throw new UnsupportedOperationException("Text-to-Braille translation is not supported.");
    }
}
