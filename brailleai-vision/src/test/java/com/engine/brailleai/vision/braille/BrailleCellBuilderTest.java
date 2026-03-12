package com.engine.brailleai.vision.braille;

import com.engine.brailleai.vision.normalization.NormalizedDot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BrailleCellBuilderTest {

    private final BrailleCellBuilder builder = new BrailleCellBuilder();

    @Test
    void groupsDotsIntoCellsAndSortsInReadingOrder() {
        List<NormalizedDot> dots = List.of(
                new NormalizedDot(0, 2, 0.9), // cell(0,1) dot 1
                new NormalizedDot(1, 0, 0.9), // cell(0,0) dot 2
                new NormalizedDot(0, 0, 0.9)  // cell(0,0) dot 1
        );

        List<BrailleCell> cells = builder.buildCells(dots);

        assertEquals(2, cells.size());
        assertEquals(0, cells.get(0).getCellRow());
        assertEquals(0, cells.get(0).getCellColumn());
        assertEquals(Set.of(1, 2), cells.get(0).getActiveDots());
        assertEquals(1, cells.get(1).getCellColumn());
        assertEquals(Set.of(1), cells.get(1).getActiveDots());
    }

    @Test
    void returnsEmptyListForNullOrEmptyInput() {
        assertEquals(List.of(), builder.buildCells(null));
        assertEquals(List.of(), builder.buildCells(List.of()));
    }
}
