-- The columns of XNAT's own tables that the plugin's SQL reads, with the names and types XNAT 1.10 uses.
CREATE TABLE IF NOT EXISTS xnat_subjectdata (
    id      VARCHAR(255) PRIMARY KEY,
    project VARCHAR(255),
    label   VARCHAR(255)
);
CREATE TABLE IF NOT EXISTS xnat_experimentdata (
    id      VARCHAR(255) PRIMARY KEY,
    project VARCHAR(255),
    label   VARCHAR(255)
);
CREATE TABLE IF NOT EXISTS xnat_subjectassessordata (
    id         VARCHAR(255) PRIMARY KEY,
    subject_id VARCHAR(255)
);
-- A subject shared into a project, with its label there.
CREATE TABLE IF NOT EXISTS xnat_projectparticipant (
    subject_id VARCHAR(255),
    project    VARCHAR(255),
    label      VARCHAR(255)
);
-- An experiment shared into a project, with its label there.
CREATE TABLE IF NOT EXISTS xnat_experimentdata_share (
    sharing_share_xnat_experimentda_id VARCHAR(255),
    project                            VARCHAR(255),
    label                              VARCHAR(255)
);
