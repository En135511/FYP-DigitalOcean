package com.engine.brailleai.vision.quality;

/**
 * Geometry preflight output used to decide fallback behavior before translation.
 */
public class VisionGeometryPreflightAssessment {

    private final boolean pageImageLikely;
    private final boolean highVariance;
    private final double varianceScore;
    private final int estimatedLineCount;
    private final String warning;

    public VisionGeometryPreflightAssessment(
            boolean pageImageLikely,
            boolean highVariance,
            double varianceScore,
            int estimatedLineCount,
            String warning
    ) {
        this.pageImageLikely = pageImageLikely;
        this.highVariance = highVariance;
        this.varianceScore = varianceScore;
        this.estimatedLineCount = estimatedLineCount;
        this.warning = warning;
    }

    public boolean isPageImageLikely() {
        return pageImageLikely;
    }

    public boolean isHighVariance() {
        return highVariance;
    }

    public double getVarianceScore() {
        return varianceScore;
    }

    public int getEstimatedLineCount() {
        return estimatedLineCount;
    }

    public String getWarning() {
        return warning;
    }
}
