package com.engine.brailleai.application.config;

import com.engine.brailleai.api.service.BrailleTranslator;
import com.engine.brailleai.core.normalize.LineBreakNormalizer;
import com.engine.brailleai.core.normalize.UnicodeSanitizer;
import com.engine.brailleai.core.normalize.WhitespaceNormalizer;
import com.engine.brailleai.core.pipeline.BrailleTranslationPipeline;
import com.engine.brailleai.core.postprocess.OutputCleaner;
import com.engine.brailleai.core.validate.BrailleValidator;
import com.engine.brailleai.liblouis.LiblouisTableRegistry;
import com.engine.brailleai.output.format.FormatRouter;
import com.engine.brailleai.output.format.OutputFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ApplicationConfigTest {

    @TempDir
    Path tempDir;

    @Test
    void createsCorePipelineBeansAndRoutesBrfOutput() {
        ApplicationConfig config = new ApplicationConfig();

        BrailleValidator validator = config.brailleValidator();
        WhitespaceNormalizer whitespaceNormalizer = config.whitespaceNormalizer();
        LineBreakNormalizer lineBreakNormalizer = config.lineBreakNormalizer();
        UnicodeSanitizer unicodeSanitizer = config.unicodeSanitizer();
        OutputCleaner outputCleaner = config.outputCleaner();
        BrailleTranslator translator = new EchoTranslator();

        BrailleTranslationPipeline pipeline = config.brailleTranslationPipeline(
                validator,
                whitespaceNormalizer,
                lineBreakNormalizer,
                unicodeSanitizer,
                translator,
                outputCleaner
        );
        FormatRouter router = config.formatRouter(
                config.pdfGenerator(),
                config.docxGenerator(),
                config.brfGenerator()
        );

        String translated = pipeline.translate("\u2801");
        byte[] brf = router.generate("\u2801", OutputFormat.BRF);

        assertEquals("ok", translated);
        assertEquals("A", new String(brf, StandardCharsets.US_ASCII));
    }

    @Test
    void createsLiblouisBeansFromSystemProperties() throws Exception {
        ApplicationConfig config = new ApplicationConfig();

        Path cliPath = tempDir.resolve("lou_translate.exe");
        Path tablePath = tempDir.resolve("en-us-g2.ctb");
        Files.writeString(cliPath, "stub");
        Files.writeString(tablePath, "stub");

        String oldCli = System.getProperty("LOUIS_CLI_PATH");
        String oldTable = System.getProperty("LOUIS_TABLE");

        try {
            System.setProperty("LOUIS_CLI_PATH", cliPath.toString());
            System.setProperty("LOUIS_TABLE", tablePath.toString());

            BrailleTranslator translator = config.brailleTranslator();
            LiblouisTableRegistry registry = config.liblouisTableRegistry();

            assertNotNull(translator);
            assertEquals("en-us-g2.ctb", registry.getDefaultTableName());
        } finally {
            restoreProperty("LOUIS_CLI_PATH", oldCli);
            restoreProperty("LOUIS_TABLE", oldTable);
        }
    }

    private void restoreProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    private static class EchoTranslator implements BrailleTranslator {
        @Override
        public String translate(String brailleUnicode) {
            return "ok";
        }
    }
}
