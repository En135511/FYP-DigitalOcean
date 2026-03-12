package com.engine.brailleai.core.postprocess;

/**
 * Cleans Liblouis output while preserving line structure.
 *
 * Responsibilities:
 * - Convert Liblouis numeric markers ('#abc') into Arabic digits
 * - Convert Liblouis capitalization markers (',word' / ',,word')
 * - Apply punctuation heuristics used by the current translation flow
 * - Normalize whitespace without destroying paragraph breaks
 */
public class OutputCleaner {

    public String clean(String translatedText) {
        if (translatedText == null) {
            return null;
        }

        StringBuilder out = new StringBuilder(translatedText.length());
        boolean numericMode = false;
        boolean numericHasDigits = false;
        boolean capitalizeNext = false;
        boolean capitalizeWord = false;

        for (int i = 0; i < translatedText.length(); i++) {
            char ch = translatedText.charAt(i);

            // Numeric marker starts number mode.
            if (ch == '#') {
                numericMode = true;
                numericHasDigits = false;
                continue;
            }

            if (numericMode) {
                Character digit = mapLetterToDigit(ch);
                if (digit != null) {
                    out.append(digit);
                    numericHasDigits = true;
                    continue;
                }
                if (numericHasDigits && isNumericSeparator(ch)) {
                    // Apostrophe often appears in noisy numeric OCR where comma is expected.
                    out.append(ch == '\'' ? ',' : ch);
                    continue;
                }
                numericMode = false;
                numericHasDigits = false;
            }

            // Capitalization markers.
            if (ch == ',') {
                char prev = i > 0 ? translatedText.charAt(i - 1) : '\0';
                char next = i + 1 < translatedText.length() ? translatedText.charAt(i + 1) : '\0';

                // ",,word" -> capitalize whole word.
                if (next == ',') {
                    char after = i + 2 < translatedText.length() ? translatedText.charAt(i + 2) : '\0';
                    if (Character.isLetter(after) && isCapitalMarkerContext(prev)) {
                        capitalizeWord = true;
                        i++; // consume second comma
                        continue;
                    }
                }

                // ",word" -> capitalize next letter.
                if (Character.isLetter(next) && isCapitalMarkerContext(prev)) {
                    capitalizeNext = true;
                    continue;
                }
            }

            if (capitalizeWord) {
                if (Character.isLetter(ch)) {
                    out.append(Character.toUpperCase(ch));
                    continue;
                }
                capitalizeWord = false;
            }

            if (capitalizeNext) {
                if (Character.isLetter(ch)) {
                    out.append(Character.toUpperCase(ch));
                    capitalizeNext = false;
                    continue;
                }
                capitalizeNext = false;
            }

            out.append(ch);
        }

        return normalizeWhitespaceAndFixPunctuationHeuristics(out.toString());
    }

    private Character mapLetterToDigit(char ch) {
        return switch (Character.toLowerCase(ch)) {
            case 'a' -> '1';
            case 'b' -> '2';
            case 'c' -> '3';
            case 'd' -> '4';
            case 'e' -> '5';
            case 'f' -> '6';
            case 'g' -> '7';
            case 'h' -> '8';
            case 'i' -> '9';
            case 'j' -> '0';
            default -> null;
        };
    }

    private boolean isCapitalMarkerContext(char previous) {
        return previous == '\0' || !Character.isLetterOrDigit(previous);
    }

    private boolean isNumericSeparator(char ch) {
        return ch == ',' || ch == '\'' || ch == '.';
    }

    private String normalizeWhitespaceAndFixPunctuationHeuristics(String text) {
        String cleaned = text
                .replace("\r\n", "\n")
                .replace("\r", "\n");

        // Heuristic punctuation fixes retained from existing behavior.
        cleaned = cleaned.replaceAll("(?<=[a-zA-Z])1 (?=[a-zA-Z])", ", ");
        cleaned = cleaned.replaceAll("(?<=[a-zA-Z])2 (?=[a-zA-Z])", "; ");
        cleaned = cleaned.replaceAll("(?<=[a-zA-Z])8", "?");
        // Numbered lists frequently arrive as "1'" / "2:" / "3;" etc.
        cleaned = cleaned.replaceAll("(?m)^(\\s*\\d+)[,':;][ \\t]+(?=\\S)", "$1. ");
        cleaned = cleaned.replaceAll("(?m)^(\\s*\\d+)[,']\\s*$", "$1.");
        cleaned = enforcePunctuationSpacing(cleaned);

        // Preserve paragraph breaks while removing noisy spacing.
        cleaned = cleaned.replaceAll("\n{3,}", "\n\n");
        cleaned = cleaned.replaceAll("[ \\t]+(?=\\n)", "");
        cleaned = cleaned.replaceAll(" {2,}", " ");

        // Do not trim; callers may rely on terminal newlines.
        return cleaned;
    }

    private String enforcePunctuationSpacing(String text) {
        String cleaned = text;

        // Remove spacing before terminal punctuation.
        cleaned = cleaned.replaceAll("[ \\t]+([,.;:!?])", "$1");

        // Remove noisy spacing inside brackets.
        cleaned = cleaned.replaceAll("([\\(\\[\\{])[ \\t]+", "$1");
        cleaned = cleaned.replaceAll("[ \\t]+([\\)\\]\\}])", "$1");

        // Ensure one separator space after sentence punctuation when followed by text.
        cleaned = cleaned.replaceAll("([!?;:])(?=[^\\s\\n\\r\\)\\]\\}\"'])", "$1 ");

        // Add missing spaces after comma and period before words (while preserving decimals/ellipsis).
        cleaned = cleaned.replaceAll("(,)(?=[\\p{L}\\(\\[\\{\\\"])", "$1 ");
        cleaned = cleaned.replaceAll("(?<!\\.)(\\.)(?=[\\p{L}\\(\\[\\{\\\"])", "$1 ");

        return cleaned;
    }
}
