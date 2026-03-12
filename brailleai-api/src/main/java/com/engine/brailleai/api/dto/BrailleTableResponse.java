package com.engine.brailleai.api.dto;

import java.util.List;

/**
 * Response payload listing available Liblouis tables.
 */
public class BrailleTableResponse {

    private String defaultTable;
    private List<String> tables;

    public BrailleTableResponse(String defaultTable, List<String> tables) {
        this.defaultTable = defaultTable;
        this.tables = tables;
    }

    public String getDefaultTable() {
        return defaultTable;
    }

    public void setDefaultTable(String defaultTable) {
        this.defaultTable = defaultTable;
    }

    public List<String> getTables() {
        return tables;
    }

    public void setTables(List<String> tables) {
        this.tables = tables;
    }
}
