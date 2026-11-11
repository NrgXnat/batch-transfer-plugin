package org.nrg.xnatx.plugins.transfer.jms.tasks.entities;

import org.hibernate.annotations.Cache;
import org.junit.Test;

import javax.persistence.Cacheable;
import java.lang.reflect.Method;

import static org.junit.Assert.assertFalse;

/**
 * XNAT's second-level cache is kept per node, so a cached run, item or progress row could show one node a status the
 * other node has already changed. None of them may be cached.
 */
public class HistoryEntitiesTest {

    @Test
    public void noTransferEntityIsCached() {
        for (final Class<?> entity : new Class<?>[]{BatchTransferHistory.class, BatchTransferItem.class,
                BatchTransferProgress.class}) {
            assertFalse(entity.getSimpleName(), entity.isAnnotationPresent(Cache.class));
            assertFalse(entity.getSimpleName(), entity.isAnnotationPresent(Cacheable.class));
            for (final Method method : entity.getMethods()) {
                assertFalse(entity.getSimpleName() + "." + method.getName(), method.isAnnotationPresent(Cache.class));
            }
        }
    }
}
