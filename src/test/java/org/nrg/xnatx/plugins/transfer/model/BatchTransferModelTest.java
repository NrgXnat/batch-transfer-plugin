package org.nrg.xnatx.plugins.transfer.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Phase 0: the new optional fields (anon_script, anon_replace_pipeline, csv_values) serialize in
 * snake_case and round-trip, and old-shape payloads (without them) still deserialize unchanged.
 */
public class BatchTransferModelTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    public void serializesNewFieldsInSnakeCase() throws Exception {
        final TransferRequest req = new TransferRequest("DEST", "E1", TransferMode.REIMPORT);
        req.setCsvValues(Collections.singletonMap("destination_patient_id", "ANON-042"));
        final BatchTransfer batch = new BatchTransfer(Collections.singletonList(req), "t1");
        batch.setAnonScript("version \"6.1\"");
        batch.setAnonReplacePipeline(true);

        final String json = mapper.writeValueAsString(batch);
        assertTrue(json.contains("\"anon_script\""));
        assertTrue(json.contains("\"anon_replace_pipeline\""));
        assertTrue(json.contains("\"csv_values\""));
    }

    @Test
    public void deserializesNewFields() throws Exception {
        final String json = "{\"tracking_id\":\"t1\",\"anon_script\":\"v6\",\"anon_replace_pipeline\":true,"
                + "\"requests\":[{\"id\":\"E1\",\"mode\":\"Reimport\",\"destination_project\":\"DEST\","
                + "\"csv_values\":{\"destination_patient_id\":\"ANON-042\"}}]}";
        final BatchTransfer back = mapper.readValue(json, BatchTransfer.class);

        assertEquals("v6", back.getAnonScript());
        assertTrue(back.isAnonReplacePipeline());
        assertEquals(TransferMode.REIMPORT, back.getRequests().get(0).getMode());
        assertEquals("ANON-042", back.getRequests().get(0).getCsvValues().get("destination_patient_id"));
    }

    @Test
    public void oldShapePayloadStillDeserializes() throws Exception {
        final String json = "{\"requests\":[{\"id\":\"E1\",\"mode\":\"Reimport\",\"destination_project\":\"DEST\"}]}";
        final BatchTransfer back = mapper.readValue(json, BatchTransfer.class);

        assertNull(back.getAnonScript());
        assertFalse(back.isAnonReplacePipeline());
        assertEquals(1, back.getRequests().size());
        assertNull(back.getRequests().get(0).getCsvValues());
    }
}
