package com.engine.brailleai.output.format;

import com.engine.brailleai.output.brf.BrfGenerator;
import com.engine.brailleai.output.docx.DocxGenerator;
import com.engine.brailleai.output.pdf.PdfGenerator;

/**
 * Routes translated text to the correct output generator.
 */
public class FormatRouter {

    private final PdfGenerator pdfGenerator;
    private final DocxGenerator docxGenerator;
    private final BrfGenerator brfGenerator;

    public FormatRouter(
            PdfGenerator pdfGenerator,
            DocxGenerator docxGenerator,
            BrfGenerator brfGenerator
    ) {
        this.pdfGenerator = pdfGenerator;
        this.docxGenerator = docxGenerator;
        this.brfGenerator = brfGenerator;
    }

    public byte[] generate(String text, OutputFormat format) {
        return switch (format) {
            case PDF -> pdfGenerator.generate(text);
            case DOCX -> docxGenerator.generate(text);
            case BRF -> brfGenerator.generate(text);
        };
    }
}
