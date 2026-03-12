package com.engine.brailleai.output.docx;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;

import java.io.ByteArrayOutputStream;

/**
 * Generates a DOCX document from translated text.
 */
public class DocxGenerator {

    /**
     * Builds a DOCX document, mapping each line to its own paragraph.
     */
    public byte[] generate(String text) {
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {

            // Preserve line breaks by creating one paragraph per line.
            for (String line : text.split("\n", -1)) {
                XWPFParagraph paragraph = document.createParagraph();
                paragraph.createRun().setText(line);
            }

            document.write(outputStream);
            return outputStream.toByteArray();

        } catch (Exception ex) {
            throw new RuntimeException("Failed to generate DOCX output", ex);
        }
    }
}
