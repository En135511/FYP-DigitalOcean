package com.engine.brailleai.web.error;


import com.engine.brailleai.api.dto.BrailleResponse;
import com.engine.brailleai.api.exception.BrailleException;
import com.engine.brailleai.api.exception.InvalidBrailleInputException;
import com.engine.brailleai.liblouis.exception.LiblouisTranslationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Global exception handler for the BrailleAI REST API.
 *
 * <p>Maps domain-specific exceptions to appropriate HTTP responses
 * without leaking internal details.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(InvalidBrailleInputException.class)
    public ResponseEntity<BrailleResponse> handleInvalidInput(
            InvalidBrailleInputException ex
    ) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(BrailleResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(LiblouisTranslationException.class)
    public ResponseEntity<BrailleResponse> handleLiblouisFailure(
            LiblouisTranslationException ex
    ) {
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(BrailleResponse.error("Translation engine failed"));
    }

    @ExceptionHandler(BrailleException.class)
    public ResponseEntity<BrailleResponse> handleBrailleDomainError(
            BrailleException ex
    ) {
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(BrailleResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<BrailleResponse> handleUploadTooLarge(
            MaxUploadSizeExceededException ex
    ) {
        return ResponseEntity
                .status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(BrailleResponse.error("Uploaded file is too large. Please use a file up to 20MB."));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<BrailleResponse> handleUnexpectedError(
            Exception ex
    ) {
        log.error("Unhandled exception in API", ex);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(BrailleResponse.error("Unexpected server error"));
    }
}
