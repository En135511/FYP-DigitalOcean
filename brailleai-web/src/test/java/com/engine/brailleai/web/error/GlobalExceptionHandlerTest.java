package com.engine.brailleai.web.error;

import com.engine.brailleai.api.dto.BrailleResponse;
import com.engine.brailleai.api.exception.BrailleException;
import com.engine.brailleai.api.exception.InvalidBrailleInputException;
import com.engine.brailleai.liblouis.exception.LiblouisTranslationException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void mapsInvalidInputToBadRequest() {
        ResponseEntity<BrailleResponse> response = handler.handleInvalidInput(
                new InvalidBrailleInputException("Bad Braille")
        );

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("error", response.getBody().getStatus());
        assertEquals("Bad Braille", response.getBody().getMessage());
    }

    @Test
    void mapsLiblouisFailureToInternalErrorWithoutLeakingDetails() {
        ResponseEntity<BrailleResponse> response = handler.handleLiblouisFailure(
                new LiblouisTranslationException("native stack")
        );

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals("Translation engine failed", response.getBody().getMessage());
    }

    @Test
    void mapsGenericBrailleExceptionToInternalError() {
        ResponseEntity<BrailleResponse> response = handler.handleBrailleDomainError(
                new TestBrailleException("Domain error")
        );

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals("Domain error", response.getBody().getMessage());
    }

    @Test
    void mapsUnexpectedExceptionToGenericInternalError() {
        ResponseEntity<BrailleResponse> response = handler.handleUnexpectedError(
                new RuntimeException("boom")
        );

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals("Unexpected server error", response.getBody().getMessage());
    }

    @Test
    void mapsLargeUploadsToPayloadTooLarge() {
        ResponseEntity<BrailleResponse> response = handler.handleUploadTooLarge(
                new MaxUploadSizeExceededException(1024L)
        );

        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, response.getStatusCode());
        assertEquals("Uploaded file is too large. Please use a file up to 20MB.", response.getBody().getMessage());
    }

    private static class TestBrailleException extends BrailleException {
        private TestBrailleException(String message) {
            super(message);
        }
    }
}
