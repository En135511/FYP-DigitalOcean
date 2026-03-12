package com.engine.brailleai.core.normalize;

/**
 * Removes unsupported control characters from Braille input while preserving
 * Braille symbols and user punctuation exactly as entered.
 */
public class UnicodeSanitizer {

    public String sanitize(String input) {
        if (input == null) {
            return null;
        }

        StringBuilder sanitized = new StringBuilder(input.length());

        for (int i = 0; i < input.length(); i++) {
            char ch = input.charAt(i);

            // Keep common layout whitespace.
            if (ch == '\n' || ch == '\t' || ch == ' ') {
                sanitized.append(ch);
                continue;
            }

            // Drop control characters only.
            if (Character.isISOControl(ch)) {
                continue;
            }

            // Preserve all printable characters, including Braille punctuation.
            sanitized.append(ch);
        }

        return sanitized.toString();
    }
}
