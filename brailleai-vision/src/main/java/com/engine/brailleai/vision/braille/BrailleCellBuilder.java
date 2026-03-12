package com.engine.brailleai.vision.braille;

import com.engine.brailleai.vision.normalization.NormalizedDot;

import java.util.*;

/**
 * Groups normalized dots into Braille cells and orders them for reading.
 */
public class BrailleCellBuilder {

    public List<BrailleCell> buildCells(List<NormalizedDot> dots) {

        if (dots == null || dots.isEmpty()) {
            return List.of();
        }

        Map<String, Set<Integer>> cellDotMap = new HashMap<>();

        for (NormalizedDot dot : dots) {

            int cellRow = dot.getRow() / 3;
            int cellColumn = dot.getColumn() / 2;

            int localRow = dot.getRow() % 3;
            int localColumn = dot.getColumn() % 2;

            int dotIndex = mapDotIndex(localRow, localColumn);

            String cellKey = cellRow + ":" + cellColumn;

            cellDotMap
                    .computeIfAbsent(cellKey, k -> new HashSet<>())
                    .add(dotIndex);
        }

        List<BrailleCell> cells = new ArrayList<>();

        for (Map.Entry<String, Set<Integer>> entry : cellDotMap.entrySet()) {
            String[] parts = entry.getKey().split(":");
            int cellRow = Integer.parseInt(parts[0]);
            int cellColumn = Integer.parseInt(parts[1]);

            cells.add(new BrailleCell(cellRow, cellColumn, entry.getValue()));
        }

        // Ensure reading order (top-left to bottom-right).
        cells.sort(
                Comparator
                        .comparingInt(BrailleCell::getCellRow)
                        .thenComparingInt(BrailleCell::getCellColumn)
        );

        return cells;
    }

    private int mapDotIndex(int localRow, int localColumn) {
        if (localColumn == 0) {
            return localRow + 1;   // dots 1-3
        } else {
            return localRow + 4;   // dots 4-6
        }
    }
}
