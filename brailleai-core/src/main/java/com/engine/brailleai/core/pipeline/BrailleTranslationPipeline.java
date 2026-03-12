package com.engine.brailleai.core.pipeline;

import com.engine.brailleai.api.service.BrailleTranslator;
import com.engine.brailleai.core.normalize.LineBreakNormalizer;
import com.engine.brailleai.core.normalize.UnicodeSanitizer;
import com.engine.brailleai.core.normalize.WhitespaceNormalizer;
import com.engine.brailleai.core.postprocess.OutputCleaner;
import com.engine.brailleai.core.validate.BrailleValidator;

/**
 * Orchestrates the full Braille translation pipeline.
 *
 * <p>This class enforces the exact processing order defined in Phase 1:
 * validation → normalization → translation → post-processing.
 */
public class BrailleTranslationPipeline {

    private final BrailleValidator validator;
    private final WhitespaceNormalizer whitespaceNormalizer;
    private final LineBreakNormalizer lineBreakNormalizer;
    private final UnicodeSanitizer unicodeSanitizer;
    private final BrailleTranslator translator;
    private final OutputCleaner outputCleaner;

    public BrailleTranslationPipeline(
            BrailleValidator validator,
            WhitespaceNormalizer whitespaceNormalizer,
            LineBreakNormalizer lineBreakNormalizer,
            UnicodeSanitizer unicodeSanitizer,
            BrailleTranslator translator,
            OutputCleaner outputCleaner
    ) {
        this.validator = validator;
        this.whitespaceNormalizer = whitespaceNormalizer;
        this.lineBreakNormalizer = lineBreakNormalizer;
        this.unicodeSanitizer = unicodeSanitizer;
        this.translator = translator;
        this.outputCleaner = outputCleaner;
    }

    /**
     * Executes the Braille translation pipeline.
     *
     * @param brailleUnicode raw Braille Unicode input
     * @return translated plain text
     */
    public String translate(String brailleUnicode) {
        return translate(brailleUnicode, null);
    }

    /**
     * Executes the Braille translation pipeline with an optional translator override.
     *
     * @param brailleUnicode raw Braille Unicode input
     * @param overrideTranslator translator to use instead of the default (nullable)
     * @return translated plain text
     */
    public String translate(String brailleUnicode, BrailleTranslator overrideTranslator) {
        BrailleTranslationContext context =
                new BrailleTranslationContext(brailleUnicode);

        // 1. Validation
        validator.validate(context);

        // 2. Normalization
        String normalized = whitespaceNormalizer.normalize(
                context.getOriginalBrailleUnicode());

        normalized = lineBreakNormalizer.normalize(normalized);
        normalized = unicodeSanitizer.sanitize(normalized);

        context.setNormalizedBrailleUnicode(normalized);

        // 3. Translation (Grade 2 handled by Liblouis)
        BrailleTranslator activeTranslator =
                overrideTranslator == null ? translator : overrideTranslator;
        String translated = activeTranslator.translate(normalized);

        // 4. Post-processing
        translated = outputCleaner.clean(translated);

        context.setTranslatedText(translated);

        return context.getTranslatedText();
    }
}
