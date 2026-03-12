package com.engine.brailleai.web.controller;

import com.engine.brailleai.api.dto.BrailleRequest;
import com.engine.brailleai.api.dto.BrailleResponse;
import com.engine.brailleai.api.dto.TranslationDirection;
import com.engine.brailleai.api.service.BrailleTranslator;
import com.engine.brailleai.core.normalize.LineBreakNormalizer;
import com.engine.brailleai.core.normalize.UnicodeSanitizer;
import com.engine.brailleai.core.normalize.WhitespaceNormalizer;
import com.engine.brailleai.core.pipeline.BrailleTranslationPipeline;
import com.engine.brailleai.core.postprocess.OutputCleaner;
import com.engine.brailleai.core.validate.BrailleValidator;
import com.engine.brailleai.liblouis.LiblouisTableRegistry;
import com.engine.brailleai.output.brf.BrfGenerator;
import com.engine.brailleai.output.docx.DocxGenerator;
import com.engine.brailleai.output.format.FormatRouter;
import com.engine.brailleai.output.format.OutputFormat;
import com.engine.brailleai.output.pdf.PdfGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrailleControllerTest {

    @TempDir
    Path tempDir;

    @Test
    void translateRejectsMixedInput() throws Exception {
        BrailleController controller = buildController(new StubTranslator());
        BrailleRequest request = new BrailleRequest();
        request.setInput("\u2801a");

        ResponseEntity<BrailleResponse> response = controller.translate(request);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(
                "Input cannot mix Braille and regular text. Use one format at a time.",
                response.getBody().getMessage()
        );
    }

    @Test
    void translateRejectsDirectionMismatch() throws Exception {
        BrailleController controller = buildController(new StubTranslator());
        BrailleRequest request = new BrailleRequest();
        request.setInput("plain text");
        request.setDirection(TranslationDirection.BRAILLE_TO_TEXT);

        ResponseEntity<BrailleResponse> response = controller.translate(request);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(
                "Braille to Text mode requires Braille Unicode input only.",
                response.getBody().getMessage()
        );
    }

    @Test
    void translateAutoDetectsTextAsTextToBraille() throws Exception {
        BrailleController controller = buildController(new StubTranslator());
        BrailleRequest request = new BrailleRequest();
        request.setInput("hello");

        ResponseEntity<BrailleResponse> response = controller.translate(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("success", response.getBody().getStatus());
        assertEquals("\u2801\u2803", response.getBody().getTranslatedText());
        assertEquals(TranslationDirection.TEXT_TO_BRAILLE, response.getBody().getDirection());
        assertEquals("text", response.getBody().getDetectedInputType());
    }

    @Test
    void downloadRejectsMixedInput() throws Exception {
        BrailleController controller = buildController(new StubTranslator());
        BrailleRequest request = new BrailleRequest();
        request.setInput("\u2801b");
        request.setOutputFormat(OutputFormat.PDF);

        ResponseEntity<byte[]> response = controller.download(request);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(0, response.getBody().length);
    }

    @Test
    void downloadBrfFromTextReturnsAsciiAttachment() throws Exception {
        BrailleController controller = buildController(new StubTranslator());
        BrailleRequest request = new BrailleRequest();
        request.setInput("question");
        request.setOutputFormat(OutputFormat.BRF);

        ResponseEntity<byte[]> response = controller.download(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(MediaType.TEXT_PLAIN, response.getHeaders().getContentType());
        assertTrue(
                response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)
                        .contains("braille-translation.brf")
        );
        assertEquals("AB", new String(response.getBody(), StandardCharsets.US_ASCII));
    }

    private BrailleController buildController(BrailleTranslator translator) throws Exception {
        BrailleTranslationPipeline pipeline = new BrailleTranslationPipeline(
                new BrailleValidator(),
                new WhitespaceNormalizer(),
                new LineBreakNormalizer(),
                new UnicodeSanitizer(),
                translator,
                new OutputCleaner()
        );
        FormatRouter router = new FormatRouter(
                new PdfGenerator(),
                new DocxGenerator(),
                new BrfGenerator()
        );
        Path tablePath = Files.writeString(tempDir.resolve("en-us-g2.ctb"), "stub");
        LiblouisTableRegistry registry = new LiblouisTableRegistry("lou_translate", tablePath.toString());

        return new BrailleController(pipeline, translator, router, registry);
    }

    private static class StubTranslator implements BrailleTranslator {
        @Override
        public String translate(String brailleUnicode) {
            return "decoded";
        }

        @Override
        public String translateTextToBraille(String plainText) {
            return "\u2801\u2803";
        }
    }
}
