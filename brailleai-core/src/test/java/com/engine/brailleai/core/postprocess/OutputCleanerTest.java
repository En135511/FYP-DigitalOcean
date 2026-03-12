package com.engine.brailleai.core.postprocess;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OutputCleanerTest {

    @Test
    void cleansLiblouisCapitalMarkersInParagraphs() {
        OutputCleaner cleaner = new OutputCleaner();

        String input = ",ffenna tulina ebibuuzo bye twebuuza ku bulamu, ku bonobaabona, ku kufa, "
                + "ne ku biseera eby'omu maaso. ,ate era waliwo ebintu bye tulowoozaako ennyo, "
                + "gamba ng'engeri y'okwebeezaawo, oba okuba n'amaka amasanyufu. ,abantu bangi "
                + "bakirabye nti ng'oggyeeko okuba nti ,bayibuli eddamu ebibuuzo ebikulu bye "
                + "beebuuza, era erimu amagezi agabayamba mu bulamu bwabwe. ,olowooza baani "
                + "abasobola okuganyulwa mu ebyo ebiri mu ,bayibuli?";

        String expected = "Ffenna tulina ebibuuzo bye twebuuza ku bulamu, ku bonobaabona, ku kufa, "
                + "ne ku biseera eby'omu maaso. Ate era waliwo ebintu bye tulowoozaako ennyo, "
                + "gamba ng'engeri y'okwebeezaawo, oba okuba n'amaka amasanyufu. Abantu bangi "
                + "bakirabye nti ng'oggyeeko okuba nti Bayibuli eddamu ebibuuzo ebikulu bye "
                + "beebuuza, era erimu amagezi agabayamba mu bulamu bwabwe. Olowooza baani "
                + "abasobola okuganyulwa mu ebyo ebiri mu Bayibuli?";

        assertEquals(expected, cleaner.clean(input));
    }

    @Test
    void enforcesPunctuationSpacingAndBracketCleanup() {
        OutputCleaner cleaner = new OutputCleaner();

        String input = "hello,world!this is great ;right?yes:( maybe ) [ test ] { ok }";
        String expected = "hello, world! this is great; right? yes: (maybe) [test] {ok}";

        assertEquals(expected, cleaner.clean(input));
    }

    @Test
    void preservesDecimalsAndEllipsisWhileFixingSentenceSpacing() {
        OutputCleaner cleaner = new OutputCleaner();

        String input = "value is 3 .14,pi...wait.Here";
        String expected = "value is 3.14, pi...wait. Here";

        assertEquals(expected, cleaner.clean(input));
    }

    @Test
    void keepsLegacyBraillePunctuationHeuristicsAndNormalizesSpacing() {
        OutputCleaner cleaner = new OutputCleaner();

        String input = "hello1 world2 yes8now";
        String expected = "hello, world; yes? now";

        assertEquals(expected, cleaner.clean(input));
    }

    @Test
    void normalizesNumberedListLineMarkersToPeriods() {
        OutputCleaner cleaner = new OutputCleaner();

        String input = "1' 114en\n2: 162:\n3; 42:\n4. 11: but";
        String expected = "1. 114en\n2. 162:\n3. 42:\n4. 11: but";

        assertEquals(expected, cleaner.clean(input));
    }

    @Test
    void normalizesStandaloneNumberedLineApostropheOrCommaToPeriod() {
        OutputCleaner cleaner = new OutputCleaner();

        String input = "1,\n2'\n3.";
        String expected = "1.\n2.\n3.";

        assertEquals(expected, cleaner.clean(input));
    }

    @Test
    void keepsNumberModeAcrossApostropheCommaAndPeriodSeparators() {
        OutputCleaner cleaner = new OutputCleaner();

        String input = "#b'jjj #c,jjj #d.ej";
        String expected = "2,000 3,000 4.50";

        assertEquals(expected, cleaner.clean(input));
    }

    @Test
    void doesNotTreatCommaInsideNumbersAsCapitalizationMarker() {
        OutputCleaner cleaner = new OutputCleaner();

        String input = "#c,jjj #d,JJJ";
        String expected = "3,000 4,000";

        assertEquals(expected, cleaner.clean(input));
    }
}
