package com.engine.brailleai.vision.quality;

import com.engine.brailleai.vision.braille.BrailleCell;
import com.engine.brailleai.vision.dto.DetectedDotDto;
import com.engine.brailleai.vision.dto.DotDetectionResponseDto;
import com.engine.brailleai.vision.normalization.NormalizedDot;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Heuristic scoring of vision pipeline results for fallback selection and warnings.
 */
public class VisionQualityAssessor {

    public VisionQualityAssessment assess(
            DotDetectionResponseDto detectionResponse,
            List<NormalizedDot> normalizedDots,
            List<BrailleCell> cells,
            String translatedText
    ) {
        int dotsCount = detectionResponse == null || detectionResponse.getDots() == null
                ? 0
                : detectionResponse.getDots().size();
        int normalizedCount = normalizedDots == null ? 0 : normalizedDots.size();
        int cellsCount = cells == null ? 0 : cells.size();
        int lineCount = countLines(cells, normalizedDots);

        double avgConfidence = averageConfidence(detectionResponse == null ? null : detectionResponse.getDots());
        double textReadability = textReadability(translatedText);

        double score = 0.0;
        score += clamp01(dotsCount / 900.0) * 25.0;
        score += clamp01(avgConfidence) * 25.0;
        score += clamp01(normalizedCount / Math.max(1.0, dotsCount * 0.55)) * 15.0;
        score += clamp01(cellsCount / Math.max(1.0, normalizedCount * 0.35)) * 15.0;
        score += clamp01(lineCount / 10.0) * 10.0;
        score += clamp01(textReadability) * 10.0;

        if (dotsCount > 250 && lineCount <= 1) {
            score -= 20.0;
        }
        if (dotsCount > 500 && lineCount < 4) {
            score -= 15.0;
        }
        if (dotsCount > 150 && cellsCount < 10) {
            score -= 15.0;
        }

        score = Math.max(0.0, Math.min(100.0, score));

        boolean lowConfidence = score < 48.0
                || (dotsCount > 250 && lineCount <= 1)
                || (translatedText != null && !translatedText.isBlank() && textReadability < 0.35);

        String warning = lowConfidence
                ? "Low confidence transcription detected. Capture a flat, shadow-free, high-resolution page, keep camera square to the paper, and avoid pen marks over dots."
                : null;

        return new VisionQualityAssessment(score, lowConfidence, warning);
    }

    private int countLines(List<BrailleCell> cells, List<NormalizedDot> normalizedDots) {
        if (cells != null && !cells.isEmpty()) {
            Set<Integer> rows = new HashSet<>();
            for (BrailleCell cell : cells) {
                rows.add(cell.getCellRow());
            }
            return rows.size();
        }

        if (normalizedDots != null && !normalizedDots.isEmpty()) {
            Set<Integer> rows = new HashSet<>();
            for (NormalizedDot dot : normalizedDots) {
                rows.add(dot.getRow() / 3);
            }
            return rows.size();
        }

        return 0;
    }

    private double averageConfidence(List<DetectedDotDto> dots) {
        if (dots == null || dots.isEmpty()) {
            return 0.0;
        }
        double sum = 0.0;
        for (DetectedDotDto dot : dots) {
            sum += dot.getConfidence();
        }
        return sum / dots.size();
    }

    private double textReadability(String text) {
        if (text == null || text.isBlank()) {
            return 0.0;
        }
        int accepted = 0;
        int total = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (Character.isWhitespace(ch)) {
                continue;
            }
            total++;
            if (Character.isLetterOrDigit(ch) || isCommonPunctuation(ch)) {
                accepted++;
            }
        }
        if (total == 0) {
            return 0.0;
        }
        return (double) accepted / total;
    }

    private boolean isCommonPunctuation(char ch) {
        return ".,;:?!'\"()[]{}-_/`".indexOf(ch) >= 0;
    }

    private double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
