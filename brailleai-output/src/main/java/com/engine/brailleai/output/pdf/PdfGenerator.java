package com.engine.brailleai.output.pdf;

import com.lowagie.text.Document;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.PdfWriter;

import java.io.ByteArrayOutputStream;

/**
 * Generates a PDF document from translated text.
 */
public class PdfGenerator {

    /**
     * Builds a PDF document, mapping each line to a paragraph.
     */
    public byte[] generate(String text) {
        try {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            Document document = new Document();

            PdfWriter.getInstance(document, outputStream);
            document.open();

            // Preserve line breaks by creating one paragraph per line.
            for (String line : text.split("\n")) {
                document.add(new Paragraph(line));
            }

            document.close();
            return outputStream.toByteArray();

        } catch (Exception ex) {
            throw new RuntimeException("Failed to generate PDF output", ex);
        }
    }
}
