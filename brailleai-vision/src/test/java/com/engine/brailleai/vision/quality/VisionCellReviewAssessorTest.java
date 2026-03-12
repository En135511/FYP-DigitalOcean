package com.engine.brailleai.vision.quality;

import com.engine.brailleai.vision.braille.BrailleCell;
import com.engine.brailleai.vision.normalization.NormalizedDot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VisionCellReviewAssessorTest {

    private final VisionCellReviewAssessor assessor = new VisionCellReviewAssessor();

    @Test
    void flagsWeakAndSparseCellsForReview() {
        List<NormalizedDot> dots = List.of(
                // cell (0,0): strong dots
                new NormalizedDot(0, 0, 0.92),
                new NormalizedDot(1, 0, 0.90),
                // cell (0,1): weak dots
                new NormalizedDot(0, 2, 0.41),
                new NormalizedDot(1, 2, 0.49),
                // cell (0,2): sparse single-dot
                new NormalizedDot(0, 4, 0.52)
        );

        List<BrailleCell> cells = List.of(
                new BrailleCell(0, 0, Set.of(1, 2)),
                new BrailleCell(0, 1, Set.of(1, 2)),
                new BrailleCell(0, 2, Set.of(1))
        );

        VisionCellReviewAssessment result = assessor.assess(dots, cells);

        assertEquals(2, result.getLowConfidenceCellsCount());
        assertTrue(result.isReviewRecommended());
        assertTrue(result.getLowConfidenceCells().stream().anyMatch(c -> "weak-dot".equals(c.getReason())));
        assertTrue(result.getLowConfidenceCells().stream().anyMatch(c -> "sparse-cell".equals(c.getReason())));
    }

    @Test
    void keepsReviewOffWhenCellsAreStable() {
        List<NormalizedDot> dots = List.of(
                new NormalizedDot(0, 0, 0.95),
                new NormalizedDot(1, 0, 0.93),
                new NormalizedDot(0, 2, 0.94),
                new NormalizedDot(1, 2, 0.92)
        );
        List<BrailleCell> cells = List.of(
                new BrailleCell(0, 0, Set.of(1, 2)),
                new BrailleCell(0, 1, Set.of(1, 2))
        );

        VisionCellReviewAssessment result = assessor.assess(dots, cells);

        assertEquals(0, result.getLowConfidenceCellsCount());
        assertFalse(result.isReviewRecommended());
    }
}
