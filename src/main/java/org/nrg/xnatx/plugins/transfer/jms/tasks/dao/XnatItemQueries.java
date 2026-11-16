package org.nrg.xnatx.plugins.transfer.jms.tasks.dao;

import lombok.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Read-only lookups on XNAT's own tables, by ID. The IDs go in as one array parameter, so a run of any size is one
 * statement: PostgreSQL caps a statement's bind parameters at a 16-bit count, which an {@code IN} list over a large
 * project would exceed. Beyond the project-level read filter in {@link #sourceItems}, these queries skip XNAT's
 * permission checks; the transfer itself checks access per item.
 */
@Repository
public class XnatItemQueries {

    // A subject shared into the project may have its own label there (xnat_projectparticipant). Experiments keep
    // their own label: a manifest names a session by it.
    private static final String SOURCES_SQL =
            "WITH src AS (SELECT CAST(? AS VARCHAR) AS project, CAST(? AS VARCHAR[]) AS readable) "
                    + "SELECT e.id, e.project, e.label, s.label AS subject_label, e.label AS label_in_project, "
                    + "COALESCE(pp.label, s.label) AS subject_label_in_project, "
                    + "(e.project = src.project OR EXISTS (SELECT 1 FROM xnat_experimentdata_share sh "
                    + "WHERE sh.sharing_share_xnat_experimentda_id = e.id AND sh.project = src.project)) AS in_project "
                    + "FROM src CROSS JOIN xnat_experimentdata e "
                    + "LEFT JOIN xnat_subjectassessordata sa ON sa.id = e.id "
                    + "LEFT JOIN xnat_subjectdata s ON s.id = sa.subject_id "
                    + "LEFT JOIN xnat_projectparticipant pp ON pp.subject_id = s.id AND pp.project = src.project "
                    + "WHERE e.id = ANY(?) AND (src.readable IS NULL OR e.project = ANY(src.readable) "
                    + "OR EXISTS (SELECT 1 FROM xnat_experimentdata_share r "
                    + "WHERE r.sharing_share_xnat_experimentda_id = e.id AND r.project = ANY(src.readable))) "
                    + "UNION ALL "
                    + "SELECT s.id, s.project, s.label, s.label, COALESCE(pp.label, s.label), COALESCE(pp.label, s.label), "
                    + "(s.project = src.project OR pp.subject_id IS NOT NULL) "
                    + "FROM src CROSS JOIN xnat_subjectdata s "
                    + "LEFT JOIN xnat_projectparticipant pp ON pp.subject_id = s.id AND pp.project = src.project "
                    + "WHERE s.id = ANY(?) AND (src.readable IS NULL OR s.project = ANY(src.readable) "
                    + "OR EXISTS (SELECT 1 FROM xnat_projectparticipant r "
                    + "WHERE r.subject_id = s.id AND r.project = ANY(src.readable)))";

    private static final String LABELS_SQL =
            "SELECT id, label FROM xnat_experimentdata WHERE id = ANY(?) "
                    + "UNION ALL "
                    + "SELECT id, label FROM xnat_subjectdata WHERE id = ANY(?)";

    // An experiment label is taken in a project when an experiment there has it, or an experiment shared into the
    // project has it as its label there: the two places XNAT looks a label up (GetExptByProjectIdentifier).
    private static final String TAKEN_LABELS_SQL =
            "SELECT label FROM xnat_experimentdata WHERE project = ? AND label = ANY(?) "
                    + "UNION "
                    + "SELECT label FROM xnat_experimentdata_share WHERE project = ? AND label = ANY(?)";

    private final JdbcTemplate jdbcTemplate;

    @Autowired
    public XnatItemQueries(final JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * An item's owning project, its own label and its subject's (null for an assessor), whether it belongs to or is
     * shared into the project it was looked up in, and its labels as that project has them.
     */
    @Value
    public static class SourceItem {
        String  project;
        String  label;
        String  subjectLabel;
        boolean inProject;
        String  labelInProject;
        String  subjectLabelInProject;
    }

    /**
     * The subjects and experiments among {@code ids}, by ID. IDs that match nothing are left out, and so are items
     * in none of {@code readableProjects}, owned or shared (null means every project). With a {@code project}, the
     * labels in it are a subject's label there; without one, they are the item's own and nothing is in it.
     */
    public Map<String, SourceItem> sourceItems(final Collection<String> ids, final String project,
                                               final Collection<String> readableProjects) {
        final Map<String, SourceItem> found = new HashMap<>();
        if (ids.isEmpty()) {
            return found;
        }
        jdbcTemplate.query(connection -> {
            final PreparedStatement statement = connection.prepareStatement(SOURCES_SQL);
            final Array array = connection.createArrayOf("varchar", ids.toArray());
            statement.setString(1, project);
            statement.setArray(2, readableProjects == null ? null
                    : connection.createArrayOf("varchar", readableProjects.toArray()));
            statement.setArray(3, array);
            statement.setArray(4, array);
            return statement;
        }, row -> {
            found.put(row.getString("id"), new SourceItem(row.getString("project"), row.getString("label"),
                    row.getString("subject_label"), row.getBoolean("in_project"), row.getString("label_in_project"),
                    row.getString("subject_label_in_project")));
        });
        return found;
    }

    /**
     * The {@code labels} that an experiment in {@code project} already has, owned or shared in. Matching is exact,
     * as XNAT's own lookup is.
     */
    public Set<String> takenExperimentLabels(final String project, final Collection<String> labels) {
        final Set<String> taken = new HashSet<>();
        if (labels.isEmpty()) {
            return taken;
        }
        jdbcTemplate.query(connection -> {
            final PreparedStatement statement = connection.prepareStatement(TAKEN_LABELS_SQL);
            final Array array = connection.createArrayOf("varchar", labels.toArray());
            statement.setString(1, project);
            statement.setArray(2, array);
            statement.setString(3, project);
            statement.setArray(4, array);
            return statement;
        }, row -> {
            taken.add(row.getString("label"));
        });
        return taken;
    }

    /** The current labels of the subjects and experiments among {@code ids}, by ID. */
    public Map<String, String> itemLabels(final Collection<String> ids) {
        final Map<String, String> labels = new HashMap<>();
        if (ids.isEmpty()) {
            return labels;
        }
        jdbcTemplate.query(connection -> {
            final PreparedStatement statement = connection.prepareStatement(LABELS_SQL);
            final Array array = connection.createArrayOf("varchar", ids.toArray());
            statement.setArray(1, array);
            statement.setArray(2, array);
            return statement;
        }, row -> {
            labels.put(row.getString("id"), row.getString("label"));
        });
        return labels;
    }
}
