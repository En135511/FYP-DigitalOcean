package com.engine.brailleai.api.dto;


import com.engine.brailleai.output.format.OutputFormat;

/**
 * Request payload for Braille translation and download endpoints.
 */
public class BrailleRequest {

    private String input;
    private String brailleUnicode;
    private OutputFormat outputFormat;
    private String table;
    private TranslationDirection direction;

    public String getInput() {
        return input;
    }

    public void setInput(String input) {
        this.input = input;
    }

    public String getBrailleUnicode() {
        return brailleUnicode;
    }

    public void setBrailleUnicode(String brailleUnicode) {
        this.brailleUnicode = brailleUnicode;
    }

    public OutputFormat getOutputFormat() {
        return outputFormat;
    }

    public void setOutputFormat(OutputFormat outputFormat) {
        this.outputFormat = outputFormat;
    }

    public String getTable() {
        return table;
    }

    public void setTable(String table) {
        this.table = table;
    }

    public TranslationDirection getDirection() {
        return direction;
    }

    public void setDirection(TranslationDirection direction) {
        this.direction = direction;
    }

    public String getResolvedInput() {
        if (input != null && !input.isBlank()) {
            return input;
        }
        return brailleUnicode;
    }
}
