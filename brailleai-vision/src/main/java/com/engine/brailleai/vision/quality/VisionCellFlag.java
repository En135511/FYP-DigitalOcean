package com.engine.brailleai.vision.quality;

/**
 * Low-confidence cell marker for post-detection review.
 */
public class VisionCellFlag {

    private final int cellRow;
    private final int cellColumn;
    private final double confidence;
    private final String reason;

    public VisionCellFlag(int cellRow, int cellColumn, double confidence, String reason) {
        this.cellRow = cellRow;
        this.cellColumn = cellColumn;
        this.confidence = confidence;
        this.reason = reason;
    }

    public int getCellRow() {
        return cellRow;
    }

    public int getCellColumn() {
        return cellColumn;
    }

    public double getConfidence() {
        return confidence;
    }

    public String getReason() {
        return reason;
    }
}
