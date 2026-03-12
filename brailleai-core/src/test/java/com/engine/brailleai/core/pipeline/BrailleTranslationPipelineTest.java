package com.engine.brailleai.core.pipeline;

import com.engine.brailleai.api.service.BrailleTranslator;
import com.engine.brailleai.core.normalize.LineBreakNormalizer;
import com.engine.brailleai.core.normalize.UnicodeSanitizer;
import com.engine.brailleai.core.normalize.WhitespaceNormalizer;
import com.engine.brailleai.core.postprocess.OutputCleaner;
import com.engine.brailleai.core.validate.BrailleValidator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BrailleTranslationPipelineTest {

    @Test
    void usesOverrideTranslatorWhenProvided() {
        CapturingTranslator defaultTranslator = new CapturingTranslator("default");
        CapturingTranslator overrideTranslator = new CapturingTranslator("override");
        BrailleTranslationPipeline pipeline = new BrailleTranslationPipeline(
                new BrailleValidator(),
                new WhitespaceNormalizer(),
                new LineBreakNormalizer(),
                new UnicodeSanitizer(),
                defaultTranslator,
                new OutputCleaner()
        );

        String translated = pipeline.translate("\u2801", overrideTranslator);

        assertEquals("override", translated);
        assertEquals(0, defaultTranslator.callCount);
        assertEquals(1, overrideTranslator.callCount);
    }

    @Test
    void normalizesInputBeforeCallingTranslator() {
        CapturingTranslator translator = new CapturingTranslator("ok");
        BrailleTranslationPipeline pipeline = new BrailleTranslationPipeline(
                new BrailleValidator(),
                new WhitespaceNormalizer(),
                new LineBreakNormalizer(),
                new UnicodeSanitizer(),
                translator,
                new OutputCleaner()
        );

        pipeline.translate("\u2801\t\t\u2803\r\r\r\u2809");

        assertEquals("\u2801 \u2803\n\n\u2809", translator.lastInput);
    }

    private static class CapturingTranslator implements BrailleTranslator {
        private final String output;
        private int callCount;
        private String lastInput;

        private CapturingTranslator(String output) {
            this.output = output;
        }

        @Override
        public String translate(String brailleUnicode) {
            callCount++;
            lastInput = brailleUnicode;
            return output;
        }
    }
}
