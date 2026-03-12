package com.engine.brailleai.output.brf;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Generates BRF (Braille ASCII) bytes from Unicode Braille text.
 *
 * <p>Only 6-dot Braille patterns are supported in standard BRF. Any 7/8-dot
 * pattern is replaced with '?' to keep output embosser-safe.
 */
public class BrfGenerator {

    private static final Map<Integer, Character> DOTS_TO_BRF = buildDotsToBrfMap();

    public byte[] generate(String text) {
        if (text == null || text.isEmpty()) {
            return new byte[0];
        }

        StringBuilder brf = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);

            if (ch == '\n' || ch == '\r') {
                brf.append(ch);
                continue;
            }
            if (ch == '\t') {
                brf.append(' ');
                continue;
            }

            if (ch >= '\u2800' && ch <= '\u28FF') {
                int dotMask = ch - '\u2800';
                if ((dotMask & 0b1100_0000) != 0) {
                    brf.append('?');
                    continue;
                }
                char mapped = DOTS_TO_BRF.getOrDefault(dotMask & 0b0011_1111, '?');
                brf.append(mapped);
                continue;
            }

            brf.append(ch <= 0x7F ? ch : '?');
        }

        return brf.toString().getBytes(StandardCharsets.US_ASCII);
    }

    private static Map<Integer, Character> buildDotsToBrfMap() {
        Map<Integer, Character> map = new HashMap<>();

        // Mapping based on liblouis en-us-brf.dis (North American Braille ASCII).
        put(map, "0", ' ');
        put(map, "1", 'A');
        put(map, "12", 'B');
        put(map, "14", 'C');
        put(map, "145", 'D');
        put(map, "15", 'E');
        put(map, "124", 'F');
        put(map, "1245", 'G');
        put(map, "125", 'H');
        put(map, "24", 'I');
        put(map, "245", 'J');
        put(map, "13", 'K');
        put(map, "123", 'L');
        put(map, "134", 'M');
        put(map, "1345", 'N');
        put(map, "135", 'O');
        put(map, "1234", 'P');
        put(map, "12345", 'Q');
        put(map, "1235", 'R');
        put(map, "234", 'S');
        put(map, "2345", 'T');
        put(map, "136", 'U');
        put(map, "1236", 'V');
        put(map, "2456", 'W');
        put(map, "1346", 'X');
        put(map, "13456", 'Y');
        put(map, "1356", 'Z');
        put(map, "356", '0');
        put(map, "2", '1');
        put(map, "23", '2');
        put(map, "25", '3');
        put(map, "256", '4');
        put(map, "26", '5');
        put(map, "235", '6');
        put(map, "2356", '7');
        put(map, "236", '8');
        put(map, "35", '9');
        put(map, "3", '\'');
        put(map, "4", '@');
        put(map, "5", '"');
        put(map, "6", ',');
        put(map, "16", '*');
        put(map, "34", '/');
        put(map, "36", '-');
        put(map, "45", '^');
        put(map, "46", '.');
        put(map, "56", ';');
        put(map, "126", '<');
        put(map, "146", '%');
        put(map, "156", ':');
        put(map, "246", '[');
        put(map, "345", '>');
        put(map, "346", '+');
        put(map, "456", '_');
        put(map, "1246", '$');
        put(map, "1256", '\\');
        put(map, "1456", '?');
        put(map, "2346", '!');
        put(map, "3456", '#');
        put(map, "12346", '&');
        put(map, "12356", '(');
        put(map, "12456", ']');
        put(map, "23456", ')');
        put(map, "123456", '=');

        return map;
    }

    private static void put(Map<Integer, Character> map, String dots, char output) {
        int dotMask = dotsToMask(dots);
        map.put(dotMask, output);
    }

    private static int dotsToMask(String dots) {
        if ("0".equals(dots)) {
            return 0;
        }
        int mask = 0;
        for (int i = 0; i < dots.length(); i++) {
            int dot = dots.charAt(i) - '0';
            if (dot < 1 || dot > 8) {
                continue;
            }
            mask |= 1 << (dot - 1);
        }
        return mask;
    }
}
