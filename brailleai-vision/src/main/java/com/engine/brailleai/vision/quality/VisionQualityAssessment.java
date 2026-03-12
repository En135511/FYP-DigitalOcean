package com.engine.brailleai.vision.quality;

/**
 * Quality result for a single vision transcription attempt.
 */
public class VisionQualityAssessment {

    private final double score;
    private final boolean lowConfidence;
    private final String warning;

    public VisionQualityAssessment(double score, boolean lowConfidence, String warning) {
        this.score = score;
        this.lowConfidence = lowConfidence;
        this.warning = warning;
    }

    public double getScore() {
        return score;
    }

    public boolean isLowConfidence() {
        return lowConfidence;
    }

    public String getWarning() {
        return warning;
    }
}
