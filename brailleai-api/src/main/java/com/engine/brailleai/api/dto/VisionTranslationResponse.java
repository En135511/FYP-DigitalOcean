package com.engine.brailleai.api.dto;

import java.util.List;

/**
 * Response payload for image-based translation requests.
 */
public class VisionTranslationResponse {

    private String brailleUnicode;
    private String translatedText;
    private String qualityWarning;
    private Integer detectedDotsCount;
    private List<VisionDetectedDot> detectedDots;
    private Integer lowConfidenceCellsCount;
    private Boolean reviewRecommended;
    private List<VisionLowConfidenceCell> lowConfidenceCells;

    public VisionTranslationResponse() {}

    public VisionTranslationResponse(String brailleUnicode, String translatedText) {
        this(brailleUnicode, translatedText, null);
    }

    public VisionTranslationResponse(
            String brailleUnicode,
            String translatedText,
            String qualityWarning
    ) {
        this.brailleUnicode = brailleUnicode;
        this.translatedText = translatedText;
        this.qualityWarning = qualityWarning;
    }

    public String getBrailleUnicode() {
        return brailleUnicode;
    }

    public void setBrailleUnicode(String brailleUnicode) {
        this.brailleUnicode = brailleUnicode;
    }

    public String getTranslatedText() {
        return translatedText;
    }

    public void setTranslatedText(String translatedText) {
        this.translatedText = translatedText;
    }

    public String getQualityWarning() {
        return qualityWarning;
    }

    public void setQualityWarning(String qualityWarning) {
        this.qualityWarning = qualityWarning;
    }

    public Integer getDetectedDotsCount() {
        return detectedDotsCount;
    }

    public void setDetectedDotsCount(Integer detectedDotsCount) {
        this.detectedDotsCount = detectedDotsCount;
    }

    public List<VisionDetectedDot> getDetectedDots() {
        return detectedDots;
    }

    public void setDetectedDots(List<VisionDetectedDot> detectedDots) {
        this.detectedDots = detectedDots;
    }

    public Integer getLowConfidenceCellsCount() {
        return lowConfidenceCellsCount;
    }

    public void setLowConfidenceCellsCount(Integer lowConfidenceCellsCount) {
        this.lowConfidenceCellsCount = lowConfidenceCellsCount;
    }

    public Boolean getReviewRecommended() {
        return reviewRecommended;
    }

    public void setReviewRecommended(Boolean reviewRecommended) {
        this.reviewRecommended = reviewRecommended;
    }

    public List<VisionLowConfidenceCell> getLowConfidenceCells() {
        return lowConfidenceCells;
    }

    public void setLowConfidenceCells(List<VisionLowConfidenceCell> lowConfidenceCells) {
        this.lowConfidenceCells = lowConfidenceCells;
    }
}
