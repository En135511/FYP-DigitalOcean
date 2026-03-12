package com.engine.brailleai.application.config;

import com.engine.brailleai.liblouis.LiblouisCliTranslator;
import com.engine.brailleai.liblouis.LiblouisTableRegistry;
import com.engine.brailleai.output.brf.BrfGenerator;
import com.engine.brailleai.output.docx.DocxGenerator;
import com.engine.brailleai.output.pdf.PdfGenerator;
import com.engine.brailleai.output.format.FormatRouter;
import com.engine.brailleai.api.service.BrailleTranslator;
import com.engine.brailleai.core.normalize.LineBreakNormalizer;
import com.engine.brailleai.core.normalize.UnicodeSanitizer;
import com.engine.brailleai.core.normalize.WhitespaceNormalizer;
import com.engine.brailleai.core.pipeline.BrailleTranslationPipeline;
import com.engine.brailleai.core.postprocess.OutputCleaner;
import com.engine.brailleai.core.validate.BrailleValidator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Central application wiring configuration.
 *
 * <p>This class defines how all core components are constructed
 * and connected together.
 */
@Configuration
public class ApplicationConfig {

    /* ---------------- Validation ---------------- */

    @Bean
    public BrailleValidator brailleValidator() {
        return new BrailleValidator();
    }

    /* ---------------- Normalization ---------------- */

    @Bean
    public WhitespaceNormalizer whitespaceNormalizer() {
        return new WhitespaceNormalizer();
    }

    @Bean
    public LineBreakNormalizer lineBreakNormalizer() {
        return new LineBreakNormalizer();
    }

    @Bean
    public UnicodeSanitizer unicodeSanitizer() {
        return new UnicodeSanitizer();
    }

    /* ---------------- Post-processing ---------------- */

    @Bean
    public OutputCleaner outputCleaner() {
        return new OutputCleaner();
    }

    /* ---------------- Liblouis ---------------- */

    /* ---------------- Pipeline ---------------- */

    @Bean
    public BrailleTranslationPipeline brailleTranslationPipeline(
            BrailleValidator validator,
            WhitespaceNormalizer whitespaceNormalizer,
            LineBreakNormalizer lineBreakNormalizer,
            UnicodeSanitizer unicodeSanitizer,
            BrailleTranslator translator,
            OutputCleaner outputCleaner
    ) {
        return new BrailleTranslationPipeline(
                validator,
                whitespaceNormalizer,
                lineBreakNormalizer,
                unicodeSanitizer,
                translator,
                outputCleaner
        );
    }
    @Bean
    public PdfGenerator pdfGenerator() {
        return new PdfGenerator();
    }

    @Bean
    public DocxGenerator docxGenerator() {
        return new DocxGenerator();
    }

    @Bean
    public BrfGenerator brfGenerator() {
        return new BrfGenerator();
    }

    @Bean
    public FormatRouter formatRouter(
            PdfGenerator pdfGenerator,
            DocxGenerator docxGenerator,
            BrfGenerator brfGenerator
    ) {
        return new FormatRouter(pdfGenerator, docxGenerator, brfGenerator);
    }

    /**
     * CLI-based Liblouis translator. Requires JVM properties:
     * -DLOUIS_CLI_PATH and -DLOUIS_TABLE.
     */
    @Bean
    public BrailleTranslator brailleTranslator() {
        return new LiblouisCliTranslator(
                System.getProperty("LOUIS_CLI_PATH"),
                System.getProperty("LOUIS_TABLE")
        );
    }

    @Bean
    public LiblouisTableRegistry liblouisTableRegistry() {
        return new LiblouisTableRegistry(
                System.getProperty("LOUIS_CLI_PATH"),
                System.getProperty("LOUIS_TABLE")
        );
    }

}
