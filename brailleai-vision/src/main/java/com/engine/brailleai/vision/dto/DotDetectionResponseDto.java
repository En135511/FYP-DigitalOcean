package com.engine.brailleai.vision.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class DotDetectionResponseDto {

    // Accept common Python keys without changing the rest of your code
    @JsonAlias({"dots", "detections", "points", "predictions"})
    private List<DetectedDotDto> dots;

    @JsonAlias({"imageWidth", "image_width", "width"})
    private int imageWidth;

    @JsonAlias({"imageHeight", "image_height", "height"})
    private int imageHeight;

    @JsonAlias({"brailleUnicode", "braille_unicode", "unicode"})
    private String brailleUnicode;

    @JsonAlias({"modelType", "model_type"})
    private String modelType;

    @JsonAlias({"uncertainCellsCount", "uncertain_cells_count"})
    private Integer uncertainCellsCount;

    @JsonAlias({"reviewRecommended", "review_recommended"})
    private Boolean reviewRecommended;

    @JsonAlias({"qualityWarning", "quality_warning"})
    private String qualityWarning;

    public List<DetectedDotDto> getDots() {
        return dots;
    }

    public void setDots(List<DetectedDotDto> dots) {
        this.dots = dots;
    }

    public int getImageWidth() {
        return imageWidth;
    }

    public void setImageWidth(int imageWidth) {
        this.imageWidth = imageWidth;
    }

    public int getImageHeight() {
        return imageHeight;
    }

    public void setImageHeight(int imageHeight) {
        this.imageHeight = imageHeight;
    }

    public String getBrailleUnicode() {
        return brailleUnicode;
    }

    public void setBrailleUnicode(String brailleUnicode) {
        this.brailleUnicode = brailleUnicode;
    }

    public String getModelType() {
        return modelType;
    }

    public void setModelType(String modelType) {
        this.modelType = modelType;
    }

    public Integer getUncertainCellsCount() {
        return uncertainCellsCount;
    }

    public void setUncertainCellsCount(Integer uncertainCellsCount) {
        this.uncertainCellsCount = uncertainCellsCount;
    }

    public Boolean getReviewRecommended() {
        return reviewRecommended;
    }

    public void setReviewRecommended(Boolean reviewRecommended) {
        this.reviewRecommended = reviewRecommended;
    }

    public String getQualityWarning() {
        return qualityWarning;
    }

    public void setQualityWarning(String qualityWarning) {
        this.qualityWarning = qualityWarning;
    }
}
