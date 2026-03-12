package com.engine.brailleai.api.dto;

/**
 * Raw detected dot coordinate from the vision detector.
 */
public class VisionDetectedDot {

    private double x;
    private double y;
    private double confidence;

    public VisionDetectedDot() {
    }

    public VisionDetectedDot(double x, double y, double confidence) {
        this.x = x;
        this.y = y;
        this.confidence = confidence;
    }

    public double getX() {
        return x;
    }

    public void setX(double x) {
        this.x = x;
    }

    public double getY() {
        return y;
    }

    public void setY(double y) {
        this.y = y;
    }

    public double getConfidence() {
        return confidence;
    }

    public void setConfidence(double confidence) {
        this.confidence = confidence;
    }
}
