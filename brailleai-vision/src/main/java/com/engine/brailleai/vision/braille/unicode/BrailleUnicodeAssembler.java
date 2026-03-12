package com.engine.brailleai.vision.braille.unicode;

import com.engine.brailleai.vision.braille.BrailleCell;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Converts ordered Braille cells into a Unicode Braille string.
 */
public class BrailleUnicodeAssembler {

    private final BrailleUnicodeEncoder encoder = new BrailleUnicodeEncoder();

    /**
     * Assembles ordered Braille cells into a Unicode Braille string.
     */
    public String assemble(List<BrailleCell> cells) {

        if (cells == null || cells.isEmpty()) {
            return "";
        }

        List<PositionedBrailleCell> positioned = new ArrayList<>();
        for (BrailleCell cell : cells) {
            positioned.add(new PositionedBrailleCell(cell));
        }

        // Reading order: top → bottom, left → right
        positioned.sort(
                Comparator
                        .comparingInt(PositionedBrailleCell::getCellRow)
                        .thenComparingInt(PositionedBrailleCell::getCellColumn)
        );

        StringBuilder brailleText = new StringBuilder();
        PositionedBrailleCell previous = null;

        for (PositionedBrailleCell current : positioned) {

            if (previous != null) {

                // New line (future-safe hook)
                if (current.getCellRow() > previous.getCellRow()) {
                    brailleText.append('\n');
                }
                // Space between words (gap in cell columns)
                else if (current.getCellColumn() - previous.getCellColumn() > 1) {
                    brailleText.append(' ');
                }
            }

            brailleText.append(encoder.encode(current.getCell()));
            previous = current;
        }

        return brailleText.toString();
    }
}
