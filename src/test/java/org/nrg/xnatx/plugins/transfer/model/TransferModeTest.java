package org.nrg.xnatx.plugins.transfer.model;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Verifies the label forms used to build workflow messages. {@code getPastAction()} backs the unified
 * source-item workflow labels ("Cloned/Reimported &lt;label&gt; to project &lt;dest&gt;").
 */
public class TransferModeTest {

    @Test
    public void getPastAction_returnsPastTenseLabels() {
        assertEquals("Cloned", TransferMode.CLONE.getPastAction());
        assertEquals("Shared", TransferMode.SHARE.getPastAction());
        assertEquals("Reimported", TransferMode.REIMPORT.getPastAction());
    }

    @Test
    public void getActionAndValue_unchanged() {
        assertEquals("Cloning", TransferMode.CLONE.getAction());
        assertEquals("Clone", TransferMode.CLONE.getValue());
        assertEquals("Reimporting", TransferMode.REIMPORT.getAction());
        assertEquals("Reimport", TransferMode.REIMPORT.getValue());
    }
}
