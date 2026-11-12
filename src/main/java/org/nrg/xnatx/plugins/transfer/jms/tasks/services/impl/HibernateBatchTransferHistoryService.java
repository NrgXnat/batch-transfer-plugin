package org.nrg.xnatx.plugins.transfer.jms.tasks.services.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
import org.nrg.framework.orm.hibernate.AbstractHibernateEntityService;
import org.nrg.xft.security.UserI;
import org.nrg.xnatx.plugins.transfer.jms.tasks.dao.BatchTransferHistoryDAO;
import org.nrg.xnatx.plugins.transfer.jms.tasks.dao.BatchTransferItemDAO;
import org.nrg.xnatx.plugins.transfer.jms.tasks.dao.XnatItemQueries;
import org.nrg.xnatx.plugins.transfer.jms.tasks.dao.XnatItemQueries.SourceItem;
import org.nrg.xnatx.plugins.transfer.jms.tasks.entities.BatchTransferHistory;
import org.nrg.xnatx.plugins.transfer.jms.tasks.entities.BatchTransferItem;
import org.nrg.xnatx.plugins.transfer.jms.tasks.services.BatchTransferHistoryService;
import org.nrg.xnatx.plugins.transfer.jms.tasks.services.BatchTransferProgressService;
import org.nrg.xnatx.plugins.transfer.jms.tasks.services.BatchTransferRunExistsException;
import org.nrg.xnatx.plugins.transfer.model.BatchTransfer;
import org.nrg.xnatx.plugins.transfer.model.TransferMode;
import org.nrg.xnatx.plugins.transfer.model.TransferRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Hibernate-backed {@link BatchTransferHistoryService}. */
@Service
public class HibernateBatchTransferHistoryService
        extends AbstractHibernateEntityService<BatchTransferHistory, BatchTransferHistoryDAO>
        implements BatchTransferHistoryService {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final BatchTransferItemDAO         itemDao;
    private final BatchTransferProgressService progressService;
    private final XnatItemQueries              xnatItems;

    @Autowired
    public HibernateBatchTransferHistoryService(final BatchTransferItemDAO itemDao,
                                                final BatchTransferProgressService progressService,
                                                final XnatItemQueries xnatItems) {
        this.itemDao = itemDao;
        this.progressService = progressService;
        this.xnatItems = xnatItems;
    }

    @Override
    @Transactional
    public void recordSubmit(final BatchTransfer batch, final UserI user, final Collection<String> readableProjects) {
        final String trackingId = batch.getTrackingId();
        if (getDao().findByTrackingId(trackingId) != null) {
            throw new BatchTransferRunExistsException(trackingId);
        }
        final List<TransferRequest> requests = batch.getRequests();
        final Set<String> ids = new LinkedHashSet<>();
        for (final TransferRequest request : requests) {
            ids.add(request.getId());
        }
        // The run belongs to the project it was started from only when every requested item was found there, so the
        // project's members never see a run with items from elsewhere. Otherwise it goes to the owning project, with
        // the items' own labels.
        final String startedFrom = StringUtils.trimToNull(batch.getSourceProject());
        final Map<String, SourceItem> sources = xnatItems.sourceItems(ids, startedFrom, readableProjects);
        final boolean inStartedFrom = startedFrom != null && sources.size() == ids.size()
                && sources.values().stream().allMatch(SourceItem::isInProject);

        final Set<String> modes = new LinkedHashSet<>();
        final Set<String> destinations = new LinkedHashSet<>();
        final Set<String> sourceProjects = new LinkedHashSet<>();
        TransferRequest firstReimport = null;
        for (final TransferRequest request : requests) {
            modes.add(modeName(request.getMode()));
            destinations.add(request.getDestinationProject());
            final SourceItem source = sources.get(request.getId());
            if (source != null) {
                sourceProjects.add(source.getProject());
            }
            if (firstReimport == null && request.getMode() == TransferMode.REIMPORT) {
                firstReimport = request;
            }
        }

        final BatchTransferHistory run = new BatchTransferHistory();
        run.setTrackingId(trackingId);
        run.setStatus(BatchTransferHistory.Status.Queued);
        run.setOperation(modes.size() == 1 ? modes.iterator().next() : "Mixed");
        if (inStartedFrom) {
            run.setSourceProject(startedFrom);
        } else {
            run.setSourceProject(sourceProjects.size() == 1 ? sourceProjects.iterator().next() : null);
        }
        run.setDestinationProject(destinations.size() == 1 ? destinations.iterator().next() : null);
        run.setItemCount(requests.size());
        run.setAnonCustomScript(StringUtils.defaultIfBlank(batch.getAnonScript(), null));
        run.setAnonReplacedPipeline(batch.isAnonReplacePipeline());
        if (firstReimport != null) {
            run.setPreserveSubjectLabel(firstReimport.getPreserveSubjectLabel());
            run.setPreserveSessionLabel(firstReimport.getPreserveSessionLabel());
        }
        run.setCreatedBy(user.getUsername());
        run.setUserId(user.getID());
        getDao().create(run);

        final List<BatchTransferItem> items = new ArrayList<>(requests.size());
        for (final TransferRequest request : requests) {
            final SourceItem source = sources.get(request.getId());
            final BatchTransferItem item = new BatchTransferItem();
            item.setItemId(request.getId());
            item.setMode(modeName(request.getMode()));
            item.setDestinationProject(request.getDestinationProject());
            item.setPreserveSubjectLabel(request.getPreserveSubjectLabel());
            item.setPreserveSessionLabel(request.getPreserveSessionLabel());
            item.setDestinationSubjectLabel(request.getDestinationSubjectLabel());
            item.setDestinationSessionLabel(request.getDestinationSessionLabel());
            item.setCsvValues(toJson(request.getCsvValues()));
            item.setRowNumber(request.getRowNumber());
            if (source != null) {
                item.setSourceSubjectLabel(inStartedFrom ? source.getSubjectLabelInProject() : source.getSubjectLabel());
                item.setSourceLabel(inStartedFrom ? source.getLabelInProject() : source.getLabel());
            }
            item.setStatus(BatchTransferItem.Status.Queued);
            items.add(item);
        }
        itemDao.createAll(run.getId(), items);
    }

    @Override
    @Transactional
    public void registerRun(final String trackingId, final Integer userId, final int total) {
        progressService.register(trackingId, userId, total);
        getDao().markRunning(trackingId);
    }

    @Override
    @Transactional
    public void failRun(final String trackingId, final String message) {
        final BatchTransferHistory run = getDao().findByTrackingId(trackingId);
        if (run == null) {
            return;
        }
        // A Stop requested before the run could start still ends it as stopped: none of its items ran.
        if (run.getStatus() == BatchTransferHistory.Status.Stopping) {
            final int stopped = itemDao.finishUnfinished(run.getId(), BatchTransferItem.Status.Stopped, null);
            getDao().finish(run.getId(), BatchTransferHistory.Status.Stopped, 0, 0, stopped);
        } else {
            final int failed = itemDao.finishUnfinished(run.getId(), BatchTransferItem.Status.Failed, message);
            getDao().finish(run.getId(), BatchTransferHistory.Status.Failed, 0, failed, 0);
        }
    }

    @Override
    @Transactional
    public StopResult requestStop(final String trackingId, final String username) {
        if (getDao().requestStop(trackingId, username) == 1) {
            return StopResult.STOPPING;
        }
        final BatchTransferHistory run = getDao().findByTrackingId(trackingId);
        if (run == null) {
            return StopResult.NOT_FOUND;
        }
        return run.getStatus() == BatchTransferHistory.Status.Stopping ? StopResult.ALREADY_STOPPING : StopResult.FINISHED;
    }

    @Override
    @Transactional
    public BatchTransferHistory findByTrackingId(final String trackingId) {
        return getDao().findByTrackingId(trackingId);
    }

    @Override
    @Transactional
    public List<BatchTransferHistory> list(final String project, final String createdBy,
                                           final BatchTransferHistory.Status status, final String visibleTo,
                                           final Collection<String> readableProjects, final int offset,
                                           final int limit) {
        return getDao().list(project, createdBy, status, visibleTo, readableProjects, offset, limit);
    }

    @Override
    @Transactional
    public long count(final String project, final String createdBy, final BatchTransferHistory.Status status,
                      final String visibleTo, final Collection<String> readableProjects) {
        return getDao().count(project, createdBy, status, visibleTo, readableProjects);
    }

    @Override
    @Transactional
    public List<BatchTransferItem> items(final long historyId) {
        return itemDao.listForRun(historyId);
    }

    @Override
    public Map<String, String> itemLabels(final Collection<String> ids) {
        return xnatItems.itemLabels(ids);
    }

    private static String modeName(final TransferMode mode) {
        return mode == null ? null : mode.getValue();
    }

    private static String toJson(final Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        try {
            return JSON.writeValueAsString(values);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Could not record the item's CSV values", e);
        }
    }
}
