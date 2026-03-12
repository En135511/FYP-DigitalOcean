package com.engine.brailleai.liblouis;

import com.engine.brailleai.api.exception.InvalidBrailleInputException;
import com.engine.brailleai.api.service.BrailleTranslator;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Resolves available Liblouis translation tables from the configured table directory.
 */
public class LiblouisTableRegistry {

    private final String cliPath;
    private final File tableDir;
    private final String defaultTableName;

    public LiblouisTableRegistry(String cliPath, String defaultTablePath) {
        if (cliPath == null || cliPath.isBlank()) {
            throw new IllegalStateException("LOUIS_CLI_PATH is not set");
        }
        if (defaultTablePath == null || defaultTablePath.isBlank()) {
            throw new IllegalStateException("LOUIS_TABLE is not set");
        }

        File defaultTable = new File(defaultTablePath);
        this.tableDir = defaultTable.getParentFile();
        this.defaultTableName = defaultTable.getName();
        this.cliPath = cliPath;
    }

    public String getDefaultTableName() {
        return defaultTableName;
    }

    public List<String> listTables() {
        if (tableDir == null || !tableDir.isDirectory()) {
            return List.of(defaultTableName);
        }

        File[] files = tableDir.listFiles((dir, name) ->
                name.endsWith(".ctb") || name.endsWith(".utb"));

        if (files == null || files.length == 0) {
            return List.of(defaultTableName);
        }

        List<String> tables = Arrays.stream(files)
                .map(File::getName)
                .distinct()
                .sorted()
                .collect(Collectors.toCollection(ArrayList::new));

        if (!tables.contains(defaultTableName)) {
            tables.add(defaultTableName);
            tables.sort(String::compareTo);
        }

        return tables;
    }

    public BrailleTranslator resolveTranslator(String tableName) {
        if (tableName == null || tableName.isBlank()) {
            return null;
        }

        File table = resolveTable(tableName);
        if (table == null) {
            throw new InvalidBrailleInputException("Unknown table: " + tableName);
        }

        return new LiblouisCliTranslator(cliPath, table.getAbsolutePath());
    }

    private File resolveTable(String tableName) {
        if (tableDir == null || !tableDir.isDirectory()) {
            return null;
        }

        String name = tableName.trim();
        if (name.contains("..") || name.contains("/") || name.contains("\\")) {
            return null;
        }

        File candidate = new File(tableDir, name);
        if (!candidate.isFile()) {
            return null;
        }

        try {
            String dirPath = tableDir.getCanonicalPath();
            String filePath = candidate.getCanonicalPath();
            if (!filePath.startsWith(dirPath + File.separator)) {
                return null;
            }
        } catch (IOException ex) {
            return null;
        }

        return candidate;
    }
}
