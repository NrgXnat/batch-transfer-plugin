# Batch Transfer Plugin for XNAT

Bulk data operations across XNAT projects — **Share**, **Clone**, or **Reimport** subjects, sessions, and assessors in a single batch from one screen.

> Share and Clone operations directly replace those in the deprecated *Batch Share Plugin*. 

| Plugin Version                  | Target XNAT | JDK |
|---------------------------------|---|---|
| `1.2.0` | 1.9.3.3 (custom anonymization script: 1.9.3.7+) | 8 |
| `1.1.1` | 1.9.3.3 | 8 |

---

## Operations

| Mode | What it does | Use it for |
|---|---|---|
| **Share** | Adds the selected data into the destination project using XNAT's standard share relationship. No files are duplicated; changes in the source project are reflected in the destination. | Cross-project visibility. |
| **Clone** | Full file duplication into the destination project archive; preserves original meta-data. **No anonymization.** | Independent, editable copies. |
| **Reimport** | Re-ingests image sessions through `DicomZipImporter` → destination project's anonymization pipeline. Subjects and image assessors are not transfered; only DICOM session data. | Re-anonymizing data for a new study context. |

### Cohort and anonymization options

- **CSV manifest** — select sessions by uploading a CSV (`source_subject_label`, `source_session_label`) instead of ticking rows. For Reimport, other columns can set destination labels (`destination_subject_label`, `destination_session_label`) or feed `${csv.<column>}` placeholders in the script.
- **Custom anonymization script (Reimport)** — a DicomEdit 6 script applied to each reimported session at ingest, in addition to the destination project's anonymization (the REST API can also make it replace that anonymization). Requires XNAT 1.9.3.7 or later; on older builds the option is hidden. Requires edit access on the destination project.

REST details: [`docs/rest-api.md`](docs/rest-api.md).

---

## Entry points

1. **Project Actions menu** — the *Batch Transfer* item appears for project owners, edit-members, and admins.
2. **Search results bulk action** — legacy entry; selects items from a saved search and routes them into the same screen.

---

## Build & test

Build and test with JDK 8 (Gradle wrapper 7.6.4).

```bash
./gradlew build    # compile + tests + deployable jar
./gradlew fatJar   # deployable jar only
./gradlew test     # tests only
```

Output: `build/libs/batch-transfer-<version>.jar`. Deploy this one; the `-thin` jar the build also produces leaves out the bundled `commons-csv`.

## Install

Drop the jar into your XNAT instance's plugins directory (e.g. `${xnat.home}/plugins/`) and restart the XNAT server. If you previously ran the *Batch Share Plugin*, remove the old `batch-share-*.jar` first — XNAT will otherwise try to load both.


## License

See LICENSE.txt for details.
