package com.engine.brailleai.vision.quality;

import com.engine.brailleai.vision.dto.DetectedDotDto;
import com.engine.brailleai.vision.dto.DotDetectionResponseDto;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Preflight geometry checks used to drive variant fallback and segmentation logic.
 */
public class VisionGeometryPreflightAssessor {

    private static final int PAGE_MIN_WIDTH = 700;
    private static final int PAGE_MIN_HEIGHT = 900;
    private static final long PAGE_MIN_UPLOAD_BYTES = 100_000L;
    private static final int PAGE_MIN_DOTS = 120;

    public VisionGeometryPreflightAssessment assess(
            DotDetectionResponseDto detectionResponse,
            int imageWidth,
            int imageHeight,
            long uploadBytes
    ) {
        List<DetectedDotDto> dots = detectionResponse == null || detectionResponse.getDots() == null
                ? List.of()
                : detectionResponse.getDots().stream().filter(d -> d != null).toList();

        int width = imageWidth > 0
                ? imageWidth
                : detectionResponse == null ? 0 : detectionResponse.getImageWidth();
        int height = imageHeight > 0
                ? imageHeight
                : detectionResponse == null ? 0 : detectionResponse.getImageHeight();

        int estimatedLines = estimateLineCount(dots);
        double varianceScore = estimateVarianceScore(dots);
        boolean highVariance = varianceScore >= 0.78
                || (dots.size() >= 140 && estimatedLines <= 1);

        boolean pageDimensions = width >= PAGE_MIN_WIDTH && height >= PAGE_MIN_HEIGHT;
        boolean hasPageContent = dots.size() >= PAGE_MIN_DOTS || estimatedLines >= 3;
        boolean pageImageLikely = pageDimensions
                && uploadBytes >= PAGE_MIN_UPLOAD_BYTES
                && hasPageContent;

        String warning = null;
        if (highVariance) {
            warning = "Detected unstable dot geometry before translation. Recapture with flatter framing and lower glare.";
        }

        return new VisionGeometryPreflightAssessment(
                pageImageLikely,
                highVariance,
                varianceScore,
                estimatedLines,
                warning
        );
    }

    private int estimateLineCount(List<DetectedDotDto> dots) {
        if (dots == null || dots.size() < 9) {
            return dots == null || dots.isEmpty() ? 0 : 1;
        }

        List<Double> ys = dots.stream()
                .map(DetectedDotDto::getY)
                .sorted()
                .toList();

        double eps = estimateRowClusterEps(ys);
        int rowBands = countClusters(ys, eps);
        if (rowBands <= 0) {
            return 0;
        }
        return Math.max(1, (int) Math.round(rowBands / 3.0));
    }

    private double estimateVarianceScore(List<DetectedDotDto> dots) {
        if (dots == null || dots.size() < 12) {
            return 0.0;
        }

        List<Double> nnDistances = nearestNeighborDistances(dots);
        if (nnDistances.isEmpty()) {
            return 0.0;
        }

        nnDistances.sort(Double::compareTo);
        double p10 = percentile(nnDistances, 0.10);
        double p50 = percentile(nnDistances, 0.50);
        double p90 = percentile(nnDistances, 0.90);
        double mean = nnDistances.stream().mapToDouble(v -> v).average().orElse(0.0);
        double std = stdDev(nnDistances, mean);

        double spreadRatio = p90 / Math.max(1e-6, p10);
        double cv = std / Math.max(1e-6, mean);
        double medianShift = Math.abs(p90 - p50) / Math.max(1e-6, p50);

        double spreadComponent = clamp01((spreadRatio - 2.2) / 2.4);
        double cvComponent = clamp01((cv - 0.45) / 0.65);
        double medianComponent = clamp01((medianShift - 0.30) / 0.90);

        return clamp01((spreadComponent * 0.5) + (cvComponent * 0.35) + (medianComponent * 0.15));
    }

    private List<Double> nearestNeighborDistances(List<DetectedDotDto> dots) {
        List<Double> out = new ArrayList<>();
        for (int i = 0; i < dots.size(); i++) {
            DetectedDotDto base = dots.get(i);
            double best = Double.MAX_VALUE;
            for (int j = 0; j < dots.size(); j++) {
                if (i == j) {
                    continue;
                }
                DetectedDotDto other = dots.get(j);
                double dist = Math.hypot(base.getX() - other.getX(), base.getY() - other.getY());
                if (dist > 0.0 && dist < best) {
                    best = dist;
                }
            }
            if (best < Double.MAX_VALUE) {
                out.add(best);
            }
        }
        return out;
    }

    private double estimateRowClusterEps(List<Double> ys) {
        if (ys == null || ys.size() < 2) {
            return 6.0;
        }
        List<Double> gaps = new ArrayList<>();
        for (int i = 1; i < ys.size(); i++) {
            double gap = ys.get(i) - ys.get(i - 1);
            if (gap > 0.0) {
                gaps.add(gap);
            }
        }
        if (gaps.isEmpty()) {
            return 6.0;
        }
        gaps.sort(Comparator.naturalOrder());
        double q25 = percentile(gaps, 0.25);
        return clamp(q25 * 1.25, 2.5, 11.5);
    }

    private int countClusters(List<Double> sortedValues, double eps) {
        if (sortedValues == null || sortedValues.isEmpty()) {
            return 0;
        }
        int clusters = 1;
        for (int i = 1; i < sortedValues.size(); i++) {
            if (sortedValues.get(i) - sortedValues.get(i - 1) > eps) {
                clusters++;
            }
        }
        return clusters;
    }

    private double percentile(List<Double> sortedValues, double q) {
        if (sortedValues == null || sortedValues.isEmpty()) {
            return 0.0;
        }
        double qq = Math.max(0.0, Math.min(1.0, q));
        int index = (int) Math.round(qq * (sortedValues.size() - 1));
        index = Math.max(0, Math.min(index, sortedValues.size() - 1));
        return sortedValues.get(index);
    }

    private double stdDev(List<Double> values, double mean) {
        if (values == null || values.isEmpty()) {
            return 0.0;
        }
        double sum = 0.0;
        for (double value : values) {
            double d = value - mean;
            sum += d * d;
        }
        return Math.sqrt(sum / values.size());
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private double clamp01(double value) {
        return clamp(value, 0.0, 1.0);
    }
}
