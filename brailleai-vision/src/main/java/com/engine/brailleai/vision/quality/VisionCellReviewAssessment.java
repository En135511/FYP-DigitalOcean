package com.engine.brailleai.vision.quality;

import java.util.List;

/**
 * Cell-level quality assessment used to drive human review cues.
 */
public class VisionCellReviewAssessment {

    private final int lowConfidenceCellsCount;
    private final boolean reviewRecommended;
    private final List<VisionCellFlag> lowConfidenceCells;

    public VisionCellReviewAssessment(
            int lowConfidenceCellsCount,
            boolean reviewRecommended,
            List<VisionCellFlag> lowConfidenceCells
    ) {
        this.lowConfidenceCellsCount = lowConfidenceCellsCount;
        this.reviewRecommended = reviewRecommended;
        this.lowConfidenceCells = lowConfidenceCells == null ? List.of() : lowConfidenceCells;
    }

    public int getLowConfidenceCellsCount() {
        return lowConfidenceCellsCount;
    }

    public boolean isReviewRecommended() {
        return reviewRecommended;
    }

    public List<VisionCellFlag> getLowConfidenceCells() {
        return lowConfidenceCells;
    }
}
