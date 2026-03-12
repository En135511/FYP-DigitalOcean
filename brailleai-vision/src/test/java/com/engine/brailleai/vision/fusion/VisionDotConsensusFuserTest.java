package com.engine.brailleai.vision.fusion;

import com.engine.brailleai.vision.dto.DetectedDotDto;
import com.engine.brailleai.vision.dto.DotDetectionResponseDto;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VisionDotConsensusFuserTest {

    private final VisionDotConsensusFuser fuser = new VisionDotConsensusFuser();

    @Test
    void keepsConsensusDotsAndDropsWeakOutliers() {
        DotDetectionResponseDto a = response(
                new DetectedDotDto(10.0, 10.0, 0.86),
                new DetectedDotDto(50.0, 50.0, 0.22)
        );
        DotDetectionResponseDto b = response(
                new DetectedDotDto(11.0, 10.4, 0.79)
        );

        DotDetectionResponseDto fused = fuser.fuse(List.of(a, b));
        assertEquals(1, fused.getDots().size());
        assertTrue(Math.abs(fused.getDots().get(0).getX() - 10.5) < 1.0);
        assertTrue(Math.abs(fused.getDots().get(0).getY() - 10.2) < 1.0);
    }

    @Test
    void keepsHighConfidenceSingletonWhenSupportIsLow() {
        DotDetectionResponseDto only = response(new DetectedDotDto(120.0, 88.0, 0.91));
        DotDetectionResponseDto empty = response();

        DotDetectionResponseDto fused = fuser.fuse(List.of(only, empty));
        assertEquals(1, fused.getDots().size());
        assertTrue(fused.getDots().get(0).getConfidence() >= 0.8);
    }

    @Test
    void dropsLowConfidenceSingletonBelowSupportThreshold() {
        DotDetectionResponseDto only = response(new DetectedDotDto(120.0, 88.0, 0.45));
        DotDetectionResponseDto empty = response();

        DotDetectionResponseDto fused = fuser.fuse(List.of(only, empty));
        assertEquals(0, fused.getDots().size());
    }

    private DotDetectionResponseDto response(DetectedDotDto... dots) {
        DotDetectionResponseDto dto = new DotDetectionResponseDto();
        dto.setImageWidth(640);
        dto.setImageHeight(480);
        dto.setDots(dots == null ? List.of() : List.of(dots));
        return dto;
    }
}
