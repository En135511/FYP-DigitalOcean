package com.engine.brailleai.vision.preprocess;

/**
 * Image payload variant used for adaptive vision detection attempts.
 */
public class VisionImageVariant {

    private final String label;
    private final String filename;
    private final String contentType;
    private final byte[] bytes;
    private final int width;
    private final int height;

    public VisionImageVariant(
            String label,
            String filename,
            String contentType,
            byte[] bytes,
            int width,
            int height
    ) {
        this.label = label;
        this.filename = filename;
        this.contentType = contentType;
        this.bytes = bytes;
        this.width = width;
        this.height = height;
    }

    public String getLabel() {
        return label;
    }

    public String getFilename() {
        return filename;
    }

    public String getContentType() {
        return contentType;
    }

    public byte[] getBytes() {
        return bytes;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }
}
