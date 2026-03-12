package com.engine.brailleai.api.dto;

/**
 * Uncertain Braille cell returned for optional manual review.
 */
public class VisionLowConfidenceCell {

    private int cellRow;
    private int cellColumn;
    private double confidence;
    private String reason;

    public VisionLowConfidenceCell() {
    }

    public VisionLowConfidenceCell(int cellRow, int cellColumn, double confidence, String reason) {
        this.cellRow = cellRow;
        this.cellColumn = cellColumn;
        this.confidence = confidence;
        this.reason = reason;
    }

    public int getCellRow() {
        return cellRow;
    }

    public void setCellRow(int cellRow) {
        this.cellRow = cellRow;
    }

    public int getCellColumn() {
        return cellColumn;
    }

    public void setCellColumn(int cellColumn) {
        this.cellColumn = cellColumn;
    }

    public double getConfidence() {
        return confidence;
    }

    public void setConfidence(double confidence) {
        this.confidence = confidence;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }
}
