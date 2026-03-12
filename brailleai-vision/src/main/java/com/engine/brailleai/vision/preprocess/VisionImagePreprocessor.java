package com.engine.brailleai.vision.preprocess;

import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds image variants used by fallback detection to improve noisy page handling.
 */
public class VisionImagePreprocessor {

    private static final String DEFAULT_FILENAME = "upload-image";
    private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";

    public List<VisionImageVariant> buildVariants(MultipartFile image) {
        try {
            return buildVariants(
                    image == null ? null : image.getOriginalFilename(),
                    image == null ? null : image.getContentType(),
                    image == null ? null : image.getBytes()
            );
        } catch (IOException ex) {
            throw new RuntimeException("Failed to read uploaded image bytes", ex);
        }
    }

    public List<VisionImageVariant> buildVariants(
            String filename,
            String contentType,
            byte[] imageBytes
    ) {
        if (imageBytes == null || imageBytes.length == 0) {
            return List.of();
        }

        String safeFilename = safeFilename(filename);
        String safeContentType = safeContentType(contentType);
        List<VisionImageVariant> variants = new ArrayList<>();

        BufferedImage sourceImage = decode(imageBytes);
        int width = sourceImage == null ? -1 : sourceImage.getWidth();
        int height = sourceImage == null ? -1 : sourceImage.getHeight();

        variants.add(new VisionImageVariant(
                "original",
                safeFilename,
                safeContentType,
                imageBytes,
                width,
                height
        ));

        if (sourceImage == null) {
            return variants;
        }

        BufferedImage contrastEnhanced = buildContrastEnhanced(sourceImage);
        byte[] enhancedBytes = encodePng(contrastEnhanced);
        if (enhancedBytes.length > 0) {
            variants.add(new VisionImageVariant(
                    "enhanced-contrast",
                    appendSuffix(safeFilename, "-enhanced.png"),
                    "image/png",
                    enhancedBytes,
                    width,
                    height
            ));
        }

        BufferedImage binary = buildBinaryVariant(contrastEnhanced);
        byte[] binaryBytes = encodePng(binary);
        if (binaryBytes.length > 0) {
            variants.add(new VisionImageVariant(
                    "binary-clean",
                    appendSuffix(safeFilename, "-binary.png"),
                    "image/png",
                    binaryBytes,
                    width,
                    height
            ));
        }

        BufferedImage segmented = buildAdaptiveSegmentedVariant(contrastEnhanced);
        byte[] segmentedBytes = encodePng(segmented);
        if (segmentedBytes.length > 0) {
            variants.add(new VisionImageVariant(
                    "adaptive-segmented",
                    appendSuffix(safeFilename, "-segmented.png"),
                    "image/png",
                    segmentedBytes,
                    width,
                    height
            ));
        }

        return variants;
    }

    private BufferedImage buildContrastEnhanced(BufferedImage source) {
        int width = source.getWidth();
        int height = source.getHeight();
        BufferedImage gray = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);

        int[] histogram = new int[256];
        int total = width * height;

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgb = source.getRGB(x, y);
                int r = (rgb >>> 16) & 0xFF;
                int g = (rgb >>> 8) & 0xFF;
                int b = rgb & 0xFF;

                if (isLikelyRedInk(r, g, b)) {
                    r = 255;
                    g = 255;
                    b = 255;
                }

                int luminance = clamp((int) Math.round(0.2126 * r + 0.7152 * g + 0.0722 * b), 0, 255);
                gray.getRaster().setSample(x, y, 0, luminance);
                histogram[luminance]++;
            }
        }

        int low = percentile(histogram, total, 0.02);
        int high = percentile(histogram, total, 0.98);
        if (high <= low) {
            return gray;
        }

        BufferedImage stretched = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int value = gray.getRaster().getSample(x, y, 0);
                int adjusted = (value - low) * 255 / (high - low);
                stretched.getRaster().setSample(x, y, 0, clamp(adjusted, 0, 255));
            }
        }
        return stretched;
    }

    private BufferedImage buildBinaryVariant(BufferedImage contrastEnhancedGray) {
        int width = contrastEnhancedGray.getWidth();
        int height = contrastEnhancedGray.getHeight();
        int[] histogram = new int[256];

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                histogram[contrastEnhancedGray.getRaster().getSample(x, y, 0)]++;
            }
        }

        int threshold = otsuThreshold(histogram, width * height);
        BufferedImage binary = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int value = contrastEnhancedGray.getRaster().getSample(x, y, 0);
                binary.getRaster().setSample(x, y, 0, value < threshold ? 0 : 255);
            }
        }
        return removeIsolatedNoise(binary);
    }

    private BufferedImage removeIsolatedNoise(BufferedImage binary) {
        int width = binary.getWidth();
        int height = binary.getHeight();
        BufferedImage cleaned = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int value = binary.getRaster().getSample(x, y, 0);
                if (value != 0 || x == 0 || y == 0 || x == width - 1 || y == height - 1) {
                    cleaned.getRaster().setSample(x, y, 0, value);
                    continue;
                }

                int blackNeighbors = 0;
                for (int yy = y - 1; yy <= y + 1; yy++) {
                    for (int xx = x - 1; xx <= x + 1; xx++) {
                        if (xx == x && yy == y) {
                            continue;
                        }
                        if (binary.getRaster().getSample(xx, yy, 0) == 0) {
                            blackNeighbors++;
                        }
                    }
                }
                cleaned.getRaster().setSample(x, y, 0, blackNeighbors <= 1 ? 255 : 0);
            }
        }
        return cleaned;
    }

    private BufferedImage buildAdaptiveSegmentedVariant(BufferedImage gray) {
        int width = gray.getWidth();
        int height = gray.getHeight();
        int radius = clamp(Math.min(width, height) / 28, 8, 28);
        int bias = 10;

        long[][] integral = buildIntegralImage(gray);
        BufferedImage segmented = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);

        for (int y = 0; y < height; y++) {
            int y0 = Math.max(0, y - radius);
            int y1 = Math.min(height - 1, y + radius);
            for (int x = 0; x < width; x++) {
                int x0 = Math.max(0, x - radius);
                int x1 = Math.min(width - 1, x + radius);

                long area = (long) (x1 - x0 + 1) * (y1 - y0 + 1);
                long sum = sumRegion(integral, x0, y0, x1, y1);
                int localMean = area <= 0 ? 255 : (int) Math.round((double) sum / area);
                int value = gray.getRaster().getSample(x, y, 0);

                segmented.getRaster().setSample(x, y, 0, value <= (localMean - bias) ? 0 : 255);
            }
        }

        return removeIsolatedNoise(majorityClean(segmented));
    }

    private BufferedImage majorityClean(BufferedImage binary) {
        int width = binary.getWidth();
        int height = binary.getHeight();
        BufferedImage cleaned = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int dark = 0;
                int samples = 0;
                for (int yy = Math.max(0, y - 1); yy <= Math.min(height - 1, y + 1); yy++) {
                    for (int xx = Math.max(0, x - 1); xx <= Math.min(width - 1, x + 1); xx++) {
                        samples++;
                        if (binary.getRaster().getSample(xx, yy, 0) < 128) {
                            dark++;
                        }
                    }
                }
                cleaned.getRaster().setSample(x, y, 0, dark >= Math.max(2, samples / 3) ? 0 : 255);
            }
        }

        return cleaned;
    }

    private long[][] buildIntegralImage(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        long[][] integral = new long[height + 1][width + 1];

        for (int y = 1; y <= height; y++) {
            long rowSum = 0;
            for (int x = 1; x <= width; x++) {
                rowSum += image.getRaster().getSample(x - 1, y - 1, 0);
                integral[y][x] = integral[y - 1][x] + rowSum;
            }
        }
        return integral;
    }

    private long sumRegion(long[][] integral, int x0, int y0, int x1, int y1) {
        int xa = x0;
        int ya = y0;
        int xb = x1 + 1;
        int yb = y1 + 1;
        return integral[yb][xb] - integral[ya][xb] - integral[yb][xa] + integral[ya][xa];
    }

    private boolean isLikelyRedInk(int r, int g, int b) {
        return r > 90 && r > (int) (g * 1.20) && r > (int) (b * 1.20);
    }

    private int percentile(int[] histogram, int total, double ratio) {
        int target = (int) Math.round(total * ratio);
        int cumulative = 0;
        for (int i = 0; i < histogram.length; i++) {
            cumulative += histogram[i];
            if (cumulative >= target) {
                return i;
            }
        }
        return histogram.length - 1;
    }

    private int otsuThreshold(int[] histogram, int total) {
        double sum = 0.0;
        for (int i = 0; i < histogram.length; i++) {
            sum += i * histogram[i];
        }

        double sumBackground = 0.0;
        int weightBackground = 0;
        int weightForeground;
        double maxVariance = -1.0;
        int threshold = 127;

        for (int i = 0; i < histogram.length; i++) {
            weightBackground += histogram[i];
            if (weightBackground == 0) {
                continue;
            }
            weightForeground = total - weightBackground;
            if (weightForeground == 0) {
                break;
            }

            sumBackground += (double) i * histogram[i];
            double meanBackground = sumBackground / weightBackground;
            double meanForeground = (sum - sumBackground) / weightForeground;
            double varianceBetween = (double) weightBackground * weightForeground
                    * (meanBackground - meanForeground) * (meanBackground - meanForeground);

            if (varianceBetween > maxVariance) {
                maxVariance = varianceBetween;
                threshold = i;
            }
        }
        return threshold;
    }

    private BufferedImage decode(byte[] bytes) {
        try (ByteArrayInputStream in = new ByteArrayInputStream(bytes)) {
            return ImageIO.read(in);
        } catch (IOException ex) {
            return null;
        }
    }

    private byte[] encodePng(BufferedImage image) {
        if (image == null) {
            return new byte[0];
        }
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException ex) {
            return new byte[0];
        }
    }

    private String safeFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            return DEFAULT_FILENAME;
        }
        return filename.trim();
    }

    private String appendSuffix(String filename, String suffix) {
        if (filename == null || filename.isBlank()) {
            return DEFAULT_FILENAME + suffix;
        }
        return filename + suffix;
    }

    private String safeContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return DEFAULT_CONTENT_TYPE;
        }
        return contentType.trim();
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
