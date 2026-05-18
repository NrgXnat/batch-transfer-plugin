// Copyright 2021 Radiologics, INC
// Developer: Timothy Olsen <tim@radiologics.com

package org.nrg.xnat.turbine.modules.screens;

import org.nrg.xnatx.plugins.transfer.model.TransferMode;
import org.nrg.xnatx.plugins.transfer.model.Fields;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.turbine.util.RunData;
import org.apache.velocity.context.Context;
import org.nrg.action.ClientException;
import org.nrg.xdat.XDAT;
import org.nrg.xdat.display.DisplayField;
import org.nrg.xdat.display.DisplayFieldElement;
import org.nrg.xdat.om.*;
import org.nrg.xdat.schema.SchemaElement;
import org.nrg.xdat.search.DisplaySearch;
import org.nrg.xdat.security.XDATUser;
import org.nrg.xdat.security.helpers.Features;
import org.nrg.xdat.turbine.modules.screens.SecureScreen;
import org.nrg.xdat.turbine.utils.TurbineUtils;
import org.nrg.xft.XFTTable;
import org.nrg.xft.security.UserI;
import org.restlet.data.Status;

import java.util.*;
import java.util.stream.Collectors;

@SuppressWarnings("unused")
@Slf4j
public class XDATScreen_batch_transfer extends SecureScreen {
    private final List<String> nonSharingProjects = new ArrayList<>();
    private final List<String> nonCopyingProjects = new ArrayList<>();
    private static final String FIELD_ID = "BATCH_TRANSFER_ID";

    private static final String READABLE_SUBJECTS = "SELECT field_value,read_element,field,element_name,grp.tag, xdat_user_id\n" +
            "FROM xdat_field_mapping xfm\n" +
            "LEFT JOIN xdat_field_mapping_set xfms ON xfm.xdat_field_mapping_set_xdat_field_mapping_set_id=xfms.xdat_field_mapping_set_id\n" +
            "LEFT JOIN xdat_element_access xea ON xfms.permissions_allow_set_xdat_elem_xdat_element_access_id=xea.xdat_element_access_id\n" +
            "LEFT JOIN xdat_usergroup grp ON xea.xdat_usergroup_xdat_usergroup_id=grp.xdat_usergroup_id\n" +
            "LEFT JOIN xdat_user_groupid map ON grp.id=map.groupid\n" +
            "LEFT JOIN xdat_user u ON map.groups_groupid_xdat_user_xdat_user_id=u.xdat_user_id\n";

    private static final String READABLE_EXPERIMENTS = "FROM xdat_user_groupid gid\n" +
            "LEFT JOIN xdat_usergroup grp ON gid.groupid=grp.id AND gid.groups_groupid_xdat_user_xdat_user_id={USER_ID}\n" +
            "LEFT JOIN xdat_element_access xea ON grp.xdat_usergroup_id=xea.xdat_usergroup_xdat_usergroup_id AND xea.element_name NOT IN ('xnat:projectData','xnat:subjectData')\n" +
            "LEFT JOIN xdat_field_mapping_set fms ON xea.xdat_element_access_id=fms.permissions_allow_set_xdat_elem_xdat_element_access_id\n" +
            "LEFT JOIN xdat_field_mapping xfm ON fms.xdat_field_mapping_set_id=xfm.xdat_field_mapping_set_xdat_field_mapping_set_id AND xfm.read_element=1\n" +
            "LEFT JOIN xdat_meta_element xme ON xea.element_name=xme.element_name\n";




    @Override
    protected void doBuildTemplate(RunData data, Context context) throws Exception {
        UserI user = XDAT.getUserDetails();
        if (user == null) {
            throw new ClientException(Status.CLIENT_ERROR_FORBIDDEN, "Forbidden");
        }

        // Check for a preset destination project passed via request parameter
        String presetDestination = data.getParameters().getString("destination");
        if (StringUtils.isNotBlank(presetDestination)) {
            context.put("presetDestination", presetDestination);
        }

        // Two entry points:
        //  (1) sourceProject param — launched from a project's Actions menu; all of the project's data is the source.
        //  (2) default — DisplaySearch in the session from a bulk action on a search results table.
        String sourceProject = data.getParameters().getString("sourceProject");
        String column;
        List<String> itemIds;
        DisplaySearch search = null;

        if (StringUtils.isNotBlank(sourceProject)) {
            // Validate project access; getProjectByIDorAlias returns null if the user lacks read permission.
            XnatProjectdata proj = XnatProjectdata.getProjectByIDorAlias(sourceProject, user, false);
            if (proj == null) {
                context.put("msg", "Project not found or access denied.");
                return;
            }
            String validatedProjectId = proj.getId();
            context.put("searchType", "subject");
            context.put("projectContext", validatedProjectId);
            column = "subj.id";
            itemIds = getSubjectIdsInProject(validatedProjectId, user);
            if (itemIds.isEmpty()) {
                context.put("sharingMsg", "None of the requested data is available for sharing or cloning.");
                return;
            }
        } else {
            // retrieve passed search object
            search = TurbineUtils.getSearch(data);
            SchemaElement rootElement = search.getRootElement();
            String xsiType = rootElement.getFullXMLName();
            addShareIdDisplayField(rootElement, search);
            search.setPagingOn(false);

            // Build the WHERE Clause based on what type of query we are performing. (Subject, Subject Assessor, or Image Assessor)
            // Also, set the searchType, we need this in the velocity to determine if a user is allowed to delete an item. (see ItemContainer.canDelete() below)
            if (xsiType.equals(XnatSubjectdata.SCHEMA_ELEMENT_NAME)) {
                context.put("searchType", "subject");
                column = "subj.id";
            } else if (rootElement.getGenericXFTElement().instanceOf(XnatSubjectassessordata.SCHEMA_ELEMENT_NAME)) {
                context.put("searchType", "subject_assessor");
                column = "SADS.id";
            } else if (rootElement.getGenericXFTElement().instanceOf(XnatImageassessordata.SCHEMA_ELEMENT_NAME)) {
                context.put("searchType", "image_assessor");
                column = "IAD.id";
            } else {
                context.put("msg", "None of the requested data is available for sharing.");
                return;
            }

            // Load search results into a table and extract the ID column
            XFTTable table = (org.nrg.xft.XFTTable) search.execute(user.getLogin());
            itemIds = table.convertColumnToArrayList(FIELD_ID);
        }

        // Build the query to retrieve information about the items we want listed.
        String query = "SELECT DISTINCT ON (SUBJ.ID, SADS.ID, IAD.ID) secondary_id AS project_label,subj.id AS subject_id, subj.label AS subject_label, subj.project AS subject_project, SADS.id AS session_id, SADS.label AS session_label, SADS.project AS session_project, SADS.date AS session_expt_date, SADS.element_name as session_element, IAD.id AS assessor_id, IAD.label AS assessor_label, IAD.project AS assessor_project, IAD.date AS assess_expt_date, IAD.element_name AS assessor_element\n" +
                "FROM (\n" +
                "SELECT subj.id, subj.label, subj.project\n" +
                "FROM xnat_subjectData subj\n" +
                "JOIN (\n" +
                READABLE_SUBJECTS +
                "WHERE xdat_user_id={USER_ID} AND read_element=1 AND element_name='xnat:subjectData' AND field='xnat:subjectData/project') OWNED ON subj.project=OWNED.tag\n" +
                "UNION\n" +
                "SELECT subj.id, pp.label, subj.project\n" +
                "FROM xnat_subjectData subj\n" +
                " JOIN xnat_projectParticipant pp ON subj.id=pp.subject_id\n" +
                "JOIN (\n" +
                READABLE_SUBJECTS +
                "WHERE xdat_user_id={USER_ID} AND read_element=1 AND element_name='xnat:subjectData' AND field='xnat:subjectData/sharing/share/project' ) SHARED ON pp.project=SHARED.tag\n" +
                ")  SUBJ\n" +
                "LEFT JOIN (\n" +
                " SELECT expt.id, expt.label, expt.project, sad.subject_id, expt.date, xme.element_name\n" +
                READABLE_EXPERIMENTS +
                "LEFT JOIN xnat_experimentData expt ON xme.xdat_meta_element_id=expt.extension AND xfm.field=xea.element_name || '/project' AND xfm.field_value=expt.project\n" +
                "JOIN xnat_subjectAssessorData sad ON expt.id=sad.id\n" +
                "UNION\n" +
                " SELECT shared.id, shared.label, proj, sad.subject_id, shared.date, xme.element_name\n" +
                READABLE_EXPERIMENTS +
                "LEFT JOIN (\n" +
                "SELECT expt.extension, expt.id, shr.project, shr.label, expt.date, expt.project AS proj FROM\n" +
                "xnat_experimentData expt\n" +
                "LEFT JOIN xnat_experimentData_share shr ON expt.id=shr.sharing_share_xnat_experimentda_id\n" +
                ")shared ON xme.xdat_meta_element_id=shared.extension AND xfm.field=xea.element_name || '/sharing/share/project' AND xfm.field_value=shared.project\n" +
                "JOIN xnat_subjectAssessorData sad ON shared.id=sad.id\n" +
                ") SADS ON subj.id=SADS.subject_id\n" +
                "LEFT JOIN (\n" +
                " SELECT expt.id, expt.label, expt.project, sad.imagesession_id, expt.date, xme.element_name\n" +
                READABLE_EXPERIMENTS +
                "LEFT JOIN xnat_experimentData expt ON xme.xdat_meta_element_id=expt.extension AND xfm.field=xea.element_name || '/project' AND xfm.field_value=expt.project\n" +
                "JOIN xnat_imageAssessorData sad ON expt.id=sad.id\n" +
                "UNION\n" +
                " SELECT shared.id, shared.label, proj, sad.imagesession_id, shared.date, xme.element_name\n" +
                READABLE_EXPERIMENTS +
                "LEFT JOIN (\n" +
                "SELECT expt.extension, expt.id, shr.project, shr.label, expt.date, expt.project AS proj FROM\n" +
                "xnat_experimentData expt\n" +
                "LEFT JOIN xnat_experimentData_share shr ON expt.id=shr.sharing_share_xnat_experimentda_id\n" +
                ")shared ON xme.xdat_meta_element_id=shared.extension AND xfm.field=xea.element_name || '/sharing/share/project' AND xfm.field_value=shared.project\n" +
                "JOIN xnat_imageAssessorData sad ON shared.id=sad.id\n" +
                ") IAD ON SADS.id=IAD.imagesession_id\n" +
                "LEFT JOIN xnat_projectData ON subj.project=xnat_projectData.id " +
                "WHERE " +
                column +
                " IN ('" +
                buildWhereClause(itemIds) +
                "');";

        query = query.replace("{USER_ID}", ((XDATUser) user).getXdatUserId().toString());

        // Execute the query to get a list of experiments
        XFTTable experiments = XFTTable.Execute(query, user.getDBName(), user.getUsername());

        // Insert the items into the context.
        Map<String, ItemContainer> sharingItems = getItems(experiments, search, TransferMode.SHARE);

        Map<String, ItemContainer> copyingItems = getItems(experiments, search, TransferMode.CLONE);

        if (sharingItems.isEmpty() && copyingItems.isEmpty()) {
            context.put("sharingMsg", "None of the requested data is available for sharing or cloning.");
            return;
        }

        if (sharingItems.isEmpty()) {
            context.put("sharingMsg", "There is no data available to share. Please try another operation to proceed.");
        } else if (!nonSharingProjects.isEmpty()) {
            context.put("sharingMsg", "Some of the data requested is not available for sharing and has been excluded from the table below.");
        }

        if (copyingItems.isEmpty()) {
            context.put("copyingMsg", "There is no data available to clone. Please try another operation to proceed.");
        } else if (!nonCopyingProjects.isEmpty()) {
            context.put("copyingMsg", "Some of the data requested is not available for cloning and has been excluded from the table below.");
        }
        context.put("sharingItems", sharingItems);
        context.put("copyingItems", copyingItems);
        context.put("turbineUtils", TurbineUtils.GetInstance());
    }

    private void addShareIdDisplayField(SchemaElement rootElement, DisplaySearch search) {
        DisplayFieldElement element = new DisplayFieldElement();
        element.setName("Field1");
        element.setSchemaElementName(rootElement.getFullXMLName() + ".ID");

        DisplayField displayField = new DisplayField(rootElement.getDisplay());
        displayField.setId(FIELD_ID);
        displayField.setHeader("ID");
        displayField.addDisplayFieldElement(element);

        search.addDisplayField(displayField);
    }

    /**
     * Function takes a XFTtable and converts it into a Hashtable of ItemContainers.
     * @param t - XFTTable
     * @param search - DisplaySearch
     * @return Hashtable<String, ItemContainer>
     */
    private Map<String, ItemContainer> getItems(XFTTable t, DisplaySearch search, TransferMode operation) {
        Map<String, ItemContainer> items = new HashMap<>();

        // For each experiment in the hashtable.
        for(Hashtable exp : t.rowHashs()){

            String project = (String) exp.get("subject_project");
            String subject_id = (String) exp.get("subject_id");
            if (project == null || subject_id == null) {
                continue;
            }

            if (operation == TransferMode.SHARE) {
                if(!Features.checkRestrictedFeature(XDAT.getUserDetails(), project, Features.PROJECT_SHARING_FEATURE)){
                    nonSharingProjects.add(project);
                    continue;
                }
            } else {
                if(!Features.checkRestrictedFeature(XDAT.getUserDetails(), project, Fields.PROJECT_COPYING_FEATURE)){
                    nonCopyingProjects.add(project);
                    continue;
                }
            }

            ItemContainer projectContainer = items.get(project);
            if (projectContainer == null) {
                projectContainer = new ItemContainer(project, (String) exp.get("project_label"),
                        project, XnatProjectdata.SCHEMA_ELEMENT_NAME);
                items.put(project, projectContainer);
            }

            // Get the container for the subject if it exists.
            Map<String, ItemContainer> subjects = projectContainer.getChildren();
            ItemContainer subj = subjects.get(subject_id);
            if (subj == null) {
                subj = new ItemContainer(subject_id, (String) exp.get("subject_label"), project,
                        XnatSubjectdata.SCHEMA_ELEMENT_NAME);
                projectContainer.addChild(subj);
            }

            String session_id = (String) exp.get("session_id");
            if (session_id != null) {
                // Get a list of subject assessors
                Map<String, ItemContainer> subjAssessors = subj.getChildren();
                ItemContainer experiment = subjAssessors.get(session_id);
                if (experiment == null){
                    // If the experiment doesn't exist create it.
                    experiment = new ItemContainer(session_id, (String) exp.get("session_label"),
                            (String) exp.get("session_project"), (String) exp.get("session_element"));
                    subj.addChild(experiment);
                }

                // Get a list of image assessors for the experiment
                Map<String, ItemContainer> imgAssessors = experiment.getChildren();
                String assess_id = (String) exp.get("assessor_id");
                if (assess_id != null && !imgAssessors.containsKey(assess_id)) {
                    //If the assessor doesn't exist, create it, add it to the list of assessors and attach it to the experiment.
                    experiment.addChild(new ItemContainer(assess_id, (String) exp.get("assessor_label"),
                            (String) exp.get("assessor_project"), (String) exp.get("assessor_element")));
                }
            }
        }

        return sortItemContainersByLabel(items);
    }

    /**
     * Function builds a comma delimited string of id's to be
     * inserted in the where clause of a sql query.
     * @param ids - a list of ids
     * @return a comma delimited string of id's
     */
    private String buildWhereClause(List<String> ids){
        return StringUtils.join(ids, "','");
    }

    /**
     * Returns all subject IDs belonging to the given project, including subjects shared into it.
     * Permission filtering is applied downstream by the main hierarchy query's READABLE_* joins.
     * @param projectId - validated project id (already checked via XnatProjectdata.getProjectByIDorAlias)
     * @param user - current user
     * @return list of subject ids
     */
    private List<String> getSubjectIdsInProject(String projectId, UserI user) throws Exception {
        String safeProjectId = projectId.replaceAll("[^A-Za-z0-9_\\-]", "");
        String query = "SELECT DISTINCT subj.id AS id FROM xnat_subjectData subj " +
                "LEFT JOIN xnat_projectParticipant pp ON subj.id=pp.subject_id " +
                "WHERE subj.project = '" + safeProjectId + "' OR pp.project = '" + safeProjectId + "';";
        XFTTable table = XFTTable.Execute(query, user.getDBName(), user.getUsername());
        return table.convertColumnToArrayList("id");
    }

    private static Map<String, ItemContainer> sortItemContainersByLabel(Map<String, ItemContainer> items) {
        items.forEach((key, value) -> value.sortChildren());
        return items.entrySet().stream()
                .sorted(Comparator.comparing(e -> e.getValue().getLabel(), String.CASE_INSENSITIVE_ORDER))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (e1, e2) -> e1, LinkedHashMap::new));
    }

    /**
     * Class holds information on an item (subject, experiment, image assessors)
     * It has helper methods to make it easier to access the information from the velocity.
     */
    public static class ItemContainer {
        private final String id, label, project, xsiType;
        private Map<String, ItemContainer> children = new HashMap<>();

        public ItemContainer(String id, String label, String project, String xsiType) {
            this.id       = id;
            this.label    = label;
            this.project  = project;
            this.xsiType  = xsiType;
        }

        public Map<String, ItemContainer> getChildren() {
            return this.children;
        }

        public void addChild(ItemContainer child) {
            this.children.put(child.getId(), child);
        }

        public void sortChildren() {
            this.children = sortItemContainersByLabel(this.children);
        }

        public String getId() {
            return this.id;
        }

        public String getLabel() {
            return this.label;
        }

        public String getProject() {
            return this.project;
        }

        public String getXsiType() {
            return this.xsiType;
        }
    }
}
