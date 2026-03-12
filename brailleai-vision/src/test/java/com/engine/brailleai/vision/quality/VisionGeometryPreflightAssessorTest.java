package com.engine.brailleai.vision.quality;

import com.engine.brailleai.vision.dto.DetectedDotDto;
import com.engine.brailleai.vision.dto.DotDetectionResponseDto;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VisionGeometryPreflightAssessorTest {

    private final VisionGeometryPreflightAssessor assessor = new VisionGeometryPreflightAssessor();

    @Test
    void doesNotClassifyShortLineAsPageByBytesOnly() {
        DotDetectionResponseDto response = new DotDetectionResponseDto();
        response.setImageWidth(1200);
        response.setImageHeight(1600);
        response.setDots(buildSingleLineDots(12, 28.0, 60.0, 180.0));

        VisionGeometryPreflightAssessment assessment = assessor.assess(
                response,
                1200,
                1600,
                420_000L
        );

        assertFalse(assessment.isPageImageLikely());
    }

    @Test
    void flagsNoisyDocumentAsHighVariance() {
        DotDetectionResponseDto response = new DotDetectionResponseDto();
        response.setImageWidth(1600);
        response.setImageHeight(2200);
        response.setDots(buildNoisyDots());

        VisionGeometryPreflightAssessment assessment = assessor.assess(
                response,
                1600,
                2200,
                520_000L
        );

        assertTrue(assessment.isHighVariance());
        assertTrue(assessment.getVarianceScore() >= 0.78);
    }

    @Test
    void keepsStableSingleLineNearBaseline() {
        DotDetectionResponseDto response = new DotDetectionResponseDto();
        response.setImageWidth(1000);
        response.setImageHeight(450);
        response.setDots(buildSingleLineDots(18, 24.0, 80.0, 130.0));

        VisionGeometryPreflightAssessment assessment = assessor.assess(
                response,
                1000,
                450,
                90_000L
        );

        assertFalse(assessment.isHighVariance());
        assertTrue(assessment.getVarianceScore() < 0.65);
    }

    private List<DetectedDotDto> buildSingleLineDots(
            int cells,
            double step,
            double startX,
            double baselineY
    ) {
        List<DetectedDotDto> dots = new ArrayList<>();
        Random random = new Random(7);
        for (int c = 0; c < cells; c++) {
            double x = startX + (c * step);
            double y1 = baselineY + random.nextDouble() * 1.2;
            double y2 = baselineY + 12.0 + random.nextDouble() * 1.2;
            double y3 = baselineY + 24.0 + random.nextDouble() * 1.2;
            dots.add(new DetectedDotDto(x, y1, 0.88));
            dots.add(new DetectedDotDto(x, y2, 0.87));
            dots.add(new DetectedDotDto(x, y3, 0.86));
        }
        return dots;
    }

    private List<DetectedDotDto> buildNoisyDots() {
        List<DetectedDotDto> dots = new ArrayList<>();
        Random random = new Random(42);

        for (int i = 0; i < 120; i++) {
            double x = 260.0 + (random.nextGaussian() * 7.5);
            double y = 420.0 + (random.nextGaussian() * 6.5);
            dots.add(new DetectedDotDto(x, y, 0.72));
        }

        for (int i = 0; i < 60; i++) {
            double x = 50.0 + random.nextDouble() * 1450.0;
            double y = 80.0 + random.nextDouble() * 2000.0;
            dots.add(new DetectedDotDto(x, y, 0.55));
        }

        return dots;
    }
}
