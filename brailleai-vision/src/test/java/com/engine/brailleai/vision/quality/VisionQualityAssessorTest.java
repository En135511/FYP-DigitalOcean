package com.engine.brailleai.vision.quality;

import com.engine.brailleai.vision.braille.BrailleCell;
import com.engine.brailleai.vision.dto.DetectedDotDto;
import com.engine.brailleai.vision.dto.DotDetectionResponseDto;
import com.engine.brailleai.vision.normalization.NormalizedDot;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VisionQualityAssessorTest {

    private final VisionQualityAssessor assessor = new VisionQualityAssessor();

    @Test
    void marksStructuredOutputAsHigherQualityThanCollapsedOutput() {
        DotDetectionResponseDto highDetection = new DotDetectionResponseDto();
        highDetection.setImageWidth(1200);
        highDetection.setImageHeight(1500);
        highDetection.setDots(generateDots(12, 24, 3, 0.90, 40.0, 42.0));

        List<NormalizedDot> highNormalized = new ArrayList<>();
        for (int line = 0; line < 12; line++) {
            for (int cell = 0; cell < 24; cell++) {
                for (int row = 0; row < 3; row++) {
                    highNormalized.add(new NormalizedDot(line * 3 + row, cell * 2, 0.9));
                }
            }
        }

        List<BrailleCell> highCells = new ArrayList<>();
        for (int line = 0; line < 12; line++) {
            for (int cell = 0; cell < 24; cell++) {
                highCells.add(new BrailleCell(line, cell, Set.of(1, 2)));
            }
        }

        VisionQualityAssessment high = assessor.assess(
                highDetection,
                highNormalized,
                highCells,
                "This is a readable transcription with punctuation, words, and spaces."
        );

        DotDetectionResponseDto lowDetection = new DotDetectionResponseDto();
        lowDetection.setImageWidth(1200);
        lowDetection.setImageHeight(1500);
        lowDetection.setDots(generateDots(1, 400, 1, 0.55, 6.0, 3.0));

        List<NormalizedDot> lowNormalized = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            lowNormalized.add(new NormalizedDot(0, i % 20, 0.5));
        }
        List<BrailleCell> lowCells = List.of(
                new BrailleCell(0, 0, Set.of(1)),
                new BrailleCell(0, 1, Set.of(1))
        );

        VisionQualityAssessment low = assessor.assess(
                lowDetection,
                lowNormalized,
                lowCells,
                "@@@##$$%^^^"
        );

        assertTrue(high.getScore() > low.getScore());
        assertFalse(high.isLowConfidence());
        assertTrue(low.isLowConfidence());
        assertTrue(low.getWarning() != null && !low.getWarning().isBlank());
    }

    private List<DetectedDotDto> generateDots(
            int lines,
            int cellsPerLine,
            int rowsPerCell,
            double confidence,
            double xSpacing,
            double ySpacing
    ) {
        List<DetectedDotDto> dots = new ArrayList<>();
        for (int line = 0; line < lines; line++) {
            for (int cell = 0; cell < cellsPerLine; cell++) {
                for (int row = 0; row < rowsPerCell; row++) {
                    double x = 20 + cell * xSpacing + (row % 2) * 2.0;
                    double y = 20 + line * ySpacing + row * 7.5;
                    dots.add(new DetectedDotDto(x, y, confidence));
                }
            }
        }
        return dots;
    }
}
