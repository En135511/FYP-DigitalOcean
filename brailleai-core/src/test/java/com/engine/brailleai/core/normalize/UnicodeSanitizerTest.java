package com.engine.brailleai.core.normalize;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UnicodeSanitizerTest {

    @Test
    void preservesBrailleSentencePunctuation() {
        UnicodeSanitizer sanitizer = new UnicodeSanitizer();

        String input = "⠓⠊⠲⠀⠼⠁⠃⠉⠲";
        String output = sanitizer.sanitize(input);

        assertEquals(input, output);
    }

    @Test
    void removesControlCharactersOnly() {
        UnicodeSanitizer sanitizer = new UnicodeSanitizer();

        String input = "⠓\u0000⠊\u0007⠲";
        String output = sanitizer.sanitize(input);

        assertEquals("⠓⠊⠲", output);
    }
}
