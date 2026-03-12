package com.engine.brailleai.web.controller;

import com.engine.brailleai.api.dto.VisionTranslationResponse;
import com.engine.brailleai.api.dto.VisionDetectedDot;
import com.engine.brailleai.api.dto.VisionLowConfidenceCell;
import com.engine.brailleai.api.service.BrailleTranslator;
import com.engine.brailleai.core.pipeline.BrailleTranslationPipeline;
import com.engine.brailleai.liblouis.LiblouisTableRegistry;
import com.engine.brailleai.output.format.FormatRouter;
import com.engine.brailleai.output.format.OutputFormat;
import com.engine.brailleai.vision.braille.BrailleCell;
import com.engine.brailleai.vision.braille.BrailleCellBuilder;
import com.engine.brailleai.vision.braille.unicode.BrailleUnicodeAssembler;
import com.engine.brailleai.vision.client.VisionClient;
import com.engine.brailleai.vision.dto.DetectedDotDto;
import com.engine.brailleai.vision.dto.DotDetectionResponseDto;
import com.engine.brailleai.vision.fusion.VisionDotConsensusFuser;
import com.engine.brailleai.vision.normalization.DotNormalizer;
import com.engine.brailleai.vision.normalization.NormalizedDot;
import com.engine.brailleai.vision.preprocess.VisionImagePreprocessor;
import com.engine.brailleai.vision.preprocess.VisionImageVariant;
import com.engine.brailleai.vision.quality.VisionCellFlag;
import com.engine.brailleai.vision.quality.VisionCellReviewAssessment;
import com.engine.brailleai.vision.quality.VisionCellReviewAssessor;
import com.engine.brailleai.vision.quality.VisionGeometryPreflightAssessment;
import com.engine.brailleai.vision.quality.VisionGeometryPreflightAssessor;
import com.engine.brailleai.vision.quality.VisionQualityAssessment;
import com.engine.brailleai.vision.quality.VisionQualityAssessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Image-based translation endpoints that run the full vision -> Braille -> text pipeline.
 */
@RestController
@RequestMapping("/api/vision")
public class VisionBrailleController {

    private static final Logger log = LoggerFactory.getLogger(VisionBrailleController.class);
    private static final double GOOD_QUALITY_SCORE = 68.0;
    private static final double ORIGINAL_VARIANT_BONUS = 2.0;
    private static final double SEGMENTED_MIN_GAIN = 4.0;
    private static final double SEGMENTED_DOT_GAIN_RATIO = 1.12;
    private static final double SEGMENTED_SCORE_TOLERANCE = 1.5;
    private static final double ORIENTATION_SWITCH_THRESHOLD = 0.14;
    private static final Set<String> COMMON_ENGLISH_WORDS = Set.of(
            "the", "and", "to", "of", "in", "is", "for", "that", "with", "on",
            "as", "are", "be", "was", "were", "it", "at", "from", "this", "an",
            "or", "by", "not", "have", "has", "had", "you", "we", "they", "he", "she"
    );

    private final VisionClient visionClient;
    private final BrailleTranslationPipeline translationPipeline;
    private final FormatRouter formatRouter;
    private final LiblouisTableRegistry tableRegistry;

    private final DotNormalizer dotNormalizer = new DotNormalizer();
    private final BrailleCellBuilder cellBuilder = new BrailleCellBuilder();
    private final BrailleUnicodeAssembler unicodeAssembler = new BrailleUnicodeAssembler();
    private final VisionImagePreprocessor imagePreprocessor = new VisionImagePreprocessor();
    private final VisionQualityAssessor qualityAssessor = new VisionQualityAssessor();
    private final VisionCellReviewAssessor cellReviewAssessor = new VisionCellReviewAssessor();
    private final VisionDotConsensusFuser dotConsensusFuser = new VisionDotConsensusFuser();
    private final VisionGeometryPreflightAssessor geometryPreflightAssessor = new VisionGeometryPreflightAssessor();

    public VisionBrailleController(
            @Value("${vision.service.base-url}") String visionServiceBaseUrl,
            BrailleTranslationPipeline translationPipeline,
            FormatRouter formatRouter,
            LiblouisTableRegistry tableRegistry
    ) {
        this.visionClient = new VisionClient(visionServiceBaseUrl);
        this.translationPipeline = translationPipeline;
        this.formatRouter = formatRouter;
        this.tableRegistry = tableRegistry;
    }

    /**
     * Detect dots from an image and return both Braille Unicode and translated text.
     */
    @PostMapping(
            value = "/translate",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<VisionTranslationResponse> translateFromImage(
            @RequestParam("image") MultipartFile image,
            @RequestParam(value = "table", required = false) String table
    ) {
        long startNanos = System.nanoTime();
        VisionTranslationResponse response = translateImage(image, table);
        long durationMs = (System.nanoTime() - startNanos) / 1_000_000L;
        int uncertainCells = response.getLowConfidenceCellsCount() == null
                ? 0
                : response.getLowConfidenceCellsCount();
        log.info(
                "VISION_RESPONSE durationMs={} reviewRecommended={} uncertainCells={}",
                durationMs,
                Boolean.TRUE.equals(response.getReviewRecommended()),
                uncertainCells
        );
        return ResponseEntity.ok(response);
    }

    /**
     * Detect dots from an image and return a PDF/DOCX/BRF translation file.
     */
    @PostMapping(
            value = "/translate/download",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public ResponseEntity<byte[]> downloadTranslationFromImage(
            @RequestParam("image") MultipartFile image,
            @RequestParam("format") String format,
            @RequestParam(value = "table", required = false) String table
    ) {
        OutputFormat outputFormat = parseFormat(format);
        if (outputFormat == null) {
            return ResponseEntity.badRequest().body(new byte[0]);
        }

        VisionTranslationResponse response = translateImage(image, table);
        String outputText = outputFormat == OutputFormat.BRF
                ? response.getBrailleUnicode()
                : response.getTranslatedText();

        byte[] fileBytes = formatRouter.generate(outputText, outputFormat);
        String filename = "braille-vision-translation." + outputFormat.name().toLowerCase(Locale.ROOT);

        MediaType mediaType = switch (outputFormat) {
            case PDF -> MediaType.APPLICATION_PDF;
            case DOCX -> MediaType.parseMediaType(
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            );
            case BRF -> MediaType.TEXT_PLAIN;
        };

        return ResponseEntity.ok()
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + filename + "\""
                )
                .contentType(mediaType)
                .body(fileBytes);
    }

    private VisionTranslationResponse translateImage(MultipartFile image, String table) {
        // Basic request diagnostics.
        log.info("=== /api/vision/translate ===");
        log.info("UPLOAD filename=" + (image == null ? "null" : image.getOriginalFilename())
                + " bytes=" + (image == null ? -1 : image.getSize())
                + " contentType=" + (image == null ? "null" : image.getContentType()));

        List<VisionImageVariant> variants = imagePreprocessor.buildVariants(image);
        if (variants.isEmpty()) {
            return new VisionTranslationResponse("", "");
        }

        TranslationAttempt originalAttempt = runAttempt(variants.get(0), table);
        logAttempt(originalAttempt);

        long uploadBytes = image == null ? 0 : image.getSize();
        VisionGeometryPreflightAssessment preflight = geometryPreflightAssessor.assess(
                originalAttempt == null ? null : originalAttempt.getDetectionResponse(),
                variants.get(0).getWidth(),
                variants.get(0).getHeight(),
                uploadBytes
        );
        boolean pageImageLikely = preflight.isPageImageLikely();

        List<TranslationAttempt> attempts = new ArrayList<>();
        attempts.add(originalAttempt);

        if (shouldRunFallbacks(originalAttempt, pageImageLikely, preflight.isHighVariance(), variants.size())) {
            for (int i = 1; i < variants.size(); i++) {
                TranslationAttempt fallbackAttempt = runAttempt(variants.get(i), table);
                attempts.add(fallbackAttempt);
                logAttempt(fallbackAttempt);
            }
        }

        TranslationAttempt consensusAttempt = buildConsensusAttempt(attempts, table);
        if (consensusAttempt != null) {
            attempts.add(consensusAttempt);
            logAttempt(consensusAttempt);
        }

        TranslationAttempt best = chooseBestAttempt(attempts);
        if (best == null) {
            return new VisionTranslationResponse("", "");
        }

        if (pageImageLikely) {
            VisionImageVariant segmentedBase = selectSegmentedBaseVariant(variants, best);
            TranslationAttempt segmentedAttempt = runSegmentedAttempt(segmentedBase, table);
            if (segmentedAttempt != null) {
                logAttempt(segmentedAttempt);
                if (preferSegmentedAttempt(best, segmentedAttempt)) {
                    best = segmentedAttempt;
                }
            }
        }

        TranslationAttempt responseAttempt = originalAttempt;
        if (responseAttempt == null || responseAttempt.getBrailleUnicode().isBlank()) {
            responseAttempt = best;
        }

        String translatedText = responseAttempt.getTranslatedText();
        boolean usedFallbackForText = false;
        if ((translatedText == null || translatedText.isBlank())
                && best != null
                && best != responseAttempt
                && best.getTranslatedText() != null
                && !best.getTranslatedText().isBlank()) {
            translatedText = best.getTranslatedText();
            usedFallbackForText = true;
        }
        if (translatedText == null) {
            translatedText = "";
        }

        String qualityWarning = responseAttempt.getQuality().getWarning();
        if (usedFallbackForText) {
            qualityWarning = appendWarning(
                    qualityWarning,
                    "Text was recovered using fallback detection; Braille output reflects original dot capture."
            );
        }
        if (qualityWarning == null || qualityWarning.isBlank()) {
            qualityWarning = best.getQuality().getWarning();
        }
        if (preflight.getWarning() != null && !preflight.getWarning().isBlank()) {
            qualityWarning = appendWarning(qualityWarning, preflight.getWarning());
        }

        VisionCellReviewAssessment reviewAssessment = cellReviewAssessor.assess(
                responseAttempt.getNormalizedDots(),
                responseAttempt.getCells()
        );
        if (reviewAssessment.isReviewRecommended()) {
            qualityWarning = appendWarning(
                    qualityWarning,
                    "Manual review is recommended: " + reviewAssessment.getLowConfidenceCellsCount()
                            + " uncertain cells detected."
            );
        }

        if (!responseAttempt.getNormalizedDots().isEmpty()) {
            printNormalizedDotStats(responseAttempt.getNormalizedDots());
        }
        if (!responseAttempt.getCells().isEmpty()) {
            printCellStats(responseAttempt.getCells());
        }

        log.info(
                "SELECTED brailleVariant={} textVariant={}",
                responseAttempt.getVariantLabel(),
                usedFallbackForText ? best.getVariantLabel() : responseAttempt.getVariantLabel()
        );
        log.info("BRAILLE UNICODE: [{}] len={}", responseAttempt.getBrailleUnicode(), responseAttempt.getBrailleUnicode().length());
        log.debug("BRAILLE UNICODE (hex): {}", toBrailleHex(responseAttempt.getBrailleUnicode()));
        log.info("TRANSLATED TEXT: [{}]", translatedText);
        if (qualityWarning != null && !qualityWarning.isBlank()) {
            log.warn("QUALITY WARNING: {}", qualityWarning);
        }
        log.info("=== END ===");

        VisionTranslationResponse response = new VisionTranslationResponse(
                responseAttempt.getBrailleUnicode(),
                translatedText,
                qualityWarning
        );
        DotDetectionResponseDto dotSource = responseAttempt == null ? null : responseAttempt.getDetectionResponse();
        if ((dotSource == null || dotSource.getDots() == null || dotSource.getDots().isEmpty())
                && originalAttempt != null) {
            dotSource = originalAttempt.getDetectionResponse();
        }
        List<VisionDetectedDot> detectedDots = toApiDots(dotSource);
        response.setDetectedDots(detectedDots);
        response.setDetectedDotsCount(detectedDots.size());
        List<VisionLowConfidenceCell> lowConfidenceCells = toApiLowConfidenceCells(reviewAssessment);
        response.setLowConfidenceCells(lowConfidenceCells);
        response.setLowConfidenceCellsCount(reviewAssessment.getLowConfidenceCellsCount());
        response.setReviewRecommended(reviewAssessment.isReviewRecommended());
        return response;
    }

    private boolean preferSegmentedAttempt(
            TranslationAttempt currentBest,
            TranslationAttempt segmentedAttempt
    ) {
        if (segmentedAttempt == null) {
            return false;
        }
        if (currentBest == null) {
            return true;
        }

        double scoreGain = segmentedAttempt.adjustedScore() - currentBest.adjustedScore();
        if (scoreGain >= SEGMENTED_MIN_GAIN) {
            return true;
        }

        int bestDots = Math.max(1, currentBest.dotCount());
        int segmentedDots = segmentedAttempt.dotCount();
        double dotGainRatio = (double) segmentedDots / bestDots;
        if (dotGainRatio >= SEGMENTED_DOT_GAIN_RATIO
                && segmentedAttempt.adjustedScore() >= currentBest.adjustedScore() - SEGMENTED_SCORE_TOLERANCE) {
            return true;
        }

        return currentBest.getQuality().isLowConfidence()
                && segmentedDots > currentBest.dotCount() + 20
                && segmentedAttempt.lineCount() >= currentBest.lineCount();
    }

    private String appendWarning(String current, String extra) {
        if (extra == null || extra.isBlank()) {
            return current;
        }
        if (current == null || current.isBlank()) {
            return extra;
        }
        if (current.contains(extra)) {
            return current;
        }
        return current + " " + extra;
    }

    private List<VisionDetectedDot> toApiDots(DotDetectionResponseDto detectionResponse) {
        if (detectionResponse == null || detectionResponse.getDots() == null) {
            return List.of();
        }
        return detectionResponse.getDots().stream()
                .map(dot -> new VisionDetectedDot(dot.getX(), dot.getY(), dot.getConfidence()))
                .collect(Collectors.toList());
    }

    private List<VisionLowConfidenceCell> toApiLowConfidenceCells(VisionCellReviewAssessment assessment) {
        if (assessment == null || assessment.getLowConfidenceCells() == null) {
            return List.of();
        }
        return assessment.getLowConfidenceCells().stream()
                .map(this::toApiLowConfidenceCell)
                .collect(Collectors.toList());
    }

    private VisionLowConfidenceCell toApiLowConfidenceCell(VisionCellFlag flag) {
        return new VisionLowConfidenceCell(
                flag.getCellRow(),
                flag.getCellColumn(),
                flag.getConfidence(),
                flag.getReason()
        );
    }

    private TranslationAttempt buildConsensusAttempt(List<TranslationAttempt> attempts, String table) {
        if (attempts == null || attempts.size() < 2) {
            return null;
        }

        List<DotDetectionResponseDto> responses = attempts.stream()
                .filter(Objects::nonNull)
                .map(TranslationAttempt::getDetectionResponse)
                .filter(Objects::nonNull)
                .filter(r -> r.getDots() != null && !r.getDots().isEmpty())
                .toList();

        if (responses.size() < 2) {
            return null;
        }

        boolean allResponsesAlreadyCellConsensus = responses.stream().allMatch(
                r -> isDotNeuralNetResponse(r)
                        && r.getBrailleUnicode() != null
                        && !r.getBrailleUnicode().isBlank()
        );
        if (allResponsesAlreadyCellConsensus) {
            return null;
        }

        DotDetectionResponseDto fused = dotConsensusFuser.fuse(responses);
        if (fused == null || fused.getDots() == null || fused.getDots().isEmpty()) {
            return null;
        }

        TranslationAttempt attempt = buildAttempt("consensus-fused", fused, table);
        if (attempt.getBrailleUnicode().isBlank() && attempt.getTranslatedText().isBlank()) {
            return null;
        }
        return attempt;
    }

    private boolean isDotNeuralNetResponse(DotDetectionResponseDto response) {
        if (response == null || response.getModelType() == null) {
            return false;
        }
        return response.getModelType().toLowerCase(Locale.ROOT).contains("dotneuralnet");
    }

    private TranslationAttempt runAttempt(VisionImageVariant variant, String table) {
        try {
            DotDetectionResponseDto detectionResponse = visionClient.detectDots(
                    variant.getFilename(),
                    variant.getContentType(),
                    variant.getBytes()
            );
            return buildAttempt(variant.getLabel(), detectionResponse, table);
        } catch (Exception ex) {
            log.warn("Vision attempt failed for variant={}", variant.getLabel(), ex);
            return TranslationAttempt.failed(variant.getLabel());
        }
    }

    private TranslationAttempt runSegmentedAttempt(VisionImageVariant variant, String table) {
        BufferedImage image = decodeImage(variant.getBytes());
        if (image == null) {
            return null;
        }

        DotDetectionResponseDto merged = detectDotsByHorizontalSegments(variant, image);
        if (merged == null) {
            return null;
        }
        return buildAttempt(variant.getLabel() + "-segmented", merged, table);
    }

    private DotDetectionResponseDto detectDotsByHorizontalSegments(
            VisionImageVariant variant,
            BufferedImage sourceImage
    ) {
        int width = sourceImage.getWidth();
        int height = sourceImage.getHeight();
        if (width <= 0 || height <= 0) {
            return null;
        }

        int bandHeight = clampInt(height / 4, 220, 420);
        int overlap = Math.max(45, bandHeight / 5);
        int step = Math.max(60, bandHeight - overlap);

        List<DetectedDotDto> mergedDots = new ArrayList<>();
        int index = 0;
        for (int startY = 0; startY < height; startY += step) {
            int endY = Math.min(height, startY + bandHeight);
            int currentHeight = endY - startY;
            if (currentHeight <= 0) {
                break;
            }

            BufferedImage band = sourceImage.getSubimage(0, startY, width, currentHeight);
            byte[] bandBytes = encodePng(band);
            if (bandBytes.length == 0) {
                if (endY >= height) break;
                index++;
                continue;
            }

            DotDetectionResponseDto bandResponse;
            try {
                bandResponse = visionClient.detectDots(
                        variant.getFilename() + "-band-" + index + ".png",
                        "image/png",
                        bandBytes
                );
            } catch (Exception ex) {
                log.debug("Band detection failed at y={} for variant={}", startY, variant.getLabel(), ex);
                if (endY >= height) break;
                index++;
                continue;
            }

            if (bandResponse != null && bandResponse.getDots() != null) {
                for (DetectedDotDto dot : bandResponse.getDots()) {
                    mergedDots.add(new DetectedDotDto(dot.getX(), dot.getY() + startY, dot.getConfidence()));
                }
            }

            if (endY >= height) {
                break;
            }
            index++;
        }

        DotDetectionResponseDto merged = new DotDetectionResponseDto();
        merged.setImageWidth(width);
        merged.setImageHeight(height);
        merged.setDots(deduplicateDots(mergedDots, 2.0));
        return merged;
    }

    private List<DetectedDotDto> deduplicateDots(List<DetectedDotDto> dots, double distance) {
        if (dots == null || dots.isEmpty()) {
            return List.of();
        }

        List<DetectedDotDto> sorted = new ArrayList<>(dots);
        sorted.sort(Comparator
                .comparingDouble(DetectedDotDto::getY)
                .thenComparingDouble(DetectedDotDto::getX));

        List<DetectedDotDto> unique = new ArrayList<>();
        for (DetectedDotDto dot : sorted) {
            int matchIndex = -1;
            for (int i = unique.size() - 1; i >= 0; i--) {
                DetectedDotDto existing = unique.get(i);
                if (dot.getY() - existing.getY() > distance) {
                    break;
                }
                if (Math.abs(dot.getX() - existing.getX()) <= distance
                        && Math.abs(dot.getY() - existing.getY()) <= distance) {
                    matchIndex = i;
                    break;
                }
            }

            if (matchIndex < 0) {
                unique.add(dot);
                continue;
            }

            DetectedDotDto existing = unique.get(matchIndex);
            if (dot.getConfidence() > existing.getConfidence()) {
                unique.set(matchIndex, dot);
            }
        }
        return unique;
    }

    private TranslationAttempt buildAttempt(
            String variantLabel,
            DotDetectionResponseDto detectionResponse,
            String table
    ) {
        if (detectionResponse == null) {
            VisionQualityAssessment quality = qualityAssessor.assess(null, List.of(), List.of(), "");
            return new TranslationAttempt(variantLabel, null, List.of(), List.of(), "", "", quality);
        }

        List<NormalizedDot> normalizedDots = dotNormalizer.normalize(detectionResponse);
        List<BrailleCell> cells = normalizedDots.isEmpty()
                ? List.of()
                : cellBuilder.buildCells(normalizedDots);

        String brailleUnicode = cells.isEmpty() ? "" : unicodeAssembler.assemble(cells);
        if (brailleUnicode == null) {
            brailleUnicode = "";
        }

        String sourceBrailleUnicode = detectionResponse.getBrailleUnicode();
        if (sourceBrailleUnicode == null) {
            sourceBrailleUnicode = "";
        }
        String sourceModelType = detectionResponse.getModelType();
        boolean sourceIsDotNeuralNet = sourceModelType != null
                && sourceModelType.toLowerCase(Locale.ROOT).contains("dotneuralnet");
        int sourceUncertainCellsCount = detectionResponse.getUncertainCellsCount() == null
                ? 0
                : detectionResponse.getUncertainCellsCount();
        boolean sourceReviewRecommended = Boolean.TRUE.equals(detectionResponse.getReviewRecommended());
        String sourceQualityWarning = detectionResponse.getQualityWarning();

        if (sourceIsDotNeuralNet && !sourceBrailleUnicode.isBlank()) {
            brailleUnicode = sourceBrailleUnicode;
            log.info(
                    "Using brailleUnicode directly from vision service for variant={} modelType={}",
                    variantLabel,
                    sourceModelType
            );
        } else if (brailleUnicode.isBlank() && !sourceBrailleUnicode.isBlank()) {
            brailleUnicode = sourceBrailleUnicode;
        }

        String translatedText = "";
        boolean translationFailed = false;
        OrientationSelection orientationSelection = null;
        BrailleTranslator overrideTranslator = tableRegistry.resolveTranslator(table);
        if (!brailleUnicode.isBlank()) {
            try {
                if (sourceIsDotNeuralNet) {
                    orientationSelection = selectOrientationForDotNeuralNet(
                            brailleUnicode,
                            table,
                            overrideTranslator
                    );
                    brailleUnicode = orientationSelection.getBrailleUnicode();
                    translatedText = orientationSelection.getTranslatedText();
                } else {
                    translatedText = translationPipeline.translate(brailleUnicode, overrideTranslator);
                }
            } catch (Exception ex) {
                translationFailed = true;
                log.debug("Liblouis translation failed for variant={}", variantLabel, ex);
            }
        }

        VisionQualityAssessment quality = qualityAssessor.assess(
                detectionResponse,
                normalizedDots,
                cells,
                translatedText
        );
        if (sourceReviewRecommended) {
            String reviewWarning = sourceQualityWarning;
            if (reviewWarning == null || reviewWarning.isBlank()) {
                reviewWarning = "Vision model flagged low-consensus cells. Manual review is recommended.";
            }
            String mergedWarning = appendWarning(quality.getWarning(), reviewWarning);
            quality = new VisionQualityAssessment(
                    Math.min(quality.getScore(), 42.0),
                    true,
                    mergedWarning
            );
            log.info(
                    "Vision source requested review for variant={} uncertainCells={}",
                    variantLabel,
                    sourceUncertainCellsCount
            );
        } else if (sourceQualityWarning != null && !sourceQualityWarning.isBlank()) {
            String mergedWarning = appendWarning(quality.getWarning(), sourceQualityWarning);
            quality = new VisionQualityAssessment(
                    quality.getScore(),
                    quality.isLowConfidence(),
                    mergedWarning
            );
        }
        if (orientationSelection != null && orientationSelection.isAlternativeSelected()) {
            String orientationWarning = "Detected mirrored Braille direction. Applied "
                    + orientationSelection.getLabel()
                    + " orientation correction.";
            quality = new VisionQualityAssessment(
                    Math.min(quality.getScore(), 62.0),
                    quality.isLowConfidence(),
                    appendWarning(quality.getWarning(), orientationWarning)
            );
            log.info(
                    "Orientation correction applied for variant={} strategy={} score={}",
                    variantLabel,
                    orientationSelection.getLabel(),
                    String.format("%.3f", orientationSelection.getScore())
            );
        }
        if (translationFailed) {
            String warning = quality.getWarning();
            if (warning == null || warning.isBlank()) {
                warning = "Unable to decode reliable text from detected Braille. Try a cleaner scan.";
            }
            quality = new VisionQualityAssessment(
                    Math.min(quality.getScore(), 35.0),
                    true,
                    warning
            );
        }

        return new TranslationAttempt(
                variantLabel,
                detectionResponse,
                normalizedDots,
                cells,
                brailleUnicode,
                translatedText,
                quality
        );
    }

    private OrientationSelection selectOrientationForDotNeuralNet(
            String brailleUnicode,
            String table,
            BrailleTranslator overrideTranslator
    ) {
        if (brailleUnicode == null || brailleUnicode.isBlank()) {
            return new OrientationSelection("original", "", "", 0.0, false);
        }

        boolean englishTable = table == null
                || table.isBlank()
                || table.toLowerCase(Locale.ROOT).contains("en");

        List<OrientationVariant> variants = buildOrientationVariants(brailleUnicode);
        OrientationSelection original = null;
        OrientationSelection best = null;

        for (OrientationVariant variant : variants) {
            String translated;
            try {
                translated = translationPipeline.translate(variant.getBrailleUnicode(), overrideTranslator);
            } catch (Exception ex) {
                log.debug("Orientation candidate translation failed: {}", variant.getLabel(), ex);
                continue;
            }

            double score = scoreOrientationText(translated, englishTable);
            OrientationSelection evaluated = new OrientationSelection(
                    variant.getLabel(),
                    variant.getBrailleUnicode(),
                    translated,
                    score,
                    false
            );

            if ("original".equals(variant.getLabel())) {
                original = evaluated;
            }
            if (best == null || evaluated.getScore() > best.getScore()) {
                best = evaluated;
            }
        }

        if (best == null) {
            throw new IllegalStateException("No orientation candidate could be translated");
        }
        if (original == null) {
            original = best;
        }

        if (shouldAdoptOrientationCandidate(original, best)) {
            return new OrientationSelection(
                    best.getLabel(),
                    best.getBrailleUnicode(),
                    best.getTranslatedText(),
                    best.getScore(),
                    true
            );
        }
        return original;
    }

    private boolean shouldAdoptOrientationCandidate(
            OrientationSelection original,
            OrientationSelection best
    ) {
        if (best == null) {
            return false;
        }
        if (original == null) {
            return !"original".equals(best.getLabel());
        }
        if ("original".equals(best.getLabel())) {
            return false;
        }
        if (original.getTranslatedText() == null || original.getTranslatedText().isBlank()) {
            return true;
        }
        return best.getScore() >= original.getScore() + ORIENTATION_SWITCH_THRESHOLD;
    }

    private List<OrientationVariant> buildOrientationVariants(String brailleUnicode) {
        List<OrientationVariant> variants = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        addOrientationVariant(variants, seen, "original", brailleUnicode);

        String reversed = reverseBrailleLineOrder(brailleUnicode);
        addOrientationVariant(variants, seen, "reverse-lines", reversed);

        String mirrored = mirrorBrailleCells(brailleUnicode);
        addOrientationVariant(variants, seen, "mirror-cells", mirrored);

        String mirroredReversed = reverseBrailleLineOrder(mirrored);
        addOrientationVariant(variants, seen, "mirror-and-reverse", mirroredReversed);

        return variants;
    }

    private void addOrientationVariant(
            List<OrientationVariant> variants,
            Set<String> seen,
            String label,
            String brailleUnicode
    ) {
        if (brailleUnicode == null || brailleUnicode.isBlank()) {
            return;
        }
        if (!seen.add(brailleUnicode)) {
            return;
        }
        variants.add(new OrientationVariant(label, brailleUnicode));
    }

    private String reverseBrailleLineOrder(String brailleUnicode) {
        String[] lines = brailleUnicode.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            char[] chars = lines[i].toCharArray();
            for (int left = 0, right = chars.length - 1; left < right; left++, right--) {
                char tmp = chars[left];
                chars[left] = chars[right];
                chars[right] = tmp;
            }
            lines[i] = new String(chars);
        }
        return String.join("\n", lines);
    }

    private String mirrorBrailleCells(String brailleUnicode) {
        StringBuilder out = new StringBuilder(brailleUnicode.length());
        for (int i = 0; i < brailleUnicode.length(); i++) {
            out.append(mirrorBrailleCell(brailleUnicode.charAt(i)));
        }
        return out.toString();
    }

    private char mirrorBrailleCell(char ch) {
        if (ch < 0x2800 || ch > 0x28FF) {
            return ch;
        }
        int mask = ch - 0x2800;
        int mirrored = 0;

        if ((mask & 0x01) != 0) mirrored |= 0x08; // 1 -> 4
        if ((mask & 0x02) != 0) mirrored |= 0x10; // 2 -> 5
        if ((mask & 0x04) != 0) mirrored |= 0x20; // 3 -> 6
        if ((mask & 0x08) != 0) mirrored |= 0x01; // 4 -> 1
        if ((mask & 0x10) != 0) mirrored |= 0x02; // 5 -> 2
        if ((mask & 0x20) != 0) mirrored |= 0x04; // 6 -> 3
        if ((mask & 0x40) != 0) mirrored |= 0x80; // 7 -> 8
        if ((mask & 0x80) != 0) mirrored |= 0x40; // 8 -> 7

        return (char) (0x2800 + mirrored);
    }

    private double scoreOrientationText(String text, boolean englishTable) {
        if (text == null || text.isBlank()) {
            return 0.0;
        }

        int letters = 0;
        int digits = 0;
        int punctuation = 0;
        int punctuationInContext = 0;
        int noise = 0;
        int nonWhitespace = 0;

        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (Character.isWhitespace(ch)) {
                continue;
            }
            nonWhitespace++;
            if (Character.isLetter(ch)) {
                letters++;
                continue;
            }
            if (Character.isDigit(ch)) {
                digits++;
                continue;
            }
            if (isCommonPunctuation(ch)) {
                punctuation++;
                char prev = i > 0 ? text.charAt(i - 1) : '\0';
                char next = i + 1 < text.length() ? text.charAt(i + 1) : '\0';
                if ((Character.isLetterOrDigit(prev) || prev == '"' || prev == '\'')
                        && (next == '\0' || Character.isWhitespace(next) || isCommonPunctuation(next))) {
                    punctuationInContext++;
                }
                continue;
            }
            noise++;
        }

        if (nonWhitespace == 0) {
            return 0.0;
        }

        double readableRatio = (double) (letters + digits + punctuation) / nonWhitespace;
        double noiseRatio = (double) noise / nonWhitespace;
        double punctuationPlacement = punctuation == 0
                ? 0.5
                : (double) punctuationInContext / punctuation;

        String[] words = text.toLowerCase(Locale.ROOT).split("[^a-z]+");
        int alphaWords = 0;
        int vowelWords = 0;
        int hardConsonantWords = 0;
        int commonWordHits = 0;

        for (String word : words) {
            if (word == null || word.length() < 2) {
                continue;
            }
            alphaWords++;
            if (containsVowel(word)) {
                vowelWords++;
            } else if (word.length() >= 5) {
                hardConsonantWords++;
            }
            if (englishTable && COMMON_ENGLISH_WORDS.contains(word)) {
                commonWordHits++;
            }
        }

        double vowelRatio = alphaWords == 0 ? 0.0 : (double) vowelWords / alphaWords;
        double hardConsonantPenalty = alphaWords == 0 ? 0.0 : (double) hardConsonantWords / alphaWords;
        double commonWordBoost = englishTable
                ? Math.min(1.0, commonWordHits / Math.max(1.0, alphaWords * 0.35))
                : 0.0;

        double score = 0.0;
        score += readableRatio * 0.42;
        score += vowelRatio * 0.20;
        score += punctuationPlacement * 0.16;
        score += commonWordBoost * 0.22;
        score -= noiseRatio * 0.33;
        score -= hardConsonantPenalty * 0.20;

        return Math.max(0.0, Math.min(1.0, score));
    }

    private boolean containsVowel(String word) {
        for (int i = 0; i < word.length(); i++) {
            char ch = word.charAt(i);
            if ("aeiouy".indexOf(ch) >= 0) {
                return true;
            }
        }
        return false;
    }

    private boolean isCommonPunctuation(char ch) {
        return ".,;:?!'\"()[]{}-_/".indexOf(ch) >= 0;
    }

    private boolean shouldRunFallbacks(
            TranslationAttempt originalAttempt,
            boolean pageImageLikely,
            boolean highVarianceGeometry,
            int variantCount
    ) {
        if (variantCount <= 1 || originalAttempt == null) {
            return false;
        }
        if (highVarianceGeometry) {
            return true;
        }
        if (originalAttempt.getQuality().isLowConfidence()) {
            return true;
        }
        if (originalAttempt.getQuality().getScore() < GOOD_QUALITY_SCORE) {
            return true;
        }
        return pageImageLikely && originalAttempt.lineCount() < 4;
    }

    private VisionImageVariant selectSegmentedBaseVariant(
            List<VisionImageVariant> variants,
            TranslationAttempt bestAttempt
    ) {
        if (bestAttempt != null) {
            for (VisionImageVariant variant : variants) {
                if (bestAttempt.getVariantLabel().startsWith(variant.getLabel())) {
                    return variant;
                }
            }
        }

        for (VisionImageVariant variant : variants) {
            if ("enhanced-contrast".equals(variant.getLabel())) {
                return variant;
            }
        }
        return variants.get(0);
    }

    private TranslationAttempt chooseBestAttempt(List<TranslationAttempt> attempts) {
        TranslationAttempt best = null;
        double bestScore = Double.NEGATIVE_INFINITY;

        for (TranslationAttempt attempt : attempts) {
            if (attempt == null) {
                continue;
            }
            double score = attempt.adjustedScore();
            if (score > bestScore) {
                best = attempt;
                bestScore = score;
            }
        }
        return best;
    }

    private void logAttempt(TranslationAttempt attempt) {
        if (attempt == null) {
            return;
        }
        int dots = attempt.getDetectionResponse() == null || attempt.getDetectionResponse().getDots() == null
                ? 0
                : attempt.getDetectionResponse().getDots().size();
        int width = attempt.getDetectionResponse() == null ? -1 : attempt.getDetectionResponse().getImageWidth();
        int height = attempt.getDetectionResponse() == null ? -1 : attempt.getDetectionResponse().getImageHeight();

        log.info(
                "ATTEMPT variant={} img={}x{} dots={} normalized={} cells={} lines={} score={} lowConfidence={}",
                attempt.getVariantLabel(),
                width,
                height,
                dots,
                attempt.getNormalizedDots().size(),
                attempt.getCells().size(),
                attempt.lineCount(),
                String.format("%.1f", attempt.getQuality().getScore()),
                attempt.getQuality().isLowConfidence()
        );
    }

    private BufferedImage decodeImage(byte[] imageBytes) {
        if (imageBytes == null || imageBytes.length == 0) {
            return null;
        }
        try (ByteArrayInputStream in = new ByteArrayInputStream(imageBytes)) {
            return ImageIO.read(in);
        } catch (IOException ex) {
            return null;
        }
    }

    private byte[] encodePng(BufferedImage image) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException ex) {
            return new byte[0];
        }
    }

    private int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private void printNormalizedDotStats(List<NormalizedDot> normalizedDots) {

        int minRow = Integer.MAX_VALUE, maxRow = Integer.MIN_VALUE;
        int minCol = Integer.MAX_VALUE, maxCol = Integer.MIN_VALUE;

        Map<Integer, Long> rowCounts = new TreeMap<>();
        Map<Integer, Long> colCounts = new TreeMap<>();

        for (NormalizedDot d : normalizedDots) {
            minRow = Math.min(minRow, d.getRow());
            maxRow = Math.max(maxRow, d.getRow());
            minCol = Math.min(minCol, d.getColumn());
            maxCol = Math.max(maxCol, d.getColumn());

            rowCounts.put(d.getRow(), rowCounts.getOrDefault(d.getRow(), 0L) + 1);
            colCounts.put(d.getColumn(), colCounts.getOrDefault(d.getColumn(), 0L) + 1);
        }

        log.debug("NORMALIZED ROW range: {} .. {}", minRow, maxRow);
        log.debug("NORMALIZED COL range: {} .. {}", minCol, maxCol);

        // Print top populated rows/cols (helps detect clustering failure)
        log.debug("TOP ROW COUNTS: {}", topN(rowCounts, 6));
        log.debug("TOP COL COUNTS: {}", topN(colCounts, 10));
    }

    private void printCellStats(List<BrailleCell> cells) {

        Map<Integer, Long> cellsPerRow = cells.stream()
                .collect(Collectors.groupingBy(BrailleCell::getCellRow, TreeMap::new, Collectors.counting()));

        log.debug("CELLS PER ROW: {}", cellsPerRow);

        // Column gap stats: if huge gaps appear everywhere, normalization is off.
        List<Integer> cols = cells.stream()
                .map(BrailleCell::getCellColumn)
                .sorted()
                .toList();

        if (cols.size() >= 2) {
            List<Integer> gaps = new ArrayList<>();
            for (int i = 1; i < cols.size(); i++) {
                gaps.add(cols.get(i) - cols.get(i - 1));
            }
            gaps.sort(Integer::compareTo);
            int medianGap = gaps.get(gaps.size() / 2);
            int maxGap = gaps.get(gaps.size() - 1);

            log.debug("CELL COLUMN GAP median={} max={}", medianGap, maxGap);
        }
    }

    private String topN(Map<Integer, Long> counts, int n) {
        return counts.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(n)
                .map(e -> e.getKey() + ":" + e.getValue())
                .collect(Collectors.joining(", "));
    }

    private String toBrailleHex(String s) {
        if (s == null || s.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            int cp = s.charAt(i);
            sb.append(String.format("U+%04X", cp));
            if (i < s.length() - 1) sb.append(" ");
        }
        return sb.toString();
    }

    private static class TranslationAttempt {
        private final String variantLabel;
        private final DotDetectionResponseDto detectionResponse;
        private final List<NormalizedDot> normalizedDots;
        private final List<BrailleCell> cells;
        private final String brailleUnicode;
        private final String translatedText;
        private final VisionQualityAssessment quality;

        private TranslationAttempt(
                String variantLabel,
                DotDetectionResponseDto detectionResponse,
                List<NormalizedDot> normalizedDots,
                List<BrailleCell> cells,
                String brailleUnicode,
                String translatedText,
                VisionQualityAssessment quality
        ) {
            this.variantLabel = variantLabel;
            this.detectionResponse = detectionResponse;
            this.normalizedDots = normalizedDots == null ? List.of() : normalizedDots;
            this.cells = cells == null ? List.of() : cells;
            this.brailleUnicode = brailleUnicode == null ? "" : brailleUnicode;
            this.translatedText = translatedText == null ? "" : translatedText;
            this.quality = quality == null
                    ? new VisionQualityAssessment(0.0, true, null)
                    : quality;
        }

        private static TranslationAttempt failed(String variantLabel) {
            return new TranslationAttempt(
                    variantLabel,
                    null,
                    List.of(),
                    List.of(),
                    "",
                    "",
                    new VisionQualityAssessment(0.0, true, null)
            );
        }

        private String getVariantLabel() {
            return variantLabel;
        }

        private DotDetectionResponseDto getDetectionResponse() {
            return detectionResponse;
        }

        private List<NormalizedDot> getNormalizedDots() {
            return normalizedDots;
        }

        private List<BrailleCell> getCells() {
            return cells;
        }

        private String getBrailleUnicode() {
            return brailleUnicode;
        }

        private String getTranslatedText() {
            return translatedText;
        }

        private VisionQualityAssessment getQuality() {
            return quality;
        }

        private int lineCount() {
            if (cells.isEmpty()) {
                return 0;
            }
            return (int) cells.stream().map(BrailleCell::getCellRow).distinct().count();
        }

        private int dotCount() {
            if (detectionResponse == null || detectionResponse.getDots() == null) {
                return 0;
            }
            return detectionResponse.getDots().size();
        }

        private double adjustedScore() {
            double score = quality.getScore();
            if ("original".equals(variantLabel)) {
                score += ORIGINAL_VARIANT_BONUS;
            }
            if (!brailleUnicode.isBlank()) {
                score += 1.5;
            }
            if (!translatedText.isBlank()) {
                score += 1.5;
            }
            return score;
        }
    }

    private static class OrientationVariant {
        private final String label;
        private final String brailleUnicode;

        private OrientationVariant(String label, String brailleUnicode) {
            this.label = label == null ? "original" : label;
            this.brailleUnicode = brailleUnicode == null ? "" : brailleUnicode;
        }

        private String getLabel() {
            return label;
        }

        private String getBrailleUnicode() {
            return brailleUnicode;
        }
    }

    private static class OrientationSelection {
        private final String label;
        private final String brailleUnicode;
        private final String translatedText;
        private final double score;
        private final boolean alternativeSelected;

        private OrientationSelection(
                String label,
                String brailleUnicode,
                String translatedText,
                double score,
                boolean alternativeSelected
        ) {
            this.label = label == null ? "original" : label;
            this.brailleUnicode = brailleUnicode == null ? "" : brailleUnicode;
            this.translatedText = translatedText == null ? "" : translatedText;
            this.score = score;
            this.alternativeSelected = alternativeSelected;
        }

        private String getLabel() {
            return label;
        }

        private String getBrailleUnicode() {
            return brailleUnicode;
        }

        private String getTranslatedText() {
            return translatedText;
        }

        private double getScore() {
            return score;
        }

        private boolean isAlternativeSelected() {
            return alternativeSelected;
        }
    }

    private OutputFormat parseFormat(String format) {
        if (format == null || format.isBlank()) {
            return null;
        }
        try {
            return OutputFormat.valueOf(format.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
