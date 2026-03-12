package com.engine.brailleai.vision.fusion;

import com.engine.brailleai.vision.dto.DetectedDotDto;
import com.engine.brailleai.vision.dto.DotDetectionResponseDto;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Fuses dot detections from multiple image variants and keeps stable consensus dots.
 */
public class VisionDotConsensusFuser {

    private static final double MIN_INPUT_CONFIDENCE = 0.02;
    private static final double DEFAULT_MERGE_DISTANCE = 3.6;
    private static final double MERGE_DISTANCE_MIN = 2.0;
    private static final double MERGE_DISTANCE_MAX = 6.2;
    private static final double HIGH_CONFIDENCE_SINGLETON = 0.78;
    private static final double VERY_HIGH_CONFIDENCE_SINGLETON = 0.92;
    private static final double LOW_SUPPORT_RATIO = 0.35;

    public DotDetectionResponseDto fuse(List<DotDetectionResponseDto> responses) {
        if (responses == null || responses.isEmpty()) {
            return emptyResponse();
        }
        double mergeDistance = estimateMergeDistance(responses);
        return fuse(responses, mergeDistance);
    }

    DotDetectionResponseDto fuse(List<DotDetectionResponseDto> responses, double mergeDistance) {
        if (responses == null || responses.isEmpty()) {
            return emptyResponse();
        }

        List<Cluster> clusters = new ArrayList<>();
        int width = 0;
        int height = 0;

        for (int sourceIndex = 0; sourceIndex < responses.size(); sourceIndex++) {
            DotDetectionResponseDto response = responses.get(sourceIndex);
            if (response == null || response.getDots() == null || response.getDots().isEmpty()) {
                continue;
            }

            width = Math.max(width, response.getImageWidth());
            height = Math.max(height, response.getImageHeight());

            for (DetectedDotDto dot : response.getDots()) {
                if (dot == null || dot.getConfidence() < MIN_INPUT_CONFIDENCE) {
                    continue;
                }
                assignToCluster(clusters, dot, sourceIndex, mergeDistance);
            }
        }

        if (clusters.isEmpty()) {
            return emptyResponse(width, height);
        }

        long consensusClusters = clusters.stream().filter(c -> c.support() >= 2).count();
        double consensusRatio = (double) consensusClusters / Math.max(1, clusters.size());
        boolean lowSupportScenario = consensusRatio < LOW_SUPPORT_RATIO;

        List<DetectedDotDto> fusedDots = new ArrayList<>();
        for (Cluster cluster : clusters) {
            int support = cluster.support();
            double maxConfidence = cluster.maxConfidence;

            boolean keep;
            if (support >= 2) {
                keep = true;
            } else if (lowSupportScenario) {
                keep = maxConfidence >= HIGH_CONFIDENCE_SINGLETON;
            } else {
                keep = maxConfidence >= VERY_HIGH_CONFIDENCE_SINGLETON;
            }

            if (!keep) {
                continue;
            }

            double x = cluster.weightedX / Math.max(1e-9, cluster.weightSum);
            double y = cluster.weightedY / Math.max(1e-9, cluster.weightSum);
            double avgConfidence = cluster.sumConfidence / Math.max(1, cluster.count);
            double fusedConfidence = clamp(Math.max(maxConfidence * 0.9, avgConfidence), 0.0, 1.0);

            fusedDots.add(new DetectedDotDto(x, y, fusedConfidence));
        }

        fusedDots.sort(Comparator
                .comparingDouble(DetectedDotDto::getY)
                .thenComparingDouble(DetectedDotDto::getX));

        DotDetectionResponseDto fused = new DotDetectionResponseDto();
        fused.setImageWidth(width);
        fused.setImageHeight(height);
        fused.setDots(fusedDots);
        fused.setModelType("dot-consensus-fused");
        return fused;
    }

    private void assignToCluster(
            List<Cluster> clusters,
            DetectedDotDto dot,
            int sourceIndex,
            double mergeDistance
    ) {
        int bestIndex = -1;
        double bestDistance = Double.MAX_VALUE;

        for (int i = clusters.size() - 1; i >= 0; i--) {
            Cluster cluster = clusters.get(i);
            double dx = dot.getX() - cluster.centerX();
            double dy = dot.getY() - cluster.centerY();
            double dist = Math.hypot(dx, dy);
            if (dist <= mergeDistance && dist < bestDistance) {
                bestDistance = dist;
                bestIndex = i;
            }
        }

        if (bestIndex < 0) {
            Cluster cluster = new Cluster();
            cluster.add(dot, sourceIndex);
            clusters.add(cluster);
            return;
        }

        clusters.get(bestIndex).add(dot, sourceIndex);
    }

    private double estimateMergeDistance(List<DotDetectionResponseDto> responses) {
        List<Double> nearest = new ArrayList<>();

        for (DotDetectionResponseDto response : responses) {
            if (response == null || response.getDots() == null || response.getDots().size() < 2) {
                continue;
            }

            List<DetectedDotDto> dots = response.getDots();
            for (int i = 0; i < dots.size(); i++) {
                DetectedDotDto base = dots.get(i);
                if (base == null) {
                    continue;
                }
                double best = Double.MAX_VALUE;
                for (int j = 0; j < dots.size(); j++) {
                    if (i == j) {
                        continue;
                    }
                    DetectedDotDto other = dots.get(j);
                    if (other == null) {
                        continue;
                    }
                    double dist = Math.hypot(base.getX() - other.getX(), base.getY() - other.getY());
                    if (dist > 0.0 && dist < best) {
                        best = dist;
                    }
                }
                if (best < Double.MAX_VALUE) {
                    nearest.add(best);
                }
            }
        }

        if (nearest.isEmpty()) {
            return DEFAULT_MERGE_DISTANCE;
        }
        nearest.sort(Double::compareTo);
        double q20 = nearest.get((int) Math.floor(0.20 * (nearest.size() - 1)));
        return clamp(q20 * 0.55, MERGE_DISTANCE_MIN, MERGE_DISTANCE_MAX);
    }

    private DotDetectionResponseDto emptyResponse() {
        return emptyResponse(0, 0);
    }

    private DotDetectionResponseDto emptyResponse(int width, int height) {
        DotDetectionResponseDto response = new DotDetectionResponseDto();
        response.setImageWidth(width);
        response.setImageHeight(height);
        response.setDots(List.of());
        response.setModelType("dot-consensus-fused");
        return response;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class Cluster {
        private double weightedX;
        private double weightedY;
        private double weightSum;
        private double sumConfidence;
        private double maxConfidence;
        private int count;
        private final Set<Integer> sources = new HashSet<>();

        private void add(DetectedDotDto dot, int sourceIndex) {
            double weight = Math.max(0.15, dot.getConfidence());
            weightedX += dot.getX() * weight;
            weightedY += dot.getY() * weight;
            weightSum += weight;
            sumConfidence += dot.getConfidence();
            maxConfidence = Math.max(maxConfidence, dot.getConfidence());
            count++;
            sources.add(sourceIndex);
        }

        private int support() {
            return sources.size();
        }

        private double centerX() {
            return weightedX / Math.max(1e-9, weightSum);
        }

        private double centerY() {
            return weightedY / Math.max(1e-9, weightSum);
        }
    }
}
