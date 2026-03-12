package com.engine.brailleai.vision.dto;

public class DetectedDotDto {

    private double x;
    private double y;
    private double confidence;

    // REQUIRED by Jackson
    public DetectedDotDto() {
    }

    // Used internally for geometry operations
    public DetectedDotDto(double x, double y, double confidence) {
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