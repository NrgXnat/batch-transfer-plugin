package org.nrg.xnatx.plugins.transfer.jms.tasks.dao;

import org.hibernate.Criteria;
import org.hibernate.LockMode;
import org.hibernate.criterion.Restrictions;
import org.nrg.framework.orm.hibernate.AbstractHibernateDAO;
import org.nrg.xnatx.plugins.transfer.jms.tasks.entities.BatchTransferProgress;
import org.springframework.stereotype.Repository;

@Repository
public class BatchTransferProgressDAO extends AbstractHibernateDAO<BatchTransferProgress> {

    /** Finds a batch's progress row, or {@code null} if none exists. */
    public BatchTransferProgress findByTrackingId(final String trackingId) {
        return byTrackingId(trackingId, false);
    }

    /**
     * Finds a batch's progress row with a pessimistic write lock ({@code SELECT ... FOR UPDATE}), so the
     * calling transaction can read-modify-write the counter atomically. Concurrent callers (on this or any
     * other node) serialize on the lock, so exactly one observes {@code completed == total}.
     */
    public BatchTransferProgress findByTrackingIdForUpdate(final String trackingId) {
        return byTrackingId(trackingId, true);
    }

    private BatchTransferProgress byTrackingId(final String trackingId, final boolean forUpdate) {
        final Criteria criteria = getSession().createCriteria(getParameterizedType());
        criteria.add(Restrictions.eq("trackingId", trackingId));
        if (forUpdate) {
            criteria.setLockMode(LockMode.PESSIMISTIC_WRITE);
        }
        return (BatchTransferProgress) criteria.uniqueResult();
    }
}
