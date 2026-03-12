package com.engine.brailleai.liblouis;

import com.engine.brailleai.api.exception.InvalidBrailleInputException;
import com.engine.brailleai.api.service.BrailleTranslator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LiblouisTableRegistryTest {

    @TempDir
    Path tempDir;

    @Test
    void listsTablesSortedAndIncludesDefault() throws Exception {
        Path defaultTable = Files.writeString(tempDir.resolve("en-us-g2.ctb"), "stub");
        Files.writeString(tempDir.resolve("fr-bfu-g2.ctb"), "stub");
        Files.writeString(tempDir.resolve("computer-braille.utb"), "stub");
        Files.writeString(tempDir.resolve("ignore.txt"), "stub");

        LiblouisTableRegistry registry = new LiblouisTableRegistry(
                "C:\\liblouis\\lou_translate.exe",
                defaultTable.toString()
        );

        assertEquals(
                List.of("computer-braille.utb", "en-us-g2.ctb", "fr-bfu-g2.ctb"),
                registry.listTables()
        );
    }

    @Test
    void resolvesKnownTableAndRejectsUnknownOrUnsafeName() throws Exception {
        Path defaultTable = Files.writeString(tempDir.resolve("en-us-g2.ctb"), "stub");
        Files.writeString(tempDir.resolve("luganda-g1.ctb"), "stub");

        LiblouisTableRegistry registry = new LiblouisTableRegistry(
                "C:\\liblouis\\lou_translate.exe",
                defaultTable.toString()
        );

        BrailleTranslator translator = registry.resolveTranslator("luganda-g1.ctb");
        assertNotNull(translator);
        assertNull(registry.resolveTranslator(null));
        assertNull(registry.resolveTranslator("  "));

        assertThrows(
                InvalidBrailleInputException.class,
                () -> registry.resolveTranslator("../etc/passwd")
        );
        assertThrows(
                InvalidBrailleInputException.class,
                () -> registry.resolveTranslator("missing.ctb")
        );
    }

    @Test
    void rejectsMissingRequiredConfiguration() {
        assertThrows(
                IllegalStateException.class,
                () -> new LiblouisTableRegistry(" ", "en-us-g2.ctb")
        );
        assertThrows(
                IllegalStateException.class,
                () -> new LiblouisTableRegistry("lou_translate", " ")
        );
    }
}
