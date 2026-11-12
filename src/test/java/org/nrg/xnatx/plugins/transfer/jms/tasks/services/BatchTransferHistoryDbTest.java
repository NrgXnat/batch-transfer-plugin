package org.nrg.xnatx.plugins.transfer.jms.tasks.services;

import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;
import org.nrg.xft.security.UserI;
import org.nrg.xnatx.plugins.transfer.DatabaseTest;
import org.nrg.xnatx.plugins.transfer.config.DatabaseTestConfig;
import org.nrg.xnatx.plugins.transfer.jms.tasks.dao.XnatItemQueries;
import org.nrg.xnatx.plugins.transfer.jms.tasks.entities.BatchTransferHistory;
import org.nrg.xnatx.plugins.transfer.jms.tasks.entities.BatchTransferItem;
import org.nrg.xnatx.plugins.transfer.jms.tasks.services.BatchTransferHistoryService.StopResult;
import org.nrg.xnatx.plugins.transfer.jms.tasks.services.BatchTransferItemService.StartResult;
import org.nrg.xnatx.plugins.transfer.jms.tasks.services.BatchTransferProgressService.Completion;
import org.nrg.xnatx.plugins.transfer.jms.tasks.services.BatchTransferProgressService.Outcome;
import org.nrg.xnatx.plugins.transfer.model.BatchTransfer;
import org.nrg.xnatx.plugins.transfer.model.TransferMode;
import org.nrg.xnatx.plugins.transfer.model.TransferRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Transfer history against PostgreSQL: what a submit records, the run and item lifecycles, Stop, and the list
 * queries. Not {@code @Transactional}: each service call commits on its own, as in XNAT. Every test uses its own
 * project and tracking ids, since the tests share one database.
 */
@Category(DatabaseTest.class)
@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(classes = DatabaseTestConfig.class)
public class BatchTransferHistoryDbTest {

    private static final String DESTINATION = "DEST";

    @Autowired private BatchTransferHistoryService  history;
    @Autowired private BatchTransferItemService     items;
    @Autowired private BatchTransferProgressService progress;
    @Autowired private XnatItemQueries              xnatItems;
    @Autowired private JdbcTemplate                 jdbc;

    private String prefix;
    private UserI  alice;

    @Before
    public void setUp() {
        prefix = "T" + UUID.randomUUID().toString().substring(0, 8);
        alice = user(prefix + "_alice", 7);
    }

    // ---- recording a submit -----------------------------------------------------------------------------

    @Test
    public void aSubmitRecordsTheRunAndItsItems() {
        final String project = id("PROJ");
        subject(project, id("S1"), "SUBJ_1");
        session(project, id("S1"), id("E1"), "SESS_1");
        session(project, id("S1"), id("E2"), "SESS_2");
        final Map<String, String> values = new LinkedHashMap<>();
        values.put("pid", "ANON_1");
        values.put("site", "B");
        values.put("arm", "2");
        final TransferRequest first = reimport(id("E1"), 2, values);
        first.setDestinationSubjectLabel("ROUTED_1");
        final TransferRequest second = reimport(id("E2"), 3, null);
        final BatchTransfer batch = new BatchTransfer(new ArrayList<>(Arrays.asList(first, second)), trackingId("submit"));
        batch.setAnonScript("version \"6.1\"\n(0010,4000) := \"${csv.pid}\"");

        history.recordSubmit(batch, alice, null);

        final Map<String, Object> run = jdbc.queryForMap("SELECT * FROM xhbm_batch_transfer_history WHERE tracking_id = ?",
                batch.getTrackingId());
        assertEquals("Queued", run.get("status"));
        assertEquals("Reimport", run.get("operation"));
        assertEquals(project, run.get("source_project"));
        assertEquals(DESTINATION, run.get("destination_project"));
        assertEquals(2, run.get("item_count"));
        assertEquals(batch.getAnonScript(), run.get("anon_custom_script"));
        assertEquals(Boolean.TRUE, run.get("preserve_session_label"));
        assertEquals(alice.getUsername(), run.get("created_by"));
        assertEquals(7, run.get("user_id"));

        final List<Map<String, Object>> rows = itemRows(batch.getTrackingId());
        assertEquals(2, rows.size());
        assertEquals(id("E1"), rows.get(0).get("item_id"));
        assertEquals(2, rows.get(0).get("row_number"));
        assertEquals("SUBJ_1", rows.get(0).get("source_subject_label"));
        assertEquals("SESS_1", rows.get(0).get("source_label"));
        assertEquals("ROUTED_1", rows.get(0).get("destination_subject_label"));
        assertEquals("the values keep the manifest's column order", "{\"pid\":\"ANON_1\",\"site\":\"B\",\"arm\":\"2\"}",
                rows.get(0).get("csv_values"));
        assertEquals("Queued", rows.get(0).get("status"));
        assertEquals("Reimport", rows.get(0).get("mode"));
        assertNull(rows.get(1).get("csv_values"));

        assertThrows("a tracking id with a run can't be reused", BatchTransferRunExistsException.class,
                () -> history.recordSubmit(batch, alice, null));

        final long historyId = ((Number) run.get("id")).longValue();
        final List<BatchTransferItem> listed = history.items(historyId);
        assertEquals(Arrays.asList(id("E1"), id("E2")),
                Arrays.asList(listed.get(0).getItemId(), listed.get(1).getItemId()));
        final Map<String, String> labels = history.itemLabels(Arrays.asList(id("E2"), id("S1"), id("NOPE")));
        assertEquals("SESS_2", labels.get(id("E2")));
        assertEquals("a subject's label too", "SUBJ_1", labels.get(id("S1")));
        assertEquals(2, labels.size());
    }

    @Test
    public void itemsChangedSinceListsOnlyTheItemsChangedSinceThen() {
        final String trackingId = submit("changed", 3);
        history.registerRun(trackingId, 7, 3);
        final Date cutoff = jdbc.queryForObject("SELECT clock_timestamp()", Date.class);
        items.start(trackingId, id("E1"));
        items.finish(trackingId, id("E2"), Outcome.FAILED, "broke", null);
        final long historyId = ((Number) runRow(trackingId).get("id")).longValue();

        final List<BatchTransferItem> changed = history.itemsChangedSince(historyId, cutoff);

        assertEquals("started or finished since the cutoff, in the run's order", Arrays.asList(id("E1"), id("E2")),
                Arrays.asList(changed.get(0).getItemId(), changed.get(1).getItemId()));
        assertEquals(2, changed.size());
        assertEquals("every item has changed since the epoch", 3, history.itemsChangedSince(historyId, new Date(0)).size());
    }

    @Test
    public void itemsAreListedInManifestRowOrderThenByLabel() {
        session(id("P"), null, id("A"), "LABEL_B");
        session(id("P"), null, id("B"), "LABEL_A");
        session(id("P"), null, id("C"), "LABEL_C");
        final String trackingId = trackingId("order");
        history.recordSubmit(new BatchTransfer(new ArrayList<>(Arrays.asList(reimport(id("A"), null, null),
                reimport(id("B"), null, null), reimport(id("C"), 5, null))), trackingId), alice, null);

        final List<BatchTransferItem> listed = history.items(((Number) runRow(trackingId).get("id")).longValue());

        assertEquals("rows with a manifest row number first, then by label", Arrays.asList(id("C"), id("B"), id("A")),
                Arrays.asList(listed.get(0).getItemId(), listed.get(1).getItemId(), listed.get(2).getItemId()));
    }

    @Test
    public void theSourceProjectIsLeftEmptyWhenItemsComeFromSeveralProjects() {
        session(id("P1"), null, id("E1"), "A");
        session(id("P2"), null, id("E2"), "B");
        final String trackingId = trackingId("mixed");
        history.recordSubmit(new BatchTransfer(new ArrayList<>(Arrays.asList(
                reimport(id("E1"), null, null), share(id("E2")), share(id("MISSING")))), trackingId), alice, null);

        final Map<String, Object> run = runRow(trackingId);
        assertNull(run.get("source_project"));
        assertEquals("Mixed", run.get("operation"));
        assertNull("an item XNAT doesn't know keeps no labels", itemRows(trackingId).get(2).get("source_label"));
    }

    @Test
    public void anItemTheUserCannotReadIsRecordedWithoutLabels() {
        final String readable = id("READ");
        final String hidden = id("HIDDEN");
        subject(readable, id("S1"), "SUBJ_1");
        session(readable, id("S1"), id("E1"), "SESS_1");
        subject(hidden, id("S2"), "SECRET_SUBJ");
        session(hidden, id("S2"), id("E2"), "SECRET_SESS");
        final String trackingId = trackingId("unreadable");

        history.recordSubmit(new BatchTransfer(new ArrayList<>(Arrays.asList(reimport(id("E1"), null, null),
                reimport(id("E2"), null, null), share(id("S2")))), trackingId), alice, Collections.singletonList(readable));

        assertEquals("the unreadable items don't count toward it", readable, runRow(trackingId).get("source_project"));
        final List<Map<String, Object>> rows = itemRows(trackingId);
        assertEquals("SESS_1", rows.get(0).get("source_label"));
        for (final Map<String, Object> row : rows.subList(1, 3)) {
            assertEquals(row.get("item_id") + " is still recorded", "Queued", row.get("status"));
            assertNull(row.get("source_label"));
            assertNull(row.get("source_subject_label"));
        }
    }

    @Test
    public void anItemSharedIntoAReadableProjectIsReadable() {
        final String readable = id("READ");
        final String owner = id("OWNER");
        subject(owner, id("S1"), "SUBJ_1");
        shareSubject(id("S1"), readable, "SUBJ_HERE");
        session(owner, id("S1"), id("E1"), "SESS_1");
        shareSession(id("E1"), readable, "SESS_HERE");
        session(owner, id("S1"), id("E2"), "NOT_SHARED");

        final Map<String, XnatItemQueries.SourceItem> found = xnatItems.sourceItems(
                Arrays.asList(id("S1"), id("E1"), id("E2")), null, Collections.singletonList(readable));

        assertEquals(found.keySet().toString(), 2, found.size());
        assertEquals("SUBJ_1", found.get(id("S1")).getLabel());
        assertEquals("SESS_1", found.get(id("E1")).getLabel());
    }

    @Test
    public void aRunIsRecordedUnderTheProjectItWasStartedFrom() {
        final String project = id("HERE");
        final String owner = id("OWNER");
        subject(project, id("S1"), "OWN_SUBJ");
        session(project, id("S1"), id("E1"), "OWN_SESS");
        subject(owner, id("S2"), "PRIMARY_SUBJ");
        shareSubject(id("S2"), project, "HERE_SUBJ");
        session(owner, id("S2"), id("E2"), "SHARED_SESS");
        shareSession(id("E2"), project, "HERE_SESS");
        final String trackingId = trackingId("startedFrom");
        final BatchTransfer batch = new BatchTransfer(new ArrayList<>(Arrays.asList(
                reimport(id("E1"), null, null), reimport(id("E2"), null, null), share(id("S2")))), trackingId);
        batch.setSourceProject(project);

        history.recordSubmit(batch, alice, null);

        assertEquals(project, runRow(trackingId).get("source_project"));
        final List<Map<String, Object>> rows = itemRows(trackingId);
        assertEquals("OWN_SUBJ", rows.get(0).get("source_subject_label"));
        assertEquals("a shared subject's label in the project", "HERE_SUBJ", rows.get(1).get("source_subject_label"));
        assertEquals("a session keeps its own label, which a manifest names", "SHARED_SESS",
                rows.get(1).get("source_label"));
        assertEquals("HERE_SUBJ", rows.get(2).get("source_label"));
    }

    @Test
    public void aRunWithAnItemThatIsNotFoundGoesToTheOwner() {
        final String project = id("HERE");
        final String owner = id("OWNER");
        subject(owner, id("S1"), "PRIMARY_SUBJ");
        shareSubject(id("S1"), project, "HERE_SUBJ");
        session(owner, id("S1"), id("E1"), "SHARED_SESS");
        shareSession(id("E1"), project, "HERE_SESS");
        final String trackingId = trackingId("notFound");
        final BatchTransfer batch = new BatchTransfer(new ArrayList<>(Arrays.asList(
                reimport(id("E1"), null, null), share(id("MISSING")))), trackingId);
        batch.setSourceProject(project);

        history.recordSubmit(batch, alice, Collections.singletonList(owner));

        assertEquals("an ID that wasn't found isn't known to be in the project", owner,
                runRow(trackingId).get("source_project"));
        assertEquals("the owner's label, as the run is the owner's", "PRIMARY_SUBJ",
                itemRows(trackingId).get(0).get("source_subject_label"));
    }

    @Test
    public void aRunWithAnItemFromOutsideTheProjectItWasStartedFromGoesToTheOwner() {
        final String project = id("HERE");
        final String owner = id("OWNER");
        subject(owner, id("S1"), "PRIMARY_SUBJ");
        shareSubject(id("S1"), project, "HERE_SUBJ");
        session(owner, id("S1"), id("E1"), "NOT_SHARED");
        final String trackingId = trackingId("outside");
        final BatchTransfer batch = new BatchTransfer(new ArrayList<>(Collections.singletonList(
                reimport(id("E1"), null, null))), trackingId);
        batch.setSourceProject(project);

        history.recordSubmit(batch, alice, null);

        assertEquals(owner, runRow(trackingId).get("source_project"));
        assertEquals("the owner's label, as the run is the owner's", "PRIMARY_SUBJ",
                itemRows(trackingId).get(0).get("source_subject_label"));
    }

    @Test
    public void theLabelLookupTakesFortyThousandIds() {
        session(id("BIG"), null, id("E1"), "ONE");
        subject(id("BIG"), id("S1"), "SUBJ");
        final List<String> ids = new ArrayList<>();
        for (int i = 0; i < 40_000; i++) {
            ids.add(id("X" + i));
        }
        ids.add(id("E1"));
        ids.add(id("S1"));

        final Map<String, XnatItemQueries.SourceItem> found = xnatItems.sourceItems(ids, null, null);

        assertEquals(2, found.size());
        assertEquals("ONE", found.get(id("E1")).getLabel());
        assertEquals("SUBJ", found.get(id("S1")).getLabel());
    }

    @Test
    public void fiveThousandItemsAreRecordedInOneSubmit() {
        final List<TransferRequest> requests = new ArrayList<>();
        for (int i = 0; i < 5_000; i++) {
            requests.add(reimport(id("E" + i), i + 2, Collections.singletonMap("pid", "P" + i)));
        }
        final String trackingId = trackingId("big");
        final long start = System.nanoTime();
        history.recordSubmit(new BatchTransfer(requests, trackingId), alice, null);
        final long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        System.out.println("Recorded a 5,000-item submit in " + millis + " ms");

        assertEquals(5_000, itemRows(trackingId).size());
    }

    @Test
    public void longValuesRoundTrip() {
        final StringBuilder script = new StringBuilder("version \"6.1\"\n");
        while (script.length() < 256 * 1024) {
            script.append("(0010,4000) := \"a long script line\"\n");
        }
        final Map<String, String> values = new LinkedHashMap<>();
        for (int i = 100; i > 0; i--) {
            values.put("column_" + i, "value " + i);
        }
        final String trackingId = trackingId("long");
        final BatchTransfer batch = new BatchTransfer(new ArrayList<>(Collections.singletonList(
                reimport(id("E1"), null, values))), trackingId);
        batch.setAnonScript(script.toString());
        history.recordSubmit(batch, alice, null);
        history.registerRun(trackingId, 7, 1);
        final StringBuilder message = new StringBuilder();
        while (message.length() < 10_000) {
            message.append("a long failure message ");
        }
        items.finish(trackingId, id("E1"), Outcome.FAILED, message.toString(), null);

        assertEquals(script.toString(), runRow(trackingId).get("anon_custom_script"));
        final Map<String, Object> item = itemRows(trackingId).get(0);
        assertEquals(message.toString(), item.get("message"));
        final String json = (String) item.get("csv_values");
        assertTrue("100 values in their order: " + json.substring(0, 60),
                json.startsWith("{\"column_100\":\"value 100\",\"column_99\":") && json.endsWith("\"column_1\":\"value 1\"}"));
    }

    @Test
    public void itemsBelongToTheirRun() {
        final String trackingId = trackingId("fk");
        history.recordSubmit(new BatchTransfer(new ArrayList<>(Collections.singletonList(share(id("E1")))), trackingId),
                alice, null);
        final long historyId = ((Number) runRow(trackingId).get("id")).longValue();

        assertThrows("an item for a missing run", DataIntegrityViolationException.class, () -> jdbc.update(
                "INSERT INTO xhbm_batch_transfer_item (created, timestamp, disabled, enabled, history_id, item_id, status) "
                        + "VALUES (now(), now(), now(), true, -1, 'X', 'Queued')"));
        assertThrows("a run that still has items", DataIntegrityViolationException.class,
                () -> jdbc.update("DELETE FROM xhbm_batch_transfer_history WHERE id = ?", historyId));
        jdbc.update("DELETE FROM xhbm_batch_transfer_item WHERE history_id = ?", historyId);
        assertEquals(1, jdbc.update("DELETE FROM xhbm_batch_transfer_history WHERE id = ?", historyId));
    }

    @Test
    public void theTablesAndIndexesAreThere() {
        final List<String> indexes = jdbc.queryForList(
                "SELECT indexname FROM pg_indexes WHERE tablename = 'xhbm_batch_transfer_history'", String.class);
        assertTrue(indexes.toString(), indexes.containsAll(Arrays.asList(
                "bt_history_destination_created", "bt_history_source_created", "bt_history_creator_created")));
    }

    // ---- run lifecycle and Stop ---------------------------------------------------------------------

    @Test
    public void registeringMovesQueuedToRunningButNeverOverwritesStopping() {
        final String running = submit("running", 1);
        history.registerRun(running, 7, 1);
        assertEquals("Running", runRow(running).get("status"));

        final String stopping = submit("stopping", 1);
        assertEquals(StopResult.STOPPING, history.requestStop(stopping, "bob"));
        history.registerRun(stopping, 7, 1);
        assertEquals("Stopping", runRow(stopping).get("status"));
        assertEquals("bob", runRow(stopping).get("stop_requested_by"));
        assertNotNull(runRow(stopping).get("stop_requested_at"));
    }

    @Test
    public void aRunThatCouldNotStartIsFailedWithItsItems() {
        final String trackingId = submit("noStart", 2);

        history.failRun(trackingId, "Could not start. Cause: no database");

        final Map<String, Object> run = runRow(trackingId);
        assertEquals("Failed", run.get("status"));
        assertEquals(0, run.get("passed_count"));
        assertEquals(2, run.get("failed_count"));
        assertNotNull(run.get("finished"));
        for (final Map<String, Object> item : itemRows(trackingId)) {
            assertEquals("Failed", item.get("status"));
            assertEquals("Could not start. Cause: no database", item.get("message"));
            assertNotNull(item.get("finished"));
        }
        history.failRun(trackingId("noSuchRun"), "nothing to fail");
    }

    @Test
    public void aRunStoppedBeforeItCouldStartEndsStopped() {
        final String trackingId = submit("stopNoStart", 2);
        assertEquals(StopResult.STOPPING, history.requestStop(trackingId, "bob"));

        history.failRun(trackingId, "Could not start. Cause: no database");

        final Map<String, Object> run = runRow(trackingId);
        assertEquals("Stopped", run.get("status"));
        assertEquals(0, run.get("failed_count"));
        assertEquals(2, run.get("stopped_count"));
        assertEquals(2, countItems(trackingId, "Stopped"));
    }

    @Test
    public void anItemIsCountedOnceAndTheLastOneFinishesTheRun() {
        final String trackingId = submit("lifecycle", 3);
        history.registerRun(trackingId, 7, 3);

        assertTrue(items.start(trackingId, id("E0")).isRun());
        assertNull(items.finish(trackingId, id("E0"), Outcome.DONE, null, "DEST_E0"));
        assertNull("a repeat finish isn't counted", items.finish(trackingId, id("E0"), Outcome.DONE, null, "DEST_E0"));
        assertFalse("a repeat delivery of a finished item doesn't run", items.start(trackingId, id("E0")).isRun());
        assertEquals(1, progress.findByTrackingId(trackingId).getCompleted());

        assertTrue(items.start(trackingId, id("E1")).isRun());
        assertNull(items.finish(trackingId, id("E1"), Outcome.FAILED, "boom", null));
        assertTrue(items.start(trackingId, id("E2")).isRun());
        assertTrue("a running item runs again: its node may have died", items.start(trackingId, id("E2")).isRun());
        final Completion completion = items.finish(trackingId, id("E2"), Outcome.DONE, null, "DEST_E2");

        assertNotNull(completion);
        assertEquals(1, completion.getFailed());
        final Map<String, Object> run = runRow(trackingId);
        assertEquals("Warnings", run.get("status"));
        assertEquals(2, run.get("passed_count"));
        assertEquals(1, run.get("failed_count"));
        assertNotNull(run.get("finished"));
        assertNull("the progress row is gone", progress.findByTrackingId(trackingId));
        final Map<String, Object> first = itemRows(trackingId).get(0);
        assertEquals("Done", first.get("status"));
        assertEquals("DEST_E0", first.get("destination_id"));
        assertNotNull(first.get("started"));
        assertNotNull(first.get("finished"));
        assertEquals("boom", itemRows(trackingId).get(1).get("message"));
    }

    @Test
    public void aStopSkipsTheItemsThatHaveNotStarted() {
        final String trackingId = submit("stop", 3);
        history.registerRun(trackingId, 7, 3);
        assertTrue(items.start(trackingId, id("E0")).isRun());

        assertEquals(StopResult.STOPPING, history.requestStop(trackingId, alice.getUsername()));
        assertEquals(StopResult.ALREADY_STOPPING, history.requestStop(trackingId, alice.getUsername()));
        final StartResult second = items.start(trackingId, id("E1"));
        assertFalse(second.isRun());
        assertNull(second.getCompletion());
        assertEquals("Stopped", itemRows(trackingId).get(1).get("status"));

        assertNull(items.finish(trackingId, id("E0"), Outcome.DONE, null, null));
        final StartResult third = items.start(trackingId, id("E2"));
        assertFalse(third.isRun());
        assertNotNull("the last stopped item finishes the run", third.getCompletion());

        final Map<String, Object> run = runRow(trackingId);
        assertEquals("Stopped", run.get("status"));
        assertEquals(1, run.get("passed_count"));
        assertEquals(2, run.get("stopped_count"));
        assertEquals(StopResult.FINISHED, history.requestStop(trackingId, alice.getUsername()));
        assertEquals(StopResult.NOT_FOUND, history.requestStop(trackingId("unknown"), alice.getUsername()));
    }

    @Test
    public void concurrentFinishesOfOneItemCountItOnce() throws Exception {
        final String trackingId = submit("twice", 2);
        history.registerRun(trackingId, 7, 2);
        items.start(trackingId, id("E0"));
        final CountDownLatch go = new CountDownLatch(1);
        final List<Callable<Object>> workers = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            workers.add(() -> {
                go.await();
                return items.finish(trackingId, id("E0"), Outcome.DONE, null, null);
            });
        }
        runTogether(workers, go);
        assertEquals(1, progress.findByTrackingId(trackingId).getCompleted());
    }

    @Test
    public void itemsWithoutRowsAreCounted() {
        final String legacy = trackingId("legacy");
        progress.register(legacy, 7, 2);
        assertTrue("a batch from before the upgrade runs its items", items.start(legacy, "A").isRun());
        assertNull(items.finish(legacy, "A", Outcome.DONE, null, null));
        assertNotNull(items.finish(legacy, "B", Outcome.FAILED, "boom", null));

        final String trackingId = submit("missing", 1);
        history.registerRun(trackingId, 7, 2);
        assertTrue(items.start(trackingId, "NOT_IN_THE_RUN").isRun());
        assertNull(items.finish(trackingId, "NOT_IN_THE_RUN", Outcome.DONE, null, null));
        assertEquals("an item missing from its run is still counted", 1,
                progress.findByTrackingId(trackingId).getCompleted());
    }

    @Test
    public void aProgressRowWithoutAStoppedCountStillCompletes() {
        final String trackingId = trackingId("null_stopped");
        progress.register(trackingId, 7, 1);
        jdbc.update("UPDATE xhbm_batch_transfer_progress SET stopped = NULL WHERE tracking_id = ?", trackingId);

        final Completion completion = progress.recordItemDone(trackingId, Outcome.DONE);

        assertNotNull(completion);
        assertEquals(0, completion.getStopped());
    }

    @Test
    public void finishingTheRunIsAtomic() {
        final String trackingId = submit("atomic", 1);
        history.registerRun(trackingId, 7, 1);
        items.start(trackingId, id("E0"));
        jdbc.execute("CREATE OR REPLACE FUNCTION bt_refuse_finish() RETURNS trigger AS $$ BEGIN "
                + "IF NEW.status <> OLD.status AND NEW.tracking_id = '" + trackingId + "' THEN "
                + "RAISE EXCEPTION 'refused'; END IF; RETURN NEW; END $$ LANGUAGE plpgsql");
        jdbc.execute("CREATE TRIGGER bt_refuse_finish BEFORE UPDATE ON xhbm_batch_transfer_history "
                + "FOR EACH ROW EXECUTE FUNCTION bt_refuse_finish()");
        try {
            assertThrows(RuntimeException.class, () -> items.finish(trackingId, id("E0"), Outcome.DONE, null, null));
        } finally {
            jdbc.execute("DROP TRIGGER bt_refuse_finish ON xhbm_batch_transfer_history");
        }

        assertEquals("the item's update rolled back", "Running", itemRows(trackingId).get(0).get("status"));
        assertNotNull("the progress row's delete rolled back", progress.findByTrackingId(trackingId));
        assertEquals(0, progress.findByTrackingId(trackingId).getCompleted());
        assertEquals("Running", runRow(trackingId).get("status"));
    }

    @Test
    public void concurrentItemsAndAStopAgreeOnTheCounts() throws Exception {
        final int threads = 8;
        final int perThread = 5;
        final int total = threads * perThread;
        final String trackingId = submit("concurrent", total);
        history.registerRun(trackingId, 7, total);

        final CountDownLatch go = new CountDownLatch(1);
        final CountDownLatch someFinished = new CountDownLatch(10);
        final AtomicInteger skipped = new AtomicInteger();
        final List<Callable<Object>> workers = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final int first = t * perThread;
            workers.add(() -> {
                go.await();
                for (int i = first; i < first + perThread; i++) {
                    if (!items.start(trackingId, id("E" + i)).isRun()) {
                        skipped.incrementAndGet();
                        continue;
                    }
                    items.finish(trackingId, id("E" + i), i % 4 == 0 ? Outcome.FAILED : Outcome.DONE, null, null);
                    someFinished.countDown();
                }
                return null;
            });
        }
        workers.add(() -> {
            go.await();
            someFinished.await(30, TimeUnit.SECONDS);
            return history.requestStop(trackingId, alice.getUsername());
        });
        runTogether(workers, go);

        final Map<String, Object> run = runRow(trackingId);
        final int passed = (Integer) run.get("passed_count");
        final int failed = (Integer) run.get("failed_count");
        final int stopped = (Integer) run.get("stopped_count");
        assertEquals(total, passed + failed + stopped);
        assertEquals(skipped.get(), stopped);
        assertEquals(stopped > 0 ? "Stopped" : failed == 0 ? "Completed" : "Warnings", run.get("status"));
        assertEquals(passed, countItems(trackingId, "Done"));
        assertEquals(failed, countItems(trackingId, "Failed"));
        assertEquals(stopped, countItems(trackingId, "Stopped"));
        assertNull(progress.findByTrackingId(trackingId));
        assertEquals(StopResult.FINISHED, history.requestStop(trackingId, alice.getUsername()));
    }

    // ---- listing -------------------------------------------------------------------------------------

    @Test
    public void theListFiltersSortsAndPages() {
        final String p = id("P");
        final String q = id("Q");
        session(p, null, id("EP"), "A");
        session(q, null, id("EQ"), "B");
        final UserI bob = user(prefix + "_bob", 8);
        final String first = submitOne("first", id("EP"), DESTINATION, alice);
        final String second = submitOne("second", id("EQ"), p, bob);
        final String third = submitOne("third", id("EP"), "ELSEWHERE", alice);
        history.requestStop(third, alice.getUsername());

        assertEquals(Arrays.asList(third, second, first), ids(history.list(p, null, null, null, null, 0, 10)));
        assertEquals(3, history.count(p, null, null, null, null));
        assertEquals(Arrays.asList(third, first), ids(history.list(null, alice.getUsername(), null, null, null, 0, 10)));
        assertEquals(Collections.singletonList(third),
                ids(history.list(p, null, BatchTransferHistory.Status.Stopping, null, null, 0, 10)));
        assertEquals(Arrays.asList(third, second), ids(history.list(p, null, null, null, null, 0, 2)));
        assertEquals(Collections.singletonList(first), ids(history.list(p, null, null, null, null, 2, 2)));

        assertEquals("a user who reads only the second run's source sees only it", Collections.singletonList(second),
                ids(history.list(p, null, null, prefix + "_carol", Collections.singletonList(q), 0, 10)));
        assertEquals("without readable projects, only the user's own runs",
                Arrays.asList(third, first), ids(history.list(p, null, null, alice.getUsername(), null, 0, 10)));
    }

    // ---- fixtures ------------------------------------------------------------------------------------

    private String id(final String suffix) {
        return prefix + "_" + suffix;
    }

    private String trackingId(final String name) {
        return "batch_transfer_" + prefix + "_" + name;
    }

    private static UserI user(final String username, final int id) {
        final UserI user = mock(UserI.class);
        when(user.getUsername()).thenReturn(username);
        when(user.getID()).thenReturn(id);
        return user;
    }

    private void subject(final String project, final String subjectId, final String label) {
        jdbc.update("INSERT INTO xnat_subjectdata (id, project, label) VALUES (?, ?, ?)", subjectId, project, label);
    }

    private void session(final String project, final String subjectId, final String sessionId, final String label) {
        jdbc.update("INSERT INTO xnat_experimentdata (id, project, label) VALUES (?, ?, ?)", sessionId, project, label);
        if (subjectId != null) {
            jdbc.update("INSERT INTO xnat_subjectassessordata (id, subject_id) VALUES (?, ?)", sessionId, subjectId);
        }
    }

    private void shareSubject(final String subjectId, final String project, final String label) {
        jdbc.update("INSERT INTO xnat_projectparticipant (subject_id, project, label) VALUES (?, ?, ?)",
                subjectId, project, label);
    }

    private void shareSession(final String sessionId, final String project, final String label) {
        jdbc.update("INSERT INTO xnat_experimentdata_share (sharing_share_xnat_experimentda_id, project, label) "
                + "VALUES (?, ?, ?)", sessionId, project, label);
    }

    private TransferRequest reimport(final String id, final Integer rowNumber, final Map<String, String> values) {
        final TransferRequest request = new TransferRequest(DESTINATION, id, TransferMode.REIMPORT, true, true,
                values, null, null, null, false);
        request.setRowNumber(rowNumber);
        return request;
    }

    private static TransferRequest share(final String id) {
        return new TransferRequest(DESTINATION, id, TransferMode.SHARE);
    }

    /** A run of {@code count} Reimport items E0, E1, ... */
    private String submit(final String name, final int count) {
        final List<TransferRequest> requests = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            requests.add(reimport(id("E" + i), null, null));
        }
        final String trackingId = trackingId(name);
        history.recordSubmit(new BatchTransfer(requests, trackingId), alice, null);
        return trackingId;
    }

    private String submitOne(final String name, final String itemId, final String destination, final UserI user) {
        final String trackingId = trackingId(name);
        history.recordSubmit(new BatchTransfer(new ArrayList<>(Collections.singletonList(
                new TransferRequest(destination, itemId, TransferMode.SHARE))), trackingId), user, null);
        return trackingId;
    }

    private Map<String, Object> runRow(final String trackingId) {
        return jdbc.queryForMap("SELECT * FROM xhbm_batch_transfer_history WHERE tracking_id = ?", trackingId);
    }

    private List<Map<String, Object>> itemRows(final String trackingId) {
        return jdbc.queryForList("SELECT i.* FROM xhbm_batch_transfer_item i "
                + "JOIN xhbm_batch_transfer_history h ON h.id = i.history_id WHERE h.tracking_id = ? ORDER BY i.id",
                trackingId);
    }

    private int countItems(final String trackingId, final String status) {
        return jdbc.queryForObject("SELECT count(*) FROM xhbm_batch_transfer_item i "
                + "JOIN xhbm_batch_transfer_history h ON h.id = i.history_id WHERE h.tracking_id = ? AND i.status = ?",
                Integer.class, trackingId, status);
    }

    private static List<String> ids(final List<BatchTransferHistory> runs) {
        final List<String> ids = new ArrayList<>();
        for (final BatchTransferHistory run : runs) {
            ids.add(run.getTrackingId());
        }
        return ids;
    }

    /** Runs the workers together and fails on any exception one of them threw, a deadlock included. */
    private static void runTogether(final List<Callable<Object>> workers, final CountDownLatch go) throws Exception {
        final ExecutorService pool = Executors.newFixedThreadPool(workers.size());
        try {
            final List<Future<Object>> futures = new ArrayList<>();
            for (final Callable<Object> worker : workers) {
                futures.add(pool.submit(worker));
            }
            go.countDown();
            for (final Future<Object> future : futures) {
                try {
                    future.get(60, TimeUnit.SECONDS);
                } catch (java.util.concurrent.ExecutionException e) {
                    fail("A worker failed: " + e.getCause());
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
