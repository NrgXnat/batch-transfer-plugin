package org.nrg.xnatx.plugins.transfer.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;
import lombok.extern.slf4j.Slf4j;
import org.nrg.framework.annotations.XapiRestController;
import org.nrg.framework.services.NrgEventService;
import org.nrg.xapi.rest.AbstractXapiRestController;
import org.nrg.xapi.rest.XapiRequestMapping;
import org.nrg.xdat.security.helpers.AccessLevel;
import org.nrg.xdat.security.helpers.Permissions;
import org.nrg.xdat.security.helpers.Roles;
import org.nrg.xdat.security.services.RoleHolder;
import org.nrg.xdat.security.services.UserManagementServiceI;
import org.nrg.xft.security.UserI;
import org.nrg.xnatx.plugins.transfer.event.BatchTransferEvent;
import org.nrg.xnatx.plugins.transfer.jms.tasks.entities.BatchTransferHistory;
import org.nrg.xnatx.plugins.transfer.jms.tasks.entities.BatchTransferItem;
import org.nrg.xnatx.plugins.transfer.jms.tasks.entities.BatchTransferProgress;
import org.nrg.xnatx.plugins.transfer.jms.tasks.services.BatchTransferHistoryService;
import org.nrg.xnatx.plugins.transfer.jms.tasks.services.BatchTransferHistoryService.StopResult;
import org.nrg.xnatx.plugins.transfer.jms.tasks.services.BatchTransferProgressService;
import org.nrg.xnatx.plugins.transfer.model.TransferRun;
import org.nrg.xnatx.plugins.transfer.model.TransferRunDetail;
import org.nrg.xnatx.plugins.transfer.model.TransferRunItem;
import org.nrg.xnatx.plugins.transfer.model.TransferRunPage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;
import static org.springframework.web.bind.annotation.RequestMethod.GET;
import static org.springframework.web.bind.annotation.RequestMethod.POST;

/**
 * Transfer history and Stop. A run is visible to the user who started it, to users who can read its source project,
 * and to site admins. Its CSV values and custom script go only to users who could have submitted it: its creator,
 * site admins, and users who can edit its source project. Only the creator or a site admin can stop it.
 */
@Slf4j
@Api("Batch Transfer History Api")
@RequestMapping(value = "/transfer/history")
@XapiRestController
public class BatchTransferHistoryApi extends AbstractXapiRestController {

    private static final int DEFAULT_PAGE_SIZE = 25;
    private static final int MAX_PAGE_SIZE     = 200;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final BatchTransferHistoryService  historyService;
    private final BatchTransferProgressService progressService;
    private final NrgEventService              eventService;

    @Autowired
    public BatchTransferHistoryApi(final UserManagementServiceI userManagementService, final RoleHolder roleHolder,
                                   final BatchTransferHistoryService historyService,
                                   final BatchTransferProgressService progressService,
                                   final NrgEventService eventService) {
        super(userManagementService, roleHolder);
        this.historyService = historyService;
        this.progressService = progressService;
        this.eventService = eventService;
    }

    @ApiOperation(value = "Lists transfer runs, newest first: a project's runs (as source or destination), or the "
            + "caller's own runs when no project is given")
    @ApiResponses({@ApiResponse(code = 200, message = "A page of runs"),
                   @ApiResponse(code = 400, message = "Unknown status")})
    @XapiRequestMapping(value = {""}, produces = APPLICATION_JSON_VALUE, method = GET,
            restrictTo = AccessLevel.Authenticated)
    @ResponseBody
    public ResponseEntity<?> list(@RequestParam(value = "project", required = false) final String project,
                                  @RequestParam(value = "status", required = false) final String status,
                                  @RequestParam(value = "page", required = false, defaultValue = "0") final int page,
                                  @RequestParam(value = "size", required = false, defaultValue = "25") final int size) {
        final BatchTransferHistory.Status statusFilter;
        try {
            statusFilter = status == null ? null : BatchTransferHistory.Status.valueOf(status);
        } catch (IllegalArgumentException e) {
            return new ResponseEntity<>("Unknown status: " + status, HttpStatus.BAD_REQUEST);
        }
        final int pageSize = size < 1 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        final int pageNumber = Math.max(page, 0);

        final UserI user = getSessionUser();
        final boolean admin = isSiteAdmin(user);
        final String createdBy = project == null ? user.getUsername() : null;
        final String visibleTo = admin ? null : user.getUsername();
        final Collection<String> readable = admin ? null : readableProjects(user);

        final List<TransferRun> runs = new ArrayList<>();
        for (final BatchTransferHistory run : historyService.list(project, createdBy, statusFilter, visibleTo, readable,
                pageNumber * pageSize, pageSize)) {
            runs.add(toView(run));
        }
        final long total = historyService.count(project, createdBy, statusFilter, visibleTo, readable);
        return new ResponseEntity<>(new TransferRunPage(runs, total, pageNumber, pageSize), HttpStatus.OK);
    }

    @ApiOperation(value = "A transfer run with its items")
    @ApiResponses({@ApiResponse(code = 200, message = "The run"),
                   @ApiResponse(code = 403, message = "The caller can't see this run"),
                   @ApiResponse(code = 404, message = "No run has this tracking id")})
    @XapiRequestMapping(value = {"{trackingId:.+}"}, produces = APPLICATION_JSON_VALUE, method = GET,
            restrictTo = AccessLevel.Authenticated)
    @ResponseBody
    public ResponseEntity<?> detail(@PathVariable("trackingId") final String trackingId) {
        final BatchTransferHistory run = historyService.findByTrackingId(trackingId);
        if (run == null) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }
        final UserI user = getSessionUser();
        if (!canSee(user, run)) {
            return new ResponseEntity<>(HttpStatus.FORBIDDEN);
        }
        final boolean showValues = canSeeValues(user, run);
        final List<BatchTransferItem> items = historyService.items(run.getId());
        final Set<String> destinationIds = new LinkedHashSet<>();
        for (final BatchTransferItem item : items) {
            if (item.getDestinationId() != null) {
                destinationIds.add(item.getDestinationId());
            }
        }
        final Map<String, String> destinationLabels = historyService.experimentLabels(destinationIds);

        final TransferRunDetail detail = new TransferRunDetail();
        detail.setRun(toView(run));
        detail.setCanStop(canStop(user, run) && (run.getStatus() == BatchTransferHistory.Status.Queued
                || run.getStatus() == BatchTransferHistory.Status.Running));
        if (showValues) {
            detail.setAnonScript(run.getAnonCustomScript());
        }
        final List<TransferRunItem> views = new ArrayList<>(items.size());
        for (final BatchTransferItem item : items) {
            views.add(toView(item, showValues, destinationLabels));
        }
        detail.setItems(views);
        return new ResponseEntity<>(detail, HttpStatus.OK);
    }

    @ApiOperation(value = "Stops a run: items that haven't started are skipped, and items already transferring finish")
    @ApiResponses({@ApiResponse(code = 200, message = "The run is stopping"),
                   @ApiResponse(code = 403, message = "Only the user who started the run, or a site admin, can stop it"),
                   @ApiResponse(code = 404, message = "No run has this tracking id"),
                   @ApiResponse(code = 409, message = "The run has already finished")})
    @XapiRequestMapping(value = {"{trackingId:.+}/stop"}, produces = APPLICATION_JSON_VALUE, method = POST,
            restrictTo = AccessLevel.Authenticated)
    @ResponseBody
    public ResponseEntity<?> stop(@PathVariable("trackingId") final String trackingId) {
        final BatchTransferHistory run = historyService.findByTrackingId(trackingId);
        if (run == null) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }
        final UserI user = getSessionUser();
        if (!canStop(user, run)) {
            return new ResponseEntity<>("Only the user who started this transfer, or a site admin, can stop it.",
                    HttpStatus.FORBIDDEN);
        }
        final StopResult result = historyService.requestStop(trackingId, user.getUsername());
        switch (result) {
            case NOT_FOUND:
                return new ResponseEntity<>(HttpStatus.NOT_FOUND);
            case FINISHED:
                return new ResponseEntity<>("This transfer has already finished.", HttpStatus.CONFLICT);
            case STOPPING:
                eventService.triggerEvent(BatchTransferEvent.progress(run.getUserId(), 0, trackingId,
                        "Stop requested by " + user.getUsername()));
                break;
            default:
                break;
        }
        return new ResponseEntity<>(toView(historyService.findByTrackingId(trackingId)), HttpStatus.OK);
    }

    private boolean canSee(final UserI user, final BatchTransferHistory run) {
        return isCreator(user, run) || isSiteAdmin(user)
                || (run.getSourceProject() != null && canReadProject(user, run.getSourceProject()));
    }

    private boolean canSeeValues(final UserI user, final BatchTransferHistory run) {
        return isCreator(user, run) || isSiteAdmin(user)
                || (run.getSourceProject() != null && canEditProject(user, run.getSourceProject()));
    }

    private boolean canStop(final UserI user, final BatchTransferHistory run) {
        return isCreator(user, run) || isSiteAdmin(user);
    }

    private static boolean isCreator(final UserI user, final BatchTransferHistory run) {
        return user.getUsername() != null && user.getUsername().equals(run.getCreatedBy());
    }

    /** Protected seams, so tests can stub XNAT's permission checks. */
    protected boolean isSiteAdmin(final UserI user) {
        return Roles.isSiteAdmin(user);
    }

    protected boolean canReadProject(final UserI user, final String project) {
        try {
            return Permissions.canReadProject(user, project);
        } catch (Exception e) {
            log.error("Could not check read access on project {}", project, e);
            return false;
        }
    }

    protected boolean canEditProject(final UserI user, final String project) {
        try {
            return Permissions.canEditProject(user, project);
        } catch (Exception e) {
            log.error("Could not check edit access on project {}", project, e);
            return false;
        }
    }

    protected Collection<String> readableProjects(final UserI user) {
        return Permissions.getReadableProjects(user);
    }

    private TransferRun toView(final BatchTransferHistory run) {
        final TransferRun view = new TransferRun();
        view.setTrackingId(run.getTrackingId());
        view.setStatus(run.getStatus().name());
        view.setOperation(run.getOperation());
        view.setSourceProject(run.getSourceProject());
        view.setDestinationProject(run.getDestinationProject());
        view.setItemCount(run.getItemCount());
        view.setPassedCount(run.getPassedCount());
        view.setFailedCount(run.getFailedCount());
        view.setStoppedCount(run.getStoppedCount());
        if (!run.getStatus().isFinished()) {
            final BatchTransferProgress progress = progressService.findByTrackingId(run.getTrackingId());
            if (progress != null) {
                final int stopped = progress.getStopped() == null ? 0 : progress.getStopped();
                view.setPassedCount(progress.getCompleted() - progress.getFailed() - stopped);
                view.setFailedCount(progress.getFailed());
                view.setStoppedCount(stopped);
            }
        }
        view.setCustomScript(run.getAnonCustomScript() != null);
        view.setAnonReplacedPipeline(run.isAnonReplacedPipeline());
        view.setPreserveSubjectLabel(run.getPreserveSubjectLabel());
        view.setPreserveSessionLabel(run.getPreserveSessionLabel());
        view.setCreatedBy(run.getCreatedBy());
        view.setCreated(run.getCreated());
        view.setFinished(run.getFinished());
        view.setStopRequestedBy(run.getStopRequestedBy());
        view.setStopRequestedAt(run.getStopRequestedAt());
        return view;
    }

    private static TransferRunItem toView(final BatchTransferItem item, final boolean showValues,
                                          final Map<String, String> destinationLabels) {
        final TransferRunItem view = new TransferRunItem();
        view.setItemId(item.getItemId());
        view.setMode(item.getMode());
        view.setRowNumber(item.getRowNumber());
        view.setSourceSubjectLabel(item.getSourceSubjectLabel());
        view.setSourceLabel(item.getSourceLabel());
        view.setDestinationProject(item.getDestinationProject());
        view.setDestinationSubjectLabel(item.getDestinationSubjectLabel());
        view.setDestinationSessionLabel(item.getDestinationSessionLabel());
        view.setPreserveSubjectLabel(item.getPreserveSubjectLabel());
        view.setPreserveSessionLabel(item.getPreserveSessionLabel());
        if (showValues) {
            view.setCsvValues(fromJson(item.getCsvValues()));
        }
        view.setStatus(item.getStatus().name());
        view.setMessage(item.getMessage());
        view.setDestinationId(item.getDestinationId());
        view.setDestinationLabel(item.getDestinationId() == null ? null : destinationLabels.get(item.getDestinationId()));
        view.setStarted(item.getStarted());
        view.setFinished(item.getFinished());
        return view;
    }

    private static Map<String, String> fromJson(final String json) {
        if (json == null) {
            return null;
        }
        try {
            return JSON.readValue(json, new TypeReference<LinkedHashMap<String, String>>() {});
        } catch (Exception e) {
            log.error("Could not read an item's recorded CSV values", e);
            return null;
        }
    }
}
