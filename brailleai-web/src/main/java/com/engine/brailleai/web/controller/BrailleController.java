package com.engine.brailleai.web.controller;

import com.engine.brailleai.api.dto.BrailleRequest;
import com.engine.brailleai.api.dto.BrailleResponse;
import com.engine.brailleai.api.dto.BrailleTableResponse;
import com.engine.brailleai.api.dto.TranslationDirection;
import com.engine.brailleai.api.service.BrailleTranslator;
import com.engine.brailleai.core.pipeline.BrailleTranslationPipeline;
import com.engine.brailleai.liblouis.LiblouisTableRegistry;
import com.engine.brailleai.output.format.FormatRouter;
import com.engine.brailleai.output.format.OutputFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;

/**
 * REST endpoints for Braille translation and file export.
 */
@RestController
@RequestMapping("/api/braille")
public class BrailleController {

    private final BrailleTranslationPipeline pipeline;
    private final BrailleTranslator defaultTranslator;
    private final FormatRouter formatRouter;
    private final LiblouisTableRegistry tableRegistry;

    public BrailleController(
            BrailleTranslationPipeline pipeline,
            BrailleTranslator defaultTranslator,
            FormatRouter formatRouter,
            LiblouisTableRegistry tableRegistry
    ) {
        this.pipeline = pipeline;
        this.defaultTranslator = defaultTranslator;
        this.formatRouter = formatRouter;
        this.tableRegistry = tableRegistry;
    }
    /**
     * Translate a Braille Unicode string into plain text.
     */
    @PostMapping(
            value = "/translate",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<BrailleResponse> translate(
            @RequestBody BrailleRequest request
    ) {
        String input = request.getResolvedInput();
        InputType inputType = classifyInputType(input);
        if (inputType == InputType.EMPTY) {
            return ResponseEntity.badRequest()
                    .body(BrailleResponse.error("Input is required."));
        }
        if (inputType == InputType.MIXED) {
            return ResponseEntity.badRequest()
                    .body(BrailleResponse.error(
                            "Input cannot mix Braille and regular text. Use one format at a time."
                    ));
        }

        BrailleTranslator overrideTranslator =
                tableRegistry.resolveTranslator(request.getTable());
        BrailleTranslator activeTranslator =
                overrideTranslator == null ? defaultTranslator : overrideTranslator;

        TranslationDirection direction = resolveDirection(request, inputType);
        String validationError = validateDirectionAgainstInput(direction, inputType);
        if (validationError != null) {
            return ResponseEntity.badRequest().body(BrailleResponse.error(validationError));
        }

        String translatedText;
        if (direction == TranslationDirection.BRAILLE_TO_TEXT) {
            translatedText = pipeline.translate(input, activeTranslator);
        } else {
            translatedText = activeTranslator.translateTextToBraille(input);
        }

        return ResponseEntity.ok(
                BrailleResponse.success(
                        translatedText,
                        direction,
                        direction == TranslationDirection.BRAILLE_TO_TEXT ? "braille" : "text"
                )
        );
    }


    /**
     * Download translated output as PDF, DOCX, or BRF.
     */
    @PostMapping(
            value = "/download",
            consumes = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<byte[]> download(
            @RequestBody BrailleRequest request
    ) {
        String input = request.getResolvedInput();
        InputType inputType = classifyInputType(input);
        if (inputType == InputType.EMPTY || inputType == InputType.MIXED) {
            return ResponseEntity.badRequest().body(new byte[0]);
        }

        OutputFormat format = request.getOutputFormat();
        if (format == null) {
            return ResponseEntity.badRequest().body(new byte[0]);
        }

        BrailleTranslator overrideTranslator =
                tableRegistry.resolveTranslator(request.getTable());
        BrailleTranslator activeTranslator =
                overrideTranslator == null ? defaultTranslator : overrideTranslator;

        TranslationDirection direction = resolveDirection(request, inputType);
        if (validateDirectionAgainstInput(direction, inputType) != null) {
            return ResponseEntity.badRequest().body(new byte[0]);
        }
        String outputText;

        if (format == OutputFormat.BRF) {
            // BRF is an ASCII representation of Braille cells, so we feed Braille Unicode text.
            outputText = direction == TranslationDirection.BRAILLE_TO_TEXT
                    ? input
                    : activeTranslator.translateTextToBraille(input);
        } else {
            outputText = direction == TranslationDirection.BRAILLE_TO_TEXT
                    ? pipeline.translate(input, activeTranslator)
                    : activeTranslator.translateTextToBraille(input);
        }

        byte[] fileBytes =
                formatRouter.generate(outputText, format);

        String filename = "braille-translation." +
                format.name().toLowerCase(Locale.ROOT);

        MediaType mediaType = switch (format) {
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

    /**
     * Sanity check for the translation pipeline using a known Braille token.
     */
    @GetMapping("/health")
    public ResponseEntity<String> healthCheck() {
        String result = pipeline.translate("\u281e\u2813\u2811");

        if (!"the".equalsIgnoreCase(result.trim())) {
            throw new IllegalStateException(
                    "Liblouis health check failed. Unexpected output: " + result
            );
        }

        return ResponseEntity.ok("Liblouis OK");
    }

    /**
     * List available Liblouis tables for UI selection.
     */
    @GetMapping("/tables")
    public ResponseEntity<BrailleTableResponse> listTables() {
        BrailleTableResponse response = new BrailleTableResponse(
                tableRegistry.getDefaultTableName(),
                tableRegistry.listTables()
        );
        return ResponseEntity.ok(response);
    }

    private TranslationDirection resolveDirection(BrailleRequest request, InputType inputType) {
        TranslationDirection requestedDirection = request.getDirection();
        if (requestedDirection != null && requestedDirection != TranslationDirection.AUTO) {
            return requestedDirection;
        }
        return detectInputDirection(inputType);
    }

    private TranslationDirection detectInputDirection(InputType inputType) {
        if (inputType == InputType.BRAILLE) {
            return TranslationDirection.BRAILLE_TO_TEXT;
        }
        return TranslationDirection.TEXT_TO_BRAILLE;
    }

    private String validateDirectionAgainstInput(TranslationDirection direction, InputType inputType) {
        if (direction == TranslationDirection.BRAILLE_TO_TEXT && inputType != InputType.BRAILLE) {
            return "Braille to Text mode requires Braille Unicode input only.";
        }
        if (direction == TranslationDirection.TEXT_TO_BRAILLE && inputType != InputType.TEXT) {
            return "Text to Braille mode requires regular text input only.";
        }
        return null;
    }

    private InputType classifyInputType(String input) {
        if (input == null || input.isBlank()) {
            return InputType.EMPTY;
        }

        boolean sawBraille = false;
        boolean sawNonBraille = false;

        for (int i = 0; i < input.length(); i++) {
            char ch = input.charAt(i);
            if (Character.isWhitespace(ch)) {
                continue;
            }
            if (ch >= '\u2800' && ch <= '\u28FF') {
                sawBraille = true;
            } else {
                sawNonBraille = true;
            }
            if (sawBraille && sawNonBraille) {
                return InputType.MIXED;
            }
        }

        if (!sawBraille && !sawNonBraille) {
            return InputType.EMPTY;
        }
        return sawBraille ? InputType.BRAILLE : InputType.TEXT;
    }

    private enum InputType {
        EMPTY,
        BRAILLE,
        TEXT,
        MIXED
    }

}
