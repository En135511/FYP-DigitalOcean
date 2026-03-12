package com.engine.brailleai.vision.braille.unicode;

import com.engine.brailleai.vision.braille.BrailleCell;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BrailleUnicodeAssemblerTest {

    private final BrailleUnicodeAssembler assembler = new BrailleUnicodeAssembler();

    @Test
    void insertsSpacesAndNewLinesFromCellPositions() {
        BrailleCell firstLineFirstCell = new BrailleCell(0, 0, Set.of(1));
        BrailleCell firstLineThirdCell = new BrailleCell(0, 2, Set.of(1));
        BrailleCell secondLineFirstCell = new BrailleCell(1, 0, Set.of(1));

        String assembled = assembler.assemble(List.of(
                secondLineFirstCell,
                firstLineThirdCell,
                firstLineFirstCell
        ));

        assertEquals("\u2801 \u2801\n\u2801", assembled);
    }

    @Test
    void returnsEmptyStringForNoCells() {
        assertEquals("", assembler.assemble(List.of()));
        assertEquals("", assembler.assemble(null));
    }
}
