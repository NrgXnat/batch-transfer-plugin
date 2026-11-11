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
 * project would exceed. These queries skip XNAT's permission checks; the transfer itself checks access per item.
 */
@Repository
public class XnatItemQueries {

    private static final String SOURCES_SQL =
            "SELECT e.id, e.project, e.label, s.label AS subject_label FROM xnat_experimentdata e "
                    + "LEFT JOIN xnat_subjectassessordata sa ON sa.id = e.id "
                    + "LEFT JOIN xnat_subjectdata s ON s.id = sa.subject_id "
                    + "WHERE e.id = ANY(?) "
                    + "UNION ALL "
                    + "SELECT s.id, s.project, s.label, s.label FROM xnat_subjectdata s WHERE s.id = ANY(?)";

    private static final String LABELS_SQL = "SELECT id, label FROM xnat_experimentdata WHERE id = ANY(?)";

    private final JdbcTemplate jdbcTemplate;

    @Autowired
    public XnatItemQueries(final JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** An item's project, its own label, and its subject's label (null for an assessor). */
    @Value
    public static class SourceItem {
        String project;
        String label;
        String subjectLabel;
    }

    /** The subjects and experiments among {@code ids}, by ID. IDs that match nothing are left out. */
    public Map<String, SourceItem> sourceItems(final Collection<String> ids) {
        final Map<String, SourceItem> found = new HashMap<>();
        if (ids.isEmpty()) {
            return found;
        }
        jdbcTemplate.query(connection -> {
            final PreparedStatement statement = connection.prepareStatement(SOURCES_SQL);
            final Array array = connection.createArrayOf("varchar", ids.toArray());
            statement.setArray(1, array);
            statement.setArray(2, array);
            return statement;
        }, row -> {
            found.put(row.getString("id"), new SourceItem(row.getString("project"), row.getString("label"),
                    row.getString("subject_label")));
        });
        return found;
    }

    /** The current labels of the experiments among {@code ids}, by ID. */
    public Map<String, String> experimentLabels(final Collection<String> ids) {
        final Map<String, String> labels = new HashMap<>();
        if (ids.isEmpty()) {
            return labels;
        }
        jdbcTemplate.query(connection -> {
            final PreparedStatement statement = connection.prepareStatement(LABELS_SQL);
            statement.setArray(1, connection.createArrayOf("varchar", ids.toArray()));
            return statement;
        }, row -> {
            labels.put(row.getString("id"), row.getString("label"));
        });
        return labels;
    }
}
