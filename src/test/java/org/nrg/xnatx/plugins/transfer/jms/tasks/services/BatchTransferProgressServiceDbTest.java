package org.nrg.xnatx.plugins.transfer.jms.tasks.services;

import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;
import org.nrg.xnatx.plugins.transfer.DatabaseTest;
import org.nrg.xnatx.plugins.transfer.config.DatabaseTestConfig;
import org.nrg.xnatx.plugins.transfer.jms.tasks.services.BatchTransferProgressService.Completion;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The progress row against PostgreSQL: its pessimistic lock, its reset on a repeated tracking id, and the unique
 * constraint behind concurrent registration. Not {@code @Transactional}: each service call commits in its own
 * transaction, as it does in XNAT, so the tests see real locking and real commits.
 */
@Category(DatabaseTest.class)
@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(classes = DatabaseTestConfig.class)
public class BatchTransferProgressServiceDbTest {

    private static final int USER_ID = 42;

    @Autowired
    private BatchTransferProgressService service;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @Test
    public void theTableIsNamedAsXnatNamesIt() {
        final List<String> columns = jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_name = 'xhbm_batch_transfer_progress'",
                Collections.<String, Object>emptyMap(), String.class);
        assertTrue(columns.toString(), columns.containsAll(
                List.of("id", "tracking_id", "user_id", "total", "completed", "failed")));
    }

    @Test
    public void concurrentItemsCompleteTheBatchExactlyOnce() throws Exception {
        final String trackingId = newTrackingId();
        final int threads = 8;
        final int itemsPerThread = 5;
        final int total = threads * itemsPerThread;
        service.register(trackingId, USER_ID, total);

        // Every fourth item fails, so the completion has to carry 10 failures counted across all threads.
        final CountDownLatch start = new CountDownLatch(1);
        final List<Callable<List<Completion>>> workers = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final int first = t * itemsPerThread;
            workers.add(() -> {
                start.await();
                final List<Completion> completions = new ArrayList<>();
                for (int item = first; item < first + itemsPerThread; item++) {
                    final Completion completion = service.recordItemDone(trackingId, item % 4 == 0);
                    if (completion != null) {
                        completions.add(completion);
                    }
                }
                return completions;
            });
        }
        final List<Completion> completions = runTogether(workers, start);

        assertEquals("exactly one call completes the batch: " + completions, 1, completions.size());
        assertEquals(Integer.valueOf(USER_ID), completions.get(0).getUserId());
        assertEquals(10, completions.get(0).getFailed());
        assertEquals("the completing call deletes the row", 0, rowCount(trackingId));
        assertNull("a later item finds no row", service.recordItemDone(trackingId, false));
    }

    @Test
    public void aRepeatedTrackingIdResetsTheRow() {
        final String trackingId = newTrackingId();
        service.register(trackingId, USER_ID, 5);
        assertNull(service.recordItemDone(trackingId, true));
        assertNull(service.recordItemDone(trackingId, false));

        service.register(trackingId, 7, 2);

        final Map<String, Object> row = row(trackingId);
        assertEquals(7, row.get("user_id"));
        assertEquals(2, row.get("total"));
        assertEquals(0, row.get("completed"));
        assertEquals(0, row.get("failed"));
        assertNull(service.recordItemDone(trackingId, false));
        final Completion completion = service.recordItemDone(trackingId, false);
        assertEquals("the failure before the reset isn't counted", 0, completion.getFailed());
        assertEquals(Integer.valueOf(7), completion.getUserId());
    }

    @Test
    public void concurrentRegistrationsLeaveOneRow() throws Exception {
        final String trackingId = newTrackingId();
        final CountDownLatch start = new CountDownLatch(1);
        final List<Callable<List<Throwable>>> workers = new ArrayList<>();
        for (int user = 1; user <= 2; user++) {
            final int userId = user;
            workers.add(() -> {
                start.await();
                try {
                    service.register(trackingId, userId, 4);
                    return Collections.emptyList();
                } catch (RuntimeException e) {
                    return Collections.singletonList(e);
                }
            });
        }
        final List<Throwable> failures = runTogether(workers, start);

        assertEquals(1, rowCount(trackingId));
        // When both inserts race, the unique constraint on tracking_id rejects one; XNAT's caller catches that.
        for (final Throwable failure : failures) {
            assertTrue("only a unique violation may fail a registration: " + failure, isUniqueViolation(failure));
        }
    }

    /** Runs the workers on their own threads, releases them together, and gathers what they return. */
    private static <T> List<T> runTogether(final List<Callable<List<T>>> workers, final CountDownLatch start)
            throws Exception {
        final ExecutorService pool = Executors.newFixedThreadPool(workers.size());
        try {
            final List<Future<List<T>>> futures = new ArrayList<>();
            for (final Callable<List<T>> worker : workers) {
                futures.add(pool.submit(worker));
            }
            start.countDown();
            final List<T> results = new ArrayList<>();
            for (final Future<List<T>> future : futures) {
                results.addAll(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private static boolean isUniqueViolation(final Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException && "23505".equals(((SQLException) cause).getSQLState())) {
                return true;
            }
        }
        return false;
    }

    private static String newTrackingId() {
        return "batch_transfer_dbtest_" + UUID.randomUUID();
    }

    private int rowCount(final String trackingId) {
        return jdbc.queryForObject("SELECT count(*) FROM xhbm_batch_transfer_progress WHERE tracking_id = :id",
                Collections.singletonMap("id", trackingId), Integer.class);
    }

    private Map<String, Object> row(final String trackingId) {
        return jdbc.queryForMap("SELECT user_id, total, completed, failed FROM xhbm_batch_transfer_progress "
                + "WHERE tracking_id = :id", Collections.singletonMap("id", trackingId));
    }
}
