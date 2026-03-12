package com.engine.brailleai.liblouis.exception;


import com.engine.brailleai.api.exception.BrailleException;

/**
 * Thrown when Liblouis fails to translate valid Braille Unicode input.
 *
 * <p>This represents a native or engine-level failure and is not caused
 * by invalid user input.
 */
public class LiblouisTranslationException extends BrailleException {

    public LiblouisTranslationException(String message) {
        super(message);
    }

    public LiblouisTranslationException(String message, Throwable cause) {
        super(message, cause);
    }
}

