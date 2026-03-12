package com.engine.brailleai.web.error;

/**
 * Raised when the backend cannot reach the external Python vision service.
 */
public class VisionServiceUnavailableException extends RuntimeException {

    public VisionServiceUnavailableException(String message) {
        super(message);
    }

    public VisionServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
