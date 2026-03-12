package com.engine.brailleai.core.pipeline;

/**
 * Holds in-memory state during a single Braille translation pipeline execution.
 *
 * <p>This context object is created at the start of the pipeline,
 * passed through each processing stage, and discarded after output generation.
 *
 * <p>It is intentionally simple and mutable to keep the pipeline explicit
 * and easy to debug.
 */
public class BrailleTranslationContext {

    private final String originalBrailleUnicode;
    private String normalizedBrailleUnicode;
    private String translatedText;

    /**
     * Creates a new translation context for a single request.
     *
     * @param originalBrailleUnicode raw Braille Unicode input from the client
     */
    public BrailleTranslationContext(String originalBrailleUnicode) {
        this.originalBrailleUnicode = originalBrailleUnicode;
    }

    public String getOriginalBrailleUnicode() {
        return originalBrailleUnicode;
    }

    public String getNormalizedBrailleUnicode() {
        return normalizedBrailleUnicode;
    }

    public void setNormalizedBrailleUnicode(String normalizedBrailleUnicode) {
        this.normalizedBrailleUnicode = normalizedBrailleUnicode;
    }

    public String getTranslatedText() {
        return translatedText;
    }

    public void setTranslatedText(String translatedText) {
        this.translatedText = translatedText;
    }
}
