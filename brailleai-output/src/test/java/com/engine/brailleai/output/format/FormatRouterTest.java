package com.engine.brailleai.output.format;

import com.engine.brailleai.output.brf.BrfGenerator;
import com.engine.brailleai.output.docx.DocxGenerator;
import com.engine.brailleai.output.pdf.PdfGenerator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class FormatRouterTest {

    @Test
    void routesGenerationToExpectedGeneratorByFormat() {
        FormatRouter router = new FormatRouter(
                new StubPdfGenerator(),
                new StubDocxGenerator(),
                new StubBrfGenerator()
        );

        assertArrayEquals("pdf".getBytes(), router.generate("input", OutputFormat.PDF));
        assertArrayEquals("docx".getBytes(), router.generate("input", OutputFormat.DOCX));
        assertArrayEquals("brf".getBytes(), router.generate("input", OutputFormat.BRF));
    }

    private static class StubPdfGenerator extends PdfGenerator {
        @Override
        public byte[] generate(String text) {
            return "pdf".getBytes();
        }
    }

    private static class StubDocxGenerator extends DocxGenerator {
        @Override
        public byte[] generate(String text) {
            return "docx".getBytes();
        }
    }

    private static class StubBrfGenerator extends BrfGenerator {
        @Override
        public byte[] generate(String text) {
            return "brf".getBytes();
        }
    }
}
