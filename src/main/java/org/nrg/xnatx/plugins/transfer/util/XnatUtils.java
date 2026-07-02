package org.nrg.xnatx.plugins.transfer.util;

import com.google.common.collect.ImmutableMap;
import lombok.extern.slf4j.Slf4j;
import org.nrg.xdat.XDAT;
import org.nrg.xdat.base.BaseElement;
import org.nrg.xdat.model.XnatImagescandataI;
import org.nrg.xdat.model.XnatSubjectassessordataI;
import org.nrg.xdat.om.*;
import org.nrg.xdat.om.base.BaseXnatImagescandata;
import org.nrg.xdat.om.base.BaseXnatProjectdata;
import org.nrg.xdat.om.base.BaseXnatSubjectdata;
import org.nrg.xdat.om.base.auto.AutoXnatExperimentdata;
import org.nrg.xdat.om.base.auto.AutoXnatProjectdata;
import org.nrg.xdat.om.base.auto.AutoXnatSubjectdata;
import org.nrg.xdat.security.helpers.Users;
import org.nrg.xft.ItemI;
import org.nrg.xft.db.MaterializedView;
import org.nrg.xft.event.EventDetails;
import org.nrg.xft.event.EventMetaI;
import org.nrg.xft.event.EventUtils;
import org.nrg.xft.event.XftItemEvent;
import org.nrg.xft.event.persist.PersistentWorkflowI;
import org.nrg.xft.event.persist.PersistentWorkflowUtils;
import org.nrg.xft.security.UserI;
import org.nrg.xft.utils.SaveItemHelper;
import org.nrg.xnat.turbine.utils.ArchivableItem;
import org.nrg.xnat.utils.WorkflowUtils;

import java.util.List;
import java.util.concurrent.Callable;

import static org.nrg.xft.event.XftItemEventI.DELETE;

@Slf4j
public class XnatUtils {

    public static XnatSubjectdata getSubject(String id, UserI user) throws Exception {
        final XnatSubjectdata subjectData = AutoXnatSubjectdata.getXnatSubjectdatasById(id, user, false);
        if (subjectData == null) {
            throw new Exception("Subject does not exist.");
        }
        return subjectData;
    }

    public static XnatProjectdata getProject(String id, UserI user) throws Exception {
        XnatProjectdata destinationProjectData = XnatProjectdata.getProjectByIDorAlias(id, user, false);
        if (destinationProjectData == null) {
            throw new Exception("Project doesn't exist.");
        }
        return destinationProjectData;
    }

    public static XnatExperimentdata getExperiment(String id, UserI user) throws Exception {
        final XnatExperimentdata experimentData = AutoXnatExperimentdata.getXnatExperimentdatasById(id, user, false);
        if (experimentData == null) {
            throw new Exception(String.format("Experiment with id %s does not exist.", id));
        }
        return experimentData;
    }

    public static ArchivableItem getArchivableItem(String id, UserI user) throws Exception {
        try {
            return getSubject(id, user);
        } catch (Exception e) {
            try {
                return getExperiment(id, user);
            } catch (Exception f) {
                final String msg = String.format("Unable to find item with id: %s", id);
                throw new Exception(msg);
            }
        }
    }

    public static void saveSharedProject(XnatExperimentdataShare pp, ArchivableItem item, UserI user, final EventDetails event) throws Exception {
        PersistentWorkflowI wrk = WorkflowUtils.buildOpenWorkflow(user, item.getItem(), event);
        EventMetaI c = wrk.buildEvent();
        PersistentWorkflowUtils.save(wrk, c);
        try {
            SaveItemHelper.authorizedSave(pp, user, false, false, c);
            PersistentWorkflowUtils.complete(wrk, c);
        } catch (Exception e) {
            PersistentWorkflowUtils.fail(wrk, c);
            throw e;
        }
    }

    public static void shareSubjectToProject(XnatProjectdata newProject, XnatSubjectdata subject, UserI user) throws Exception {
        final XnatProjectparticipant pp = new XnatProjectparticipant(user);
        pp.setProject(newProject.getId());
        pp.setSubjectId(subject.getId());
        pp.setLabel(subject.getLabel());

        final EventDetails details = EventUtils.newEventInstance(EventUtils.CATEGORY.DATA, EventUtils.TYPE.WEB_SERVICE, EventUtils.CONFIGURED_PROJECT_SHARING);
        BaseXnatSubjectdata.SaveSharedProject(pp, subject, user, details);
    }

    public static XnatExperimentdataShare shareExperimentToProject(XnatProjectdata newProject, XnatExperimentdata experiment, UserI user) throws Exception {

        final XnatExperimentdata destinationExpt = XnatExperimentdata.GetExptByProjectIdentifier(newProject.getId(), experiment.getLabel(), user, false);
        if (destinationExpt != null) {
            final String msg = String.format("Experiment %s already exists in the destination project (%s) with the same label.", experiment.getLabel(), newProject.getId());
            throw new Exception(msg);
        }

        final String newProjectId = newProject.getId();
        final XnatExperimentdataShare shared = new XnatExperimentdataShare(user);

        shared.setProject(newProjectId);
        shared.setProperty("sharing_share_xnat_experimentda_id", experiment.getId());
        shared.setLabel(experiment.getLabel());

        if (experiment instanceof XnatImagesessiondata) {
            for (XnatImagescandataI scan : ((XnatImagesessiondata) experiment).getScans_scan()) {
                shareScanToProject(user, newProject, (XnatImagescandata) scan);
            }
        }

        final EventDetails details = EventUtils.newEventInstance(EventUtils.CATEGORY.DATA, EventUtils.TYPE.WEB_SERVICE, EventUtils.CONFIGURED_PROJECT_SHARING);
        XnatUtils.saveSharedProject(shared, experiment, user, details);
        XDAT.triggerXftItemEvent(experiment.getItem(), XftItemEvent.SHARE, ImmutableMap.<String, Object>of("target", newProjectId));
        return shared;
    }

    public static void shareScanToProject(UserI user, XnatProjectdata newProject, XnatImagescandata scan) throws Exception {
        XnatImagescandataShare shared = new XnatImagescandataShare(user);
        final String newProjectId = newProject.getId();

        shared.setProject(newProjectId);
        shared.setProperty("sharing_share_xnat_imagescandat_xnat_imagescandata_id", scan.getXnatImagescandataId());
        shared.setLabel(scan.getId());
        EventDetails details = EventUtils.newEventInstance(EventUtils.CATEGORY.DATA, EventUtils.TYPE.WEB_SERVICE, EventUtils.CONFIGURED_PROJECT_SHARING);
        BaseXnatImagescandata.SaveSharedProject(shared, scan, user, details);
        XDAT.triggerXftItemEvent(scan, XftItemEvent.SHARE, ImmutableMap.<String, Object>of("target", newProjectId));
    }

    /**
     * Creates a PersistentWorkflowI for the action being performed
     *
     * @param user        - The user creating the workflow
     * @param item        - The item we are saving the workflow to.
     * @param id          - The id of the item.
     * @param project     - The project of the item
     * @param eventAction - The action we are performing
     * @return
     * @throws Exception
     */
    public static PersistentWorkflowI getWorkflow(final UserI user, final ItemI item, final String id, final String project, final String eventAction) throws Exception {
        final EventDetails details = EventUtils.newEventInstance(EventUtils.CATEGORY.DATA, EventUtils.TYPE.PROCESS, eventAction);
        return PersistentWorkflowUtils.buildOpenWorkflow(user, item.getXSIType(), id, project, details);
    }

    /**
     * Creates a workflow and does an action.
     *
     * @param user         - The user doing the action
     * @param item         - The item to save the workflow on.
     * @param workflowName - The name of the workflow.
     * @param action       - The runnable action to perform.
     * @throws Exception
     */
    public static void doActionWithWorkflow(final UserI user, final ArchivableItem item, final String workflowName, final Callable<Boolean> action) throws Exception {
        final PersistentWorkflowI wrk = XnatUtils.getWorkflow(user, item, item.getId(), item.getProject(), workflowName);
        PersistentWorkflowUtils.save(wrk, wrk.buildEvent());
        try {
            action.call();
            PersistentWorkflowUtils.complete(wrk, wrk.buildEvent());
        } catch (Exception e) {
            // Record the root cause (e.g. the schema/validation error), not this frame's generic wrapper,
            // so the workflow details and the surfaced failure name the actual reason.
            wrk.setDetails(rootCauseMessage(e));
            PersistentWorkflowUtils.fail(wrk, wrk.buildEvent());
            throw new Exception(workflowName + " Failed", e);
        }
    }

    /** The deepest throwable in {@code t}'s cause chain (cycle-safe). */
    public static Throwable rootCause(final Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root;
    }

    /**
     * The message of {@code t}'s root cause, for surfacing the real failure reason to the user rather than
     * a generic wrapper message. Falls back to the root's simple class name when its message is blank.
     */
    public static String rootCauseMessage(final Throwable t) {
        final Throwable root = rootCause(t);
        final String message = root.getMessage();
        return (message != null && !message.trim().isEmpty()) ? message : root.getClass().getSimpleName();
    }

    /**
     * Deletes the item, and it's files as the admin user. Ignores the security.prevent-data-deletion setting.
     *
     * @param item - The item being deleted.
     */
    public static void deleteItemWithoutSecurity(final ArchivableItem item) {
        final UserI user = Users.getAdminUser();
        final BaseXnatProjectdata project = AutoXnatProjectdata.getXnatProjectdatasById(item.getProject(), user, false);
        if (project == null) {
            log.error("Project {} doesn't exist", item.getProject());
            return;
        }

        final PersistentWorkflowI wrk;
        try {
            wrk = getWorkflow(user, item, item.getId(), item.getProject(), "Deleted");
        } catch (Exception e) {
            log.error("Failed to create workflow for item {}. Unable to delete", item.getItem(), e);
            return;
        }

        final EventMetaI eventMetaI = wrk.buildEvent();
        try {
            if (XnatExperimentdata.class.isAssignableFrom(item.getClass())) {
                final XnatExperimentdata experiment = ((XnatExperimentdata) item);
                experiment.deleteFiles(user, wrk.buildEvent());
            } else if (XnatSubjectdata.class.isAssignableFrom(item.getClass())) {
                final XnatSubjectdata subject = ((XnatSubjectdata) item);
                subject.deleteFiles(user, eventMetaI);
                final List<XnatSubjectassessordataI> experiments = subject.getExperiments_experiment();
                for (XnatSubjectassessordataI experiment : experiments) {
                    XnatUtils.deleteItemWithoutSecurity((XnatSubjectassessordata) experiment);
                }
            }

            SaveItemHelper.authorizedDelete(item.getItem().getCurrentDBVersion(), user, eventMetaI);
            Users.clearCache(user);
            MaterializedView.deleteByUser(user);

            XDAT.triggerXftItemEvent((BaseElement) item, DELETE, ImmutableMap.of("target", project.getId()));
            PersistentWorkflowUtils.complete(wrk, wrk.buildEvent());
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            try {
                PersistentWorkflowUtils.fail(wrk, eventMetaI);
            } catch (Exception e2) {
                log.error("Failed to update workflow for item {}", item.getItem(), e2);
            }
        }
    }
}
