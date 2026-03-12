package com.engine.brailleai.vision.normalization;

import com.engine.brailleai.vision.dto.DetectedDotDto;
import com.engine.brailleai.vision.dto.DotDetectionResponseDto;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DotNormalizerTest {

    private final DotNormalizer normalizer = new DotNormalizer();

    @Test
    void returnsEmptyWhenResponseOrDotsAreMissing() {
        DotDetectionResponseDto response = new DotDetectionResponseDto();
        response.setDots(List.of());

        assertEquals(List.of(), normalizer.normalize(null));
        assertEquals(List.of(), normalizer.normalize(response));
    }

    @Test
    void filtersOutVeryLowConfidenceDots() {
        DotDetectionResponseDto response = new DotDetectionResponseDto();
        response.setDots(List.of(
                new DetectedDotDto(10, 10, 0.01),
                new DetectedDotDto(20, 20, 0.03)
        ));

        assertEquals(List.of(), normalizer.normalize(response));
    }
}
