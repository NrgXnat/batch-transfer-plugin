# Batch Transfer Plugin for XNAT

Bulk data operations across XNAT projects — **Share**, **Clone**, or **Reimport** subjects, sessions, and assessors in a single batch from one screen.

> Share and Clone operations directly replace those in the deprecated *Batch Share Plugin*. 

| Plugin Version                  | Target XNAT | JDK |
|---------------------------------|---|---|
| `1.1.1` | 1.9.3.3 | 8 |

---

## Operations

| Mode | What it does | Use it for |
|---|---|---|
| **Share** | Adds the selected data into the destination project using XNAT's standard share relationship. No files are duplicated; changes in the source project are reflected in the destination. | Cross-project visibility. |
| **Clone** | Full file duplication into the destination project archive; preserves original meta-data. **No anonymization.** | Independent, editable copies. |
| **Reimport** | Re-ingests image sessions through `DicomZipImporter` → destination project's anonymization pipeline. Subjects and image assessors are not transfered; only DICOM session data. | Re-anonymizing data for a new study context. |


---

## Entry points

1. **Project Actions menu** — the *Batch Transfer* item appears for project owners, edit-members, and admins.
2. **Search results bulk action** — legacy entry; selects items from a saved search and routes them into the same screen.

---

## Build & test

JDK 8 only (Lombok in this Gradle 6.4.1 setup breaks on newer JDKs).

```bash
./gradlew build         # compile + tests
./gradlew jar    # jar only
./gradlew test   # tests only
```

Output: `build/libs/batch-transfer-1.1.1.jar`

## Install

Drop the jar into your XNAT instance's plugins directory (e.g. `${xnat.home}/plugins/`) and restart the XNAT server. If you previously ran the *Batch Share Plugin*, remove the old `batch-share-*.jar` first — XNAT will otherwise try to load both.


## License

See LICENSE.txt for details.
