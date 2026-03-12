package com.engine.brailleai.vision.braille;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class BrailleCell {

    private final int cellRow;
    private final int cellColumn;
    private final Set<Integer> activeDots;

    public BrailleCell(int cellRow, int cellColumn, Set<Integer> activeDots) {
        this.cellRow = cellRow;
        this.cellColumn = cellColumn;
        this.activeDots = new HashSet<>(activeDots);
    }

    public int getCellRow() {
        return cellRow;
    }

    public int getCellColumn() {
        return cellColumn;
    }

    public Set<Integer> getActiveDots() {
        return Collections.unmodifiableSet(activeDots);
    }
}