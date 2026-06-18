package org.nrg.xnatx.plugins.transfer.model;

import com.fasterxml.jackson.annotation.JsonValue;

public enum TransferMode {
    CLONE("Clone", "Cloning", "Cloned"),
    SHARE("Share", "Sharing", "Shared"),
    REIMPORT("Reimport", "Reimporting", "Reimported");

    private final String value;
    private final String action;
    private final String pastAction;

    TransferMode(String value, String action, String pastAction) {
        this.value = value;
        this.action = action;
        this.pastAction = pastAction;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    public String getAction() {
        return action;
    }

    /** Past-tense form ("Cloned"/"Shared"/"Reimported") for labeling completed-action workflows. */
    public String getPastAction() {
        return pastAction;
    }

    public String toString() {
        return getValue();
    }
}
