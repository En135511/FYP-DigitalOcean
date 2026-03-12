package com.engine.brailleai.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Standard response wrapper for Braille translation requests.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BrailleResponse {

    private String status;
    private String translatedText;
    private TranslationDirection direction;
    private String detectedInputType;
    private String message;

    private BrailleResponse(
            String status,
            String translatedText,
            TranslationDirection direction,
            String detectedInputType,
            String message
    ) {
        this.status = status;
        this.translatedText = translatedText;
        this.direction = direction;
        this.detectedInputType = detectedInputType;
        this.message = message;
    }

    public static BrailleResponse success(String translatedText) {
        return new BrailleResponse(
                "success",
                translatedText,
                null,
                null,
                null
        );
    }

    public static BrailleResponse success(
            String translatedText,
            TranslationDirection direction,
            String detectedInputType
    ) {
        return new BrailleResponse(
                "success",
                translatedText,
                direction,
                detectedInputType,
                null
        );
    }

    public static BrailleResponse error(String message) {
        return new BrailleResponse(
                "error",
                null,
                null,
                null,
                message
        );
    }

    public String getStatus() {
        return status;
    }

    public String getTranslatedText() {
        return translatedText;
    }

    public TranslationDirection getDirection() {
        return direction;
    }

    public String getDetectedInputType() {
        return detectedInputType;
    }

    public String getMessage() {
        return message;
    }
}
