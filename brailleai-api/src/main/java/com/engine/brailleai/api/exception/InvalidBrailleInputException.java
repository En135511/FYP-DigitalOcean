package com.engine.brailleai.api.exception;

/**
 * Thrown when Braille Unicode input fails domain validation.
 *
 * <p>This exception represents client-side input errors such as:
 * <ul>
 *   <li>Null or empty input</li>
 *   <li>Non-Braille Unicode characters</li>
 * </ul>
 *
 * <p>It is an expected, recoverable error and should be
 * translated into an appropriate HTTP response by the web layer.
 */
public class InvalidBrailleInputException extends BrailleException {

    public InvalidBrailleInputException(String message) {
        super(message);
    }
}

