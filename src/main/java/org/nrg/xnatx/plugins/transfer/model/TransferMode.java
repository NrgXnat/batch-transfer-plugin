package org.nrg.xnatx.plugins.transfer.model;

import com.fasterxml.jackson.annotation.JsonValue;

public enum TransferMode {
    CLONE("Clone", "Cloning"),
    SHARE("Share", "Sharing"),
    REIMPORT("Reimport", "Reimporting");

    private final String value;
    private final String action;

    TransferMode(String value, String action) {
        this.value = value;
        this.action = action;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    public String getAction() {
        return action;
    }

    public String toString() {
        return getValue();
    }
}
