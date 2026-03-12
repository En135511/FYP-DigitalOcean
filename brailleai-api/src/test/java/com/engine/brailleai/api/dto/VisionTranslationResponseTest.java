package com.engine.brailleai.api.dto;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VisionTranslationResponseTest {

    @Test
    void constructorAndAccessorsSupportOptionalQualityWarning() {
        VisionTranslationResponse response = new VisionTranslationResponse(
                "braille-cells",
                "ab",
                "low confidence"
        );

        assertEquals("braille-cells", response.getBrailleUnicode());
        assertEquals("ab", response.getTranslatedText());
        assertEquals("low confidence", response.getQualityWarning());
    }

    @Test
    void twoArgumentConstructorKeepsWarningNull() {
        VisionTranslationResponse response = new VisionTranslationResponse("braille-a", "a");
        assertNull(response.getQualityWarning());
    }

    @Test
    void supportsRawDetectedDotPayload() {
        VisionTranslationResponse response = new VisionTranslationResponse("x", "y");
        response.setDetectedDots(List.of(new VisionDetectedDot(10.0, 12.0, 0.8)));
        response.setDetectedDotsCount(1);

        assertEquals(1, response.getDetectedDotsCount());
        assertEquals(1, response.getDetectedDots().size());
        assertEquals(10.0, response.getDetectedDots().get(0).getX());
    }

    @Test
    void supportsLowConfidenceCellReviewMetadata() {
        VisionTranslationResponse response = new VisionTranslationResponse("x", "y");
        response.setLowConfidenceCellsCount(2);
        response.setReviewRecommended(true);
        response.setLowConfidenceCells(List.of(
                new VisionLowConfidenceCell(1, 4, 0.41, "weak-dot"),
                new VisionLowConfidenceCell(1, 5, 0.58, "low-cell-confidence")
        ));

        assertEquals(2, response.getLowConfidenceCellsCount());
        assertTrue(response.getReviewRecommended());
        assertEquals(2, response.getLowConfidenceCells().size());
        assertEquals("weak-dot", response.getLowConfidenceCells().get(0).getReason());
    }
}
