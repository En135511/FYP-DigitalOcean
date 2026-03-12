package com.engine.brailleai.vision.normalization;

/**
 * Represents a detected dot mapped to a normalized braille grid position.
 */
public class NormalizedDot {

    private final int row;
    private final int column;
    private final double confidence;

    public NormalizedDot(int row, int column, double confidence) {
        this.row = row;
        this.column = column;
        this.confidence = confidence;
    }

    public int getRow() {
        return row;
    }

    public int getColumn() {
        return column;
    }

    public double getConfidence() {
        return confidence;
    }
}
