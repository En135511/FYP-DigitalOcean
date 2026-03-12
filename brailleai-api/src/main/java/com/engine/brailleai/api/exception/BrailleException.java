package com.engine.brailleai.api.exception;


/**
 * Base exception for all Braille domain-related errors.
 *
 * <p>This class represents expected, meaningful failures that occur
 * during Braille processing and should be handled gracefully by
 * higher layers (e.g., the web layer).
 */
public abstract class BrailleException extends RuntimeException {

    protected BrailleException(String message) {
        super(message);
    }

    protected BrailleException(String message, Throwable cause) {
        super(message, cause);
    }
}
