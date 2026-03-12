package com.engine.brailleai.vision.quality;

import com.engine.brailleai.vision.braille.BrailleCell;
import com.engine.brailleai.vision.normalization.NormalizedDot;

import java.util.*;

/**
 * Flags uncertain cells so educators can review questionable transcriptions.
 */
public class VisionCellReviewAssessor {

    private static final double LOW_CELL_AVERAGE = 0.62;
    private static final double VERY_LOW_DOT = 0.45;
    private static final int MAX_FLAGGED_CELLS = 200;

    public VisionCellReviewAssessment assess(
            List<NormalizedDot> normalizedDots,
            List<BrailleCell> cells
    ) {
        if (normalizedDots == null || normalizedDots.isEmpty() || cells == null || cells.isEmpty()) {
            return new VisionCellReviewAssessment(0, false, List.of());
        }

        Map<String, Map<Integer, Double>> confidenceByCellDot = new HashMap<>();
        for (NormalizedDot dot : normalizedDots) {
            int cellRow = dot.getRow() / 3;
            int cellColumn = dot.getColumn() / 2;
            int localRow = dot.getRow() % 3;
            int localColumn = dot.getColumn() % 2;
            int dotIndex = localColumn == 0 ? localRow + 1 : localRow + 4;

            String key = cellKey(cellRow, cellColumn);
            Map<Integer, Double> byDot = confidenceByCellDot.computeIfAbsent(key, k -> new HashMap<>());
            byDot.put(dotIndex, Math.max(byDot.getOrDefault(dotIndex, 0.0), dot.getConfidence()));
        }

        List<VisionCellFlag> flagged = new ArrayList<>();
        for (BrailleCell cell : cells) {
            CellCheck check = checkCell(cell, confidenceByCellDot.get(cellKey(cell.getCellRow(), cell.getCellColumn())));
            if (!check.flagged()) {
                continue;
            }
            flagged.add(new VisionCellFlag(
                    cell.getCellRow(),
                    cell.getCellColumn(),
                    round(check.averageConfidence()),
                    check.reason()
            ));
        }

        flagged.sort(Comparator.comparingDouble(VisionCellFlag::getConfidence));
        if (flagged.size() > MAX_FLAGGED_CELLS) {
            flagged = new ArrayList<>(flagged.subList(0, MAX_FLAGGED_CELLS));
        }

        int flaggedCount = flagged.size();
        double ratio = cells.isEmpty() ? 0.0 : (double) flaggedCount / cells.size();
        boolean reviewRecommended = flaggedCount > 0 && (flaggedCount >= 6 || ratio >= 0.12);

        return new VisionCellReviewAssessment(flaggedCount, reviewRecommended, flagged);
    }

    private CellCheck checkCell(BrailleCell cell, Map<Integer, Double> confidenceByDot) {
        Set<Integer> activeDots = cell.getActiveDots();
        if (activeDots == null || activeDots.isEmpty()) {
            return new CellCheck(true, 0.0, "empty-cell");
        }

        if (activeDots.size() == 1) {
            double only = readConfidence(activeDots.iterator().next(), confidenceByDot);
            if (only < 0.70) {
                return new CellCheck(true, only, "sparse-cell");
            }
            return new CellCheck(false, only, null);
        }

        double sum = 0.0;
        double min = Double.MAX_VALUE;
        int count = 0;
        for (Integer dotIndex : activeDots) {
            double conf = readConfidence(dotIndex, confidenceByDot);
            sum += conf;
            min = Math.min(min, conf);
            count++;
        }

        double avg = count == 0 ? 0.0 : sum / count;
        if (min < VERY_LOW_DOT) {
            return new CellCheck(true, avg, "weak-dot");
        }
        if (avg < LOW_CELL_AVERAGE) {
            return new CellCheck(true, avg, "low-cell-confidence");
        }
        return new CellCheck(false, avg, null);
    }

    private double readConfidence(Integer dotIndex, Map<Integer, Double> confidenceByDot) {
        if (dotIndex == null || confidenceByDot == null) {
            return 0.0;
        }
        return confidenceByDot.getOrDefault(dotIndex, 0.0);
    }

    private String cellKey(int row, int col) {
        return row + ":" + col;
    }

    private double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    private record CellCheck(boolean flagged, double averageConfidence, String reason) {
    }
}
