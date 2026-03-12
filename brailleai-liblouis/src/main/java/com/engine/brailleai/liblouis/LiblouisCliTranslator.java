package com.engine.brailleai.liblouis;

import com.engine.brailleai.api.service.BrailleTranslator;
import com.engine.brailleai.liblouis.exception.LiblouisTranslationException;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * CLI-based Liblouis translator that back-translates Unicode Braille to text.
 */
public class LiblouisCliTranslator implements BrailleTranslator {

    private final String louTranslatePath;
    private final String tablePath;

    public LiblouisCliTranslator(String louTranslatePath, String tablePath) {
        this.louTranslatePath = louTranslatePath;
        this.tablePath = tablePath;
    }

    /**
     * Back-translates Unicode Braille to text using Liblouis CLI.
     */
    @Override
    public String translate(String brailleUnicode) {
        return runCliTranslation(brailleUnicode, true);
    }

    @Override
    public String translateTextToBraille(String plainText) {
        return runCliTranslation(normalizeTextForForwardTranslation(plainText), false);
    }

    private String runCliTranslation(String input, boolean backward) {
        if (input == null || input.isBlank()) {
            return "";
        }

        try {
            if (louTranslatePath == null || louTranslatePath.isBlank()) {
                throw new LiblouisTranslationException("LOUIS_CLI_PATH is not set");
            }
            if (tablePath == null || tablePath.isBlank()) {
                throw new LiblouisTranslationException("LOUIS_TABLE is not set");
            }

            File cli = new File(louTranslatePath);
            File table = new File(tablePath);
            File displayTable = resolveDisplayTable(table);

            if (!cli.isFile()) {
                throw new LiblouisTranslationException(
                        "Liblouis CLI not found: " + cli.getAbsolutePath()
                );
            }
            if (!table.isFile()) {
                throw new LiblouisTranslationException(
                        "Liblouis table not found: " + table.getAbsolutePath()
                );
            }
            if (!displayTable.isFile()) {
                throw new LiblouisTranslationException(
                        "Liblouis display table not found: " + displayTable.getAbsolutePath()
                );
            }

            String tableDir = table.getParentFile() == null ? "" : table.getParentFile().getAbsolutePath();
            List<List<String>> commands = buildCandidateCommands(
                    cli.getAbsolutePath(),
                    table.getAbsolutePath(),
                    displayTable.getAbsolutePath(),
                    backward
            );

            CliRunResult successfulRun = null;
            List<String> failureDetails = new ArrayList<>();

            for (List<String> command : commands) {
                try {
                    CliRunResult result = executeCommand(command, input, tableDir);
                    if (result.exitCode == 0) {
                        successfulRun = result;
                        break;
                    }
                    failureDetails.add(formatFailure(result.commandDisplay, result.exitCode, result.stderr));
                } catch (IOException | InterruptedException commandException) {
                    if (commandException instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                    String commandDisplay = String.join(" ", command);
                    failureDetails.add(formatFailure(commandDisplay, -1, commandException.getMessage()));
                }
            }

            if (successfulRun == null) {
                throw new LiblouisTranslationException(
                        "Liblouis CLI failed. Attempts: " + String.join(" || ", failureDetails)
                );
            }

            String output = successfulRun.stdout;
            String errorText = successfulRun.stderr;
            if (errorText != null && !errorText.isBlank()) {
                System.err.println("Liblouis CLI stderr: " + errorText);
            }

            // Liblouis back-translation emits a leading space; remove exactly one.
            if (backward && output.startsWith(" ")) {
                output = output.substring(1);
            }
            if (!backward) {
                output = normalizeBrailleOutput(output);
            }

            return output;

        } catch (Exception e) {
            throw new LiblouisTranslationException("Liblouis CLI translation failed", e);
        }
    }

    private File resolveDisplayTable(File table) {
        if (table == null || table.getParentFile() == null) {
            return new File("unicode.dis");
        }
        return new File(table.getParentFile(), "unicode.dis");
    }

    private List<List<String>> buildCandidateCommands(
            String cliPath,
            String tablePath,
            String displayTablePath,
            boolean backward
    ) {
        List<List<String>> commands = new ArrayList<>();

        // Preferred modern long-option command.
        List<String> primary = new ArrayList<>();
        primary.add(cliPath);
        if (backward) {
            primary.add("--backward");
        }
        primary.add("--display-table");
        primary.add(displayTablePath);
        primary.add(tablePath);
        commands.add(primary);

        // Compatibility with older CLI option style.
        List<String> shortDisplay = new ArrayList<>();
        shortDisplay.add(cliPath);
        if (backward) {
            shortDisplay.add("-b");
        }
        shortDisplay.add("-d");
        shortDisplay.add(displayTablePath);
        shortDisplay.add(tablePath);
        commands.add(shortDisplay);

        // Final fallback: no explicit display table.
        List<String> fallback = new ArrayList<>();
        fallback.add(cliPath);
        if (backward) {
            fallback.add("-b");
        }
        fallback.add(tablePath);
        commands.add(fallback);

        return commands;
    }

    private CliRunResult executeCommand(List<String> command, String input, String tableDir)
            throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);

        // Ensure Liblouis can resolve table includes from the table directory.
        Map<String, String> env = pb.environment();
        String existing = env.get("LOUIS_TABLEPATH");
        if (!tableDir.isBlank()) {
            if (existing == null || existing.isBlank()) {
                env.put("LOUIS_TABLEPATH", tableDir);
            } else if (!existing.contains(tableDir)) {
                env.put("LOUIS_TABLEPATH", tableDir + File.pathSeparator + existing);
            }
        }

        Process process = pb.start();

        StreamCollector stdout = new StreamCollector(process.getInputStream());
        StreamCollector stderr = new StreamCollector(process.getErrorStream());

        Thread outThread = new Thread(stdout, "liblouis-stdout");
        Thread errThread = new Thread(stderr, "liblouis-stderr");
        outThread.start();
        errThread.start();

        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8))) {
            writer.write(input);
        }

        int exitCode = process.waitFor();
        outThread.join();
        errThread.join();

        return new CliRunResult(
                exitCode,
                stdout.getText(),
                stderr.getText(),
                String.join(" ", command)
        );
    }

    private String formatFailure(String commandDisplay, int exitCode, String stderr) {
        String detail = stderr == null ? "" : stderr.trim().replaceAll("\\s+", " ");
        if (detail.length() > 240) {
            detail = detail.substring(0, 240) + "...";
        }
        return "cmd=[" + commandDisplay + "] exit=" + exitCode + " stderr=" + detail;
    }

    private String normalizeBrailleOutput(String output) {
        if (output == null || output.isBlank()) {
            return output;
        }
        if (containsUnicodeBraille(output)) {
            return output;
        }
        return convertBrailleAsciiToUnicode(output);
    }

    private boolean containsUnicodeBraille(String value) {
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch >= '\u2800' && ch <= '\u28FF') {
                return true;
            }
        }
        return false;
    }

    private String convertBrailleAsciiToUnicode(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            out.append(mapBrailleAsciiChar(value.charAt(i)));
        }
        return out.toString();
    }

    private char mapBrailleAsciiChar(char ch) {
        if (ch == ' ' || ch == '\n' || ch == '\r' || ch == '\t') {
            return ch;
        }

        char key = Character.isLetter(ch) ? Character.toUpperCase(ch) : ch;
        return switch (key) {
            case '!' -> '\u282e';
            case '"' -> '\u2810';
            case '#' -> '\u283c';
            case '$' -> '\u282b';
            case '%' -> '\u2829';
            case '&' -> '\u282f';
            case '\'' -> '\u2804';
            case '(' -> '\u2837';
            case ')' -> '\u283e';
            case '*' -> '\u2821';
            case '+' -> '\u282c';
            case ',' -> '\u2820';
            case '-' -> '\u2824';
            case '.' -> '\u2828';
            case '/' -> '\u280c';
            case '0' -> '\u2834';
            case '1' -> '\u2802';
            case '2' -> '\u2806';
            case '3' -> '\u2812';
            case '4' -> '\u2832';
            case '5' -> '\u2822';
            case '6' -> '\u2816';
            case '7' -> '\u2836';
            case '8' -> '\u2826';
            case '9' -> '\u2814';
            case ':' -> '\u2831';
            case ';' -> '\u2830';
            case '<' -> '\u2823';
            case '=' -> '\u283f';
            case '>' -> '\u281c';
            case '?' -> '\u2839';
            case '@' -> '\u2808';
            case 'A' -> '\u2801';
            case 'B' -> '\u2803';
            case 'C' -> '\u2809';
            case 'D' -> '\u2819';
            case 'E' -> '\u2811';
            case 'F' -> '\u280b';
            case 'G' -> '\u281b';
            case 'H' -> '\u2813';
            case 'I' -> '\u280a';
            case 'J' -> '\u281a';
            case 'K' -> '\u2805';
            case 'L' -> '\u2807';
            case 'M' -> '\u280d';
            case 'N' -> '\u281d';
            case 'O' -> '\u2815';
            case 'P' -> '\u280f';
            case 'Q' -> '\u281f';
            case 'R' -> '\u2817';
            case 'S' -> '\u280e';
            case 'T' -> '\u281e';
            case 'U' -> '\u2825';
            case 'V' -> '\u2827';
            case 'W' -> '\u283a';
            case 'X' -> '\u282d';
            case 'Y' -> '\u283d';
            case 'Z' -> '\u2835';
            case '[' -> '\u282a';
            case '\\' -> '\u2833';
            case ']' -> '\u283b';
            case '^' -> '\u2818';
            case '_' -> '\u2838';
            default -> ch;
        };
    }

    /**
     * Normalizes text punctuation so Liblouis can translate punctuation
     * consistently even when users paste rich-text characters.
     */
    private String normalizeTextForForwardTranslation(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }

        StringBuilder normalized = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            char ch = input.charAt(i);
            switch (ch) {
                // Apostrophes / single quotes
                case '\u2018': // left single quote
                case '\u2019': // right single quote
                case '\u201B': // single high-reversed-9 quote
                case '\u2032': // prime
                    normalized.append('\'');
                    break;

                // Double quotes
                case '\u201C': // left double quote
                case '\u201D': // right double quote
                case '\u2033': // double prime
                    normalized.append('"');
                    break;

                // Dashes and ellipsis
                case '\u2013': // en dash
                case '\u2014': // em dash
                    normalized.append('-');
                    break;
                case '\u2026': // ellipsis
                    normalized.append("...");
                    break;

                // Period-like punctuation
                case '\u2024': // one dot leader
                case '\u3002': // ideographic full stop
                case '\uFF0E': // fullwidth full stop
                    normalized.append('.');
                    break;

                // Common fullwidth punctuation variants
                case '\uFF0C':
                    normalized.append(',');
                    break;
                case '\uFF1B':
                    normalized.append(';');
                    break;
                case '\uFF1A':
                    normalized.append(':');
                    break;
                case '\uFF01':
                    normalized.append('!');
                    break;
                case '\uFF1F':
                    normalized.append('?');
                    break;

                // Non-breaking / narrow spaces -> plain space
                case '\u00A0':
                case '\u2007':
                case '\u202F':
                    normalized.append(' ');
                    break;

                default:
                    normalized.append(ch);
                    break;
            }
        }

        return normalized.toString();
    }

    private static class StreamCollector implements Runnable {
        private final InputStream stream;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        StreamCollector(InputStream stream) {
            this.stream = stream;
        }

        @Override
        public void run() {
            try (InputStream in = stream) {
                in.transferTo(buffer);
            } catch (IOException ignored) {
            }
        }

        String getText() {
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static class CliRunResult {
        private final int exitCode;
        private final String stdout;
        private final String stderr;
        private final String commandDisplay;

        CliRunResult(int exitCode, String stdout, String stderr, String commandDisplay) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
            this.commandDisplay = commandDisplay;
        }
    }

}
