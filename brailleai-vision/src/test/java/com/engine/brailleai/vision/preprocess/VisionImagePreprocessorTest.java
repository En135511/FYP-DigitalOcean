package com.engine.brailleai.vision.preprocess;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

class VisionImagePreprocessorTest {

    private final VisionImagePreprocessor preprocessor = new VisionImagePreprocessor();

    @Test
    void buildsEnhancedVariantsAndSuppressesRedAnnotations() throws Exception {
        BufferedImage source = new BufferedImage(60, 40, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = source.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, source.getWidth(), source.getHeight());
        g.setColor(Color.BLACK);
        g.fillOval(10, 10, 6, 6);
        g.setColor(new Color(220, 35, 35));
        g.fillRect(5, 24, 40, 3);
        g.dispose();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(source, "png", out);

        List<VisionImageVariant> variants = preprocessor.buildVariants(
                "sample.png",
                "image/png",
                out.toByteArray()
        );

        assertTrue(variants.size() >= 4);

        VisionImageVariant enhanced = variants.stream()
                .filter(v -> "enhanced-contrast".equals(v.getLabel()))
                .findFirst()
                .orElseThrow();
        VisionImageVariant segmented = variants.stream()
                .filter(v -> "adaptive-segmented".equals(v.getLabel()))
                .findFirst()
                .orElseThrow();

        BufferedImage enhancedImage = ImageIO.read(new ByteArrayInputStream(enhanced.getBytes()));
        int redLinePixel = enhancedImage.getRaster().getSample(20, 25, 0);
        int blackDotPixel = enhancedImage.getRaster().getSample(12, 12, 0);
        BufferedImage segmentedImage = ImageIO.read(new ByteArrayInputStream(segmented.getBytes()));
        Set<Integer> bucket = new HashSet<>();
        for (int y = 0; y < segmentedImage.getHeight(); y++) {
            for (int x = 0; x < segmentedImage.getWidth(); x++) {
                int value = segmentedImage.getRaster().getSample(x, y, 0);
                if (value < 20) {
                    bucket.add(0);
                } else if (value > 235) {
                    bucket.add(255);
                } else {
                    bucket.add(128);
                }
            }
        }

        assertTrue(redLinePixel > 180, "Red annotation should be lightened.");
        assertTrue(blackDotPixel < 170, "Braille dot should remain dark enough for detection.");
        assertTrue(bucket.contains(0), "Segmented variant should keep dark foreground pixels.");
        assertTrue(bucket.contains(255), "Segmented variant should keep light background pixels.");
    }
}
