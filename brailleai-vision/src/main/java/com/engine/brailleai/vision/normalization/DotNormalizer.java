package com.engine.brailleai.vision.normalization;

import com.engine.brailleai.vision.dto.DetectedDotDto;
import com.engine.brailleai.vision.dto.DotDetectionResponseDto;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Normalizes detected dot positions into a stable braille grid.
 * The algorithm deskews raw detections, infers row/column centers, and maps dots
 * into virtual cells. Multi-line detection is preferred; single-line is a fallback.
 */
public class DotNormalizer {

    private static final double MIN_CONFIDENCE = 0.05;

    // X eps from q20 nearest-neighbor.
    private static final double EPSX_FALLBACK = 8.0;
    private static final double EPSX_MIN = 2.5;
    private static final double EPSX_MAX = 9.5;

    // Y eps from q20 nearest-neighbor.
    private static final double EPSY_FALLBACK = 8.0;
    private static final double EPSY_MIN = 2.5;
    private static final double EPSY_MAX = 11.0;

    // Row center support thresholds.
    private static final int MIN_ROW_SUPPORT = 2;
    private static final int MIN_ROW_SUPPORT_RELAXED = 1;

    // Split row-centers into groups (separate text lines).
    private static final double LINE_BREAK_MULT = 1.8;

    // Snapping tolerance in Y.
    private static final double TOLY_MULT = 0.85;

    // Triple validation.
    private static final double TRIPLE_GAP_MULT = 2.6;

    // Bottom row synthesis.
    private static final int MIN_BOTTOM_ROW_SUPPORT = 1;

    // X snapping tolerance.
    private static final double TOLX_MIN = 8.0;

    /**
     * Normalizes detected dots into a grid of rows/columns.
     */
    public List<NormalizedDot> normalize(DotDetectionResponseDto response) {

        if (response == null || response.getDots() == null || response.getDots().isEmpty()) {
            return List.of();
        }

        List<DetectedDotDto> dots = response.getDots().stream()
                .filter(d -> d.getConfidence() >= MIN_CONFIDENCE)
                .collect(Collectors.toList());

        if (dots.isEmpty()) return List.of();

        // Deskew around the dot centroid.
        double angle = estimateSkewAngle(dots);
        double centerX = dots.stream().mapToDouble(DetectedDotDto::getX).average().orElse(0.0);
        double centerY = dots.stream().mapToDouble(DetectedDotDto::getY).average().orElse(0.0);

        List<DetectedDotDto> rotated = dots.stream()
                .map(d -> rotateDot(d, angle, centerX, centerY))
                .toList();

        // Primary: multi-line normalization.
        List<NormalizedDot> multi = normalizeMultiLine(rotated);
        if (multi != null && !multi.isEmpty()) {
            if (spansMultipleLines(multi)) {
                return multi;
            }
            List<NormalizedDot> single = normalizeSingleLine(rotated);
            if (single != null && !single.isEmpty()) {
                return single;
            }
            return multi;
        }

        // Fallback: single-line normalization.
        List<NormalizedDot> single = normalizeSingleLine(rotated);
        return (single == null) ? List.of() : single;
    }

    private List<NormalizedDot> normalizeMultiLine(List<DetectedDotDto> rotated) {

        // Infer Y row centers.
        List<Double> ySorted = rotated.stream().map(DetectedDotDto::getY).sorted().toList();
        double epsY = estimateEpsFromNearestNeighborQuantile(ySorted, 1.10, EPSY_FALLBACK, EPSY_MIN, EPSY_MAX);

        List<Double> rowCenters = cluster1D(ySorted, epsY);

        rowCenters = filterCentersBySupport(rotated, rowCenters, epsY, true, MIN_ROW_SUPPORT);
        if (rowCenters.size() < 2) {
            rowCenters = filterCentersBySupport(rotated, rowCenters, epsY, true, MIN_ROW_SUPPORT_RELAXED);
        }

        // If we only see 2 physical dot-rows (common for a-j), synthesize the 3rd.
        if (rowCenters.size() == 2) {
            double r0 = rowCenters.get(0);
            double r1 = rowCenters.get(1);
            double step = r1 - r0;
            if (step <= 0) return List.of();
            rowCenters = new ArrayList<>(rowCenters);
            rowCenters.add(r1 + step);
            rowCenters.sort(Double::compareTo);
        }

        if (rowCenters.size() < 3) {
            return List.of(); // fail -> fallback
        }

        // Split row centers into groups by large gaps.
        List<Double> rowGaps = consecutiveGaps(rowCenters, epsY);
        double medianRowGap = rowGaps.isEmpty() ? (epsY * 2.5) : quantileSortedCopy(rowGaps, 0.50);
        double lineBreakThreshold = medianRowGap * LINE_BREAK_MULT;

        List<List<Double>> groups = splitByLargeGaps(rowCenters, lineBreakThreshold);

        // Build lines from groups using offset alignment + bottom-row synthesis.
        List<Line> lines = new ArrayList<>();
        for (List<Double> g : groups) {
            lines.addAll(buildLinesFromGroupAligned(rotated, g, epsY));
        }
        if (lines.isEmpty()) {
            return List.of(); // fail -> fallback
        }

        // Global renumber by vertical order.
        lines.sort(Comparator.comparingDouble(l -> l.r0));
        for (int i = 0; i < lines.size(); i++) {
            lines.get(i).lineIndex = i;
        }

        double tolY = Math.max(epsY * 1.6, medianRowGap * TOLY_MULT);

        // Assign dots to nearest (lineIndex, rowInLine).
        Map<Integer, List<AssignedDot>> dotsByLine = new TreeMap<>();
        for (DetectedDotDto d : rotated) {
            LineRow lr = nearestLineRow(lines, d.getY(), tolY);
            if (lr == null) continue;
            dotsByLine.computeIfAbsent(lr.lineIndex, k -> new ArrayList<>())
                    .add(new AssignedDot(d, lr.rowInLine));
        }
        if (dotsByLine.isEmpty()) {
            return List.of(); // fail -> fallback
        }

        // Per-line X clustering -> pair columns -> gap-aware cell index -> map to grid.
        List<NormalizedDot> normalized = new ArrayList<>();

        for (Map.Entry<Integer, List<AssignedDot>> entry : dotsByLine.entrySet()) {

            int lineIndex = entry.getKey();
            List<AssignedDot> lineDots = entry.getValue();
            if (lineDots.size() < 3) continue; // Relaxed from 6 to support sparse lines.

            List<Double> xSorted = lineDots.stream().map(ad -> ad.dot.getX()).sorted().toList();
            double epsX = estimateEpsFromNearestNeighborQuantile(xSorted, 1.10, EPSX_FALLBACK, EPSX_MIN, EPSX_MAX);

            List<Double> colCenters = cluster1D(xSorted, epsX);
            if (colCenters.isEmpty()) continue;

            List<double[]> pairs = pairColumns(colCenters);
            if (pairs.isEmpty()) continue;

            // Preserve large gaps as word breaks.
            int[] pairToVirtualCell = buildGapAwareCellIndex(pairs);

            double tolX = Math.max(TOLX_MIN, epsX * 2.2);

            for (AssignedDot ad : lineDots) {
                BestX best = bestPairSide(pairs, ad.dot.getX());
                if (best.cellIndex < 0) continue;

                // Always snap (do not drop dots).
                int gridRow = lineIndex * 3 + ad.rowInLine;
                int virtualCell = pairToVirtualCell[best.cellIndex];
                int gridCol = virtualCell * 2 + best.localCol;

                normalized.add(new NormalizedDot(gridRow, gridCol, ad.dot.getConfidence()));
            }
        }

        return normalized.isEmpty() ? List.of() : normalized;
    }

    private List<NormalizedDot> normalizeSingleLine(List<DetectedDotDto> rotated) {

        if (rotated == null || rotated.isEmpty()) return List.of();

        double[] yCenters = kMeans1D(rotated.stream().mapToDouble(DetectedDotDto::getY).toArray(), 3);
        if (yCenters.length < 3) return List.of();
        Arrays.sort(yCenters);

        double tolY = estimateRowTol(yCenters);

        List<Double> xValuesSorted = rotated.stream().map(DetectedDotDto::getX).sorted().toList();
        double epsX = estimateEpsFromNearestNeighbor(xValuesSorted, 0.55, 8.0);
        List<Double> colCenters = cluster1D(xValuesSorted, epsX);
        if (colCenters.isEmpty()) return List.of();

        List<double[]> pairs = pairColumnsSingle(colCenters);
        if (pairs.isEmpty()) return List.of();

        int[] pairToVirtualCell = buildGapAwareCellIndex(pairs);

        double tolX = Math.max(epsX, 6.0);

        List<NormalizedDot> normalized = new ArrayList<>();

        for (DetectedDotDto d : rotated) {

            Integer rowIdx = nearestIndex(yCenters, d.getY(), tolY);
            if (rowIdx == null) continue;

            BestX best = bestPairSide(pairs, d.getX());
            if (best.cellIndex < 0) continue;

            if (best.distance > tolX) continue;

            int gridRow = rowIdx; // 0..2
            int virtualCell = pairToVirtualCell[best.cellIndex];
            int gridCol = virtualCell * 2 + best.localCol;

            normalized.add(new NormalizedDot(gridRow, gridCol, d.getConfidence()));
        }

        return normalized;
    }

    private List<Line> buildLinesFromGroupAligned(List<DetectedDotDto> dots, List<Double> centers, double epsY) {

        if (centers == null || centers.size() < 2) return List.of();

        List<Double> c = new ArrayList<>(centers);
        c.sort(Double::compareTo);

        // If a group has only 2 row-centers, synthesize r2 and treat as one line.
        if (c.size() == 2) {
            double r0 = c.get(0);
            double r1 = c.get(1);
            double step = r1 - r0;
            if (step <= 0) return List.of();
            double r2 = r1 + step;
            return List.of(new Line(0, r0, r1, r2));
        }

        List<Double> gaps = new ArrayList<>();
        for (int i = 1; i < c.size(); i++) {
            double g = c.get(i) - c.get(i - 1);
            if (g > 0) gaps.add(g);
        }
        gaps.sort(Double::compareTo);

        double rowGap = gaps.isEmpty() ? (epsY * 2.5) : quantileFromSorted(gaps, 0.30);
        double maxInTripleGap = rowGap * TRIPLE_GAP_MULT;

        int bestOffset = 0;
        int bestValid = -1;

        for (int offset = 0; offset < 3; offset++) {
            int valid = 0;
            for (int i = offset; i + 2 < c.size(); i += 3) {
                double r0 = c.get(i);
                double r1 = c.get(i + 1);
                double r2 = c.get(i + 2);
                if (isValidTriple(dots, r0, r1, r2, epsY, maxInTripleGap)) valid++;
            }
            if (valid > bestValid) {
                bestValid = valid;
                bestOffset = offset;
            }
        }

        List<Line> out = new ArrayList<>();
        int localIndex = 0;

        for (int i = bestOffset; i + 2 < c.size(); i += 3) {

            double r0 = c.get(i);
            double r1 = c.get(i + 1);
            double r2 = c.get(i + 2);

            if (!isValidTriple(dots, r0, r1, r2, epsY, maxInTripleGap)) {
                continue;
            }

            int s2 = supportCount(dots, r2, epsY);
            if (s2 <= MIN_BOTTOM_ROW_SUPPORT) {
                double step = (r1 - r0);
                r2 = r1 + step;
            }

            out.add(new Line(localIndex++, r0, r1, r2));
        }

        return out;
    }

    private boolean isValidTriple(
            List<DetectedDotDto> dots,
            double r0, double r1, double r2,
            double epsY,
            double maxGap
    ) {
        double g01 = r1 - r0;
        double g12 = r2 - r1;

        if (g01 <= 0 || g12 <= 0) return false;
        if (g01 > maxGap || g12 > maxGap) return false;

        return supportCount(dots, r0, epsY) >= MIN_ROW_SUPPORT_RELAXED
                && supportCount(dots, r1, epsY) >= MIN_ROW_SUPPORT_RELAXED;
    }

    private int supportCount(List<DetectedDotDto> dots, double center, double epsY) {
        int s = 0;
        for (DetectedDotDto d : dots) {
            if (Math.abs(d.getY() - center) <= epsY) s++;
        }
        return s;
    }

    private static class Line {
        int lineIndex;
        final double r0, r1, r2;
        Line(int lineIndex, double r0, double r1, double r2) {
            this.lineIndex = lineIndex;
            this.r0 = r0;
            this.r1 = r1;
            this.r2 = r2;
        }
    }

    private static class LineRow {
        final int lineIndex;
        final int rowInLine;
        LineRow(int lineIndex, int rowInLine) {
            this.lineIndex = lineIndex;
            this.rowInLine = rowInLine;
        }
    }

    private static class AssignedDot {
        final DetectedDotDto dot;
        final int rowInLine;
        AssignedDot(DetectedDotDto dot, int rowInLine) {
            this.dot = dot;
            this.rowInLine = rowInLine;
        }
    }

    private LineRow nearestLineRow(List<Line> lines, double y, double tolY) {

        int bestLine = -1;
        int bestRow = -1;
        double bestDist = Double.MAX_VALUE;

        for (Line line : lines) {

            double d0 = Math.abs(y - line.r0);
            if (d0 < bestDist) { bestDist = d0; bestLine = line.lineIndex; bestRow = 0; }

            double d1 = Math.abs(y - line.r1);
            if (d1 < bestDist) { bestDist = d1; bestLine = line.lineIndex; bestRow = 1; }

            double d2 = Math.abs(y - line.r2);
            if (d2 < bestDist) { bestDist = d2; bestLine = line.lineIndex; bestRow = 2; }
        }

        if (bestLine < 0 || bestDist > tolY) return null;
        return new LineRow(bestLine, bestRow);
    }

    private List<List<Double>> splitByLargeGaps(List<Double> sortedCenters, double breakGap) {

        List<List<Double>> groups = new ArrayList<>();
        List<Double> current = new ArrayList<>();
        current.add(sortedCenters.get(0));

        for (int i = 1; i < sortedCenters.size(); i++) {
            double gap = sortedCenters.get(i) - sortedCenters.get(i - 1);
            if (gap > breakGap) {
                groups.add(current);
                current = new ArrayList<>();
            }
            current.add(sortedCenters.get(i));
        }

        groups.add(current);
        return groups;
    }

    private List<Double> filterCentersBySupport(
            List<DetectedDotDto> dots,
            List<Double> centers,
            double eps,
            boolean useY,
            int minSupport
    ) {
        List<Double> kept = new ArrayList<>();
        for (double c : centers) {
            int s = 0;
            for (DetectedDotDto d : dots) {
                double v = useY ? d.getY() : d.getX();
                if (Math.abs(v - c) <= eps) s++;
            }
            if (s >= minSupport) kept.add(c);
        }
        kept.sort(Double::compareTo);
        return kept;
    }

    private List<Double> consecutiveGaps(List<Double> sortedCenters, double ignoreBelow) {
        List<Double> gaps = new ArrayList<>();
        for (int i = 1; i < sortedCenters.size(); i++) {
            double g = sortedCenters.get(i) - sortedCenters.get(i - 1);
            if (g > ignoreBelow) gaps.add(g);
        }
        return gaps;
    }

    private static class BestX {
        final int cellIndex;
        final int localCol;   // 0=left, 1=right.
        final double distance;
        BestX(int cellIndex, int localCol, double distance) {
            this.cellIndex = cellIndex;
            this.localCol = localCol;
            this.distance = distance;
        }
    }

    private BestX bestPairSide(List<double[]> pairs, double x) {

        int bestCell = -1;
        int bestLocalCol = -1;
        double bestDist = Double.MAX_VALUE;

        for (int cell = 0; cell < pairs.size(); cell++) {
            double xL = pairs.get(cell)[0];
            double xR = pairs.get(cell)[1];

            double dL = Math.abs(x - xL);
            if (dL < bestDist) { bestDist = dL; bestCell = cell; bestLocalCol = 0; }

            double dR = Math.abs(x - xR);
            if (dR < bestDist) { bestDist = dR; bestCell = cell; bestLocalCol = 1; }
        }

        return new BestX(bestCell, bestLocalCol, bestDist);
    }

    private List<double[]> pairColumns(List<Double> colCenters) {

        if (colCenters == null || colCenters.isEmpty()) return List.of();

        List<Double> c = new ArrayList<>(colCenters);
        c.sort(Double::compareTo);

        List<Double> gaps = new ArrayList<>();
        for (int i = 1; i < c.size(); i++) {
            double g = c.get(i) - c.get(i - 1);
            if (g > 0) gaps.add(g);
        }
        gaps.sort(Double::compareTo);

        if (gaps.isEmpty()) {
            double xL = c.get(0);
            double lr = 18.0;
            return List.of(new double[]{xL, xL + lr});
        }

        double within = quantileFromSorted(gaps, 0.20);
        double between = quantileFromSorted(gaps, 0.70);

        within = clamp(within, 6.0, 36.0);
        between = clamp(between, within + 3.0, 90.0);

        double threshold = (within + between) / 2.0;

        List<double[]> pairs = new ArrayList<>();
        int i = 0;

        while (i < c.size()) {

            if (i < c.size() - 1) {
                double x0 = c.get(i);
                double x1 = c.get(i + 1);
                double gap = x1 - x0;

                if (gap <= threshold) {
                    pairs.add(new double[]{x0, x1});
                    i += 2;
                    continue;
                }
            }

            double xL = c.get(i);
            pairs.add(new double[]{xL, xL + within});
            i += 1;
        }

        return pairs;
    }

    private boolean spansMultipleLines(List<NormalizedDot> normalized) {
        if (normalized == null || normalized.isEmpty()) return false;
        for (NormalizedDot d : normalized) {
            if (d.getRow() >= 3) return true;
        }
        return false;
    }

    private List<double[]> pairColumnsSingle(List<Double> colCenters) {

        if (colCenters == null || colCenters.isEmpty()) return List.of();

        List<Double> c = new ArrayList<>(colCenters);
        c.sort(Double::compareTo);

        if (c.size() == 1) {
            double xL = c.get(0);
            double lr = 18.0;
            return List.of(new double[]{xL, xL + lr});
        }

        List<Double> gaps = new ArrayList<>();
        for (int i = 1; i < c.size(); i++) {
            gaps.add(c.get(i) - c.get(i - 1));
        }
        gaps.sort(Double::compareTo);

        int take = Math.max(1, Math.min(5, gaps.size()));
        double lrGap = gaps.isEmpty() ? 18.0 : median(gaps.subList(0, take));

        List<double[]> pairs = new ArrayList<>();
        int i = 0;

        while (i < c.size()) {

            if (i < c.size() - 1) {
                double a = c.get(i);
                double b = c.get(i + 1);
                double gap = b - a;

                if (gap <= lrGap * 1.6) {
                    pairs.add(new double[]{a, b});
                    i += 2;
                    continue;
                }
            }

            double xL = c.get(i);
            double xR = xL + lrGap;
            pairs.add(new double[]{xL, xR});
            i += 1;
        }

        return pairs;
    }

    private double median(List<Double> v) {
        if (v.isEmpty()) return 0.0;
        List<Double> c = new ArrayList<>(v);
        c.sort(Double::compareTo);
        return c.get(c.size() / 2);
    }

    private int[] buildGapAwareCellIndex(List<double[]> pairs) {

        List<Double> centers = new ArrayList<>();
        for (double[] p : pairs) centers.add((p[0] + p[1]) / 2.0);

        List<Double> gaps = new ArrayList<>();
        for (int i = 1; i < centers.size(); i++) gaps.add(centers.get(i) - centers.get(i - 1));
        gaps.sort(Double::compareTo);

        double cellGap = gaps.isEmpty() ? 1.0 : gaps.get(gaps.size() / 2);

        int[] mapped = new int[pairs.size()];
        int virtualIndex = 0;
        mapped[0] = 0;

        for (int i = 1; i < pairs.size(); i++) {
            double delta = centers.get(i) - centers.get(i - 1);

            int step = (int) Math.round(delta / cellGap);
            if (step < 1) step = 1;

            virtualIndex += step;
            mapped[i] = virtualIndex;
        }

        return mapped;
    }

    // ============================================================
    // 1D clustering + eps estimation
    // ============================================================

    private List<Double> cluster1D(List<Double> sortedValues, double eps) {
        if (sortedValues == null || sortedValues.isEmpty()) return List.of();

        List<Double> centers = new ArrayList<>();
        int start = 0;

        for (int i = 1; i < sortedValues.size(); i++) {
            if (sortedValues.get(i) - sortedValues.get(i - 1) > eps) {
                centers.add(mean(sortedValues, start, i));
                start = i;
            }
        }
        centers.add(mean(sortedValues, start, sortedValues.size()));
        centers.sort(Double::compareTo);
        return centers;
    }

    private double mean(List<Double> v, int from, int to) {
        double s = 0.0;
        for (int i = from; i < to; i++) s += v.get(i);
        return s / (to - from);
    }

    private double estimateEpsFromNearestNeighborQuantile(
            List<Double> sorted,
            double scale,
            double fallback,
            double minClamp,
            double maxClamp
    ) {
        if (sorted == null || sorted.size() < 2) return fallback;

        List<Double> diffs = new ArrayList<>();
        for (int i = 1; i < sorted.size(); i++) {
            double d = sorted.get(i) - sorted.get(i - 1);
            if (d > 0.0) diffs.add(d);
        }
        if (diffs.isEmpty()) return fallback;

        diffs.sort(Double::compareTo);

        int idx = (int) Math.floor(0.20 * (diffs.size() - 1));
        idx = Math.max(0, Math.min(idx, diffs.size() - 1));

        double q20 = diffs.get(idx);
        double eps = q20 * scale;

        eps = clamp(eps, minClamp, maxClamp);
        return (Double.isFinite(eps) && eps > 0.0) ? eps : fallback;
    }

    private double estimateEpsFromNearestNeighbor(List<Double> sorted, double scale, double fallback) {
        if (sorted == null || sorted.size() < 2) return fallback;

        List<Double> diffs = new ArrayList<>();
        for (int i = 1; i < sorted.size(); i++) {
            double d = sorted.get(i) - sorted.get(i - 1);
            if (d > 0.0) diffs.add(d);
        }
        if (diffs.isEmpty()) return fallback;

        diffs.sort(Double::compareTo);
        double median = diffs.get(diffs.size() / 2);
        return Math.max(fallback, median * scale);
    }

    private double quantileSortedCopy(List<Double> values, double q) {
        if (values == null || values.isEmpty()) return 0.0;
        List<Double> c = new ArrayList<>(values);
        c.sort(Double::compareTo);
        return quantileFromSorted(c, q);
    }

    private double quantileFromSorted(List<Double> sorted, double q) {
        if (sorted == null || sorted.isEmpty()) return 0.0;
        double qq = Math.max(0.0, Math.min(1.0, q));
        int idx = (int) Math.round(qq * (sorted.size() - 1));
        idx = Math.max(0, Math.min(idx, sorted.size() - 1));
        return sorted.get(idx);
    }

    private double estimateRowTol(double[] yCenters) {
        if (yCenters.length < 2) return 6.0;
        double minGap = Double.MAX_VALUE;
        for (int i = 1; i < yCenters.length; i++) {
            minGap = Math.min(minGap, Math.abs(yCenters[i] - yCenters[i - 1]));
        }
        return Math.max(6.0, minGap * 0.45);
    }

    private Integer nearestIndex(double[] centers, double value, double tol) {
        int best = -1;
        double bestDist = Double.MAX_VALUE;

        for (int i = 0; i < centers.length; i++) {
            double dist = Math.abs(value - centers[i]);
            if (dist < bestDist) {
                bestDist = dist;
                best = i;
            }
        }
        if (best == -1 || bestDist > tol) return null;
        return best;
    }

    private double[] kMeans1D(double[] values, int k) {
        if (values.length == 0) return new double[0];

        double min = Arrays.stream(values).min().orElse(0.0);
        double max = Arrays.stream(values).max().orElse(min);

        double[] centers = new double[k];
        for (int i = 0; i < k; i++) {
            centers[i] = min + (max - min) * i / Math.max(1, (k - 1));
        }

        for (int iter = 0; iter < 25; iter++) {
            List<List<Double>> buckets = new ArrayList<>();
            for (int i = 0; i < k; i++) buckets.add(new ArrayList<>());

            for (double v : values) {
                int idx = 0;
                double best = Double.MAX_VALUE;
                for (int i = 0; i < k; i++) {
                    double d = Math.abs(v - centers[i]);
                    if (d < best) {
                        best = d;
                        idx = i;
                    }
                }
                buckets.get(idx).add(v);
            }

            boolean moved = false;
            for (int i = 0; i < k; i++) {
                if (buckets.get(i).isEmpty()) continue;
                double newC = buckets.get(i).stream().mapToDouble(x -> x).average().orElse(centers[i]);
                if (Math.abs(newC - centers[i]) > 1e-3) moved = true;
                centers[i] = newC;
            }

            if (!moved) break;
        }

        return centers;
    }

    private DetectedDotDto rotateDot(DetectedDotDto dot, double angle, double centerX, double centerY) {
        double x = dot.getX() - centerX;
        double y = dot.getY() - centerY;

        double cos = Math.cos(-angle);
        double sin = Math.sin(-angle);

        double xr = x * cos - y * sin;
        double yr = x * sin + y * cos;

        return new DetectedDotDto(xr + centerX, yr + centerY, dot.getConfidence());
    }

    private double estimateSkewAngle(List<DetectedDotDto> dots) {

        double meanX = 0.0;
        double meanY = 0.0;

        for (DetectedDotDto d : dots) {
            meanX += d.getX();
            meanY += d.getY();
        }

        meanX /= dots.size();
        meanY /= dots.size();

        double num = 0.0;
        double den = 0.0;

        for (DetectedDotDto d : dots) {
            double dx = d.getX() - meanX;
            double dy = d.getY() - meanY;
            num += dx * dy;
            den += dx * dx;
        }

        return Math.atan2(num, den);
    }

    private double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
