package com.engine.brailleai.vision.braille.unicode;

import com.engine.brailleai.vision.braille.BrailleCell;

public class PositionedBrailleCell {

    private final BrailleCell cell;
    private final int cellRow;
    private final int cellColumn;

    public PositionedBrailleCell(BrailleCell cell) {
        this.cell = cell;
        this.cellRow = cell.getCellRow();
        this.cellColumn = cell.getCellColumn();
    }

    public BrailleCell getCell() {
        return cell;
    }

    public int getCellRow() {
        return cellRow;
    }

    public int getCellColumn() {
        return cellColumn;
    }
}