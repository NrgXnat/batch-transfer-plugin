package org.nrg.xnatx.plugins.transfer.jms.tasks.dao;

import lombok.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

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
                    + "SELECT e.id, e.project, e.label, COALESCE(pp.label, s.label) AS subject_label, "
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
                    + "SELECT s.id, s.project, COALESCE(pp.label, s.label), COALESCE(pp.label, s.label), "
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

    private final JdbcTemplate jdbcTemplate;

    @Autowired
    public XnatItemQueries(final JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * An item's owning project, its own label, its subject's label (null for an assessor), and whether it belongs to
     * or is shared into the project it was looked up in.
     */
    @Value
    public static class SourceItem {
        String  project;
        String  label;
        String  subjectLabel;
        boolean inProject;
    }

    /**
     * The subjects and experiments among {@code ids}, by ID. IDs that match nothing are left out, and so are items
     * in none of {@code readableProjects}, owned or shared (null means every project). With a {@code project}, a
     * subject's label is the one it has in that project. Without one, every label is the item's own and none is in
     * the project.
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
                    row.getString("subject_label"), row.getBoolean("in_project")));
        });
        return found;
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
