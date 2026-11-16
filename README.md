# Batch Transfer Plugin for XNAT

Bulk data operations across XNAT projects — **Share**, **Clone**, or **Reimport** subjects, sessions, and assessors in a single batch from one screen.

New to Batch Transfer? The [quick start](docs/quick-start.md) walks through each operation and History with screenshots.

| Plugin Version                  | Target XNAT | JDK |
|---------------------------------|---|---|
| `2.0.0` | 1.10.2.1 | 21 |
| `1.1.1` | 1.9.3.3 | 8 |

> **XNAT 1.10.2.1:** Reimports that run at the same time soon after XNAT starts can fail with `Invalid attribute ...` and stay in the prearchive. This is an XNAT bug fixed in [NrgXnat/xnat#104](https://github.com/NrgXnat/xnat/pull/104); see *Known issues* under 2.0.0 in the [CHANGELOG](CHANGELOG.md).

---

## Operations

| Mode | What it does | Use it for |
|---|---|---|
| **Share** | Adds the selected data into the destination project using XNAT's standard share relationship. No files are duplicated; changes in the source project are reflected in the destination. | Cross-project visibility. |
| **Clone** | An independent copy in the destination project's archive, with the original metadata. Files are hard-linked, so the copy takes no extra disk space when it's made. **No anonymization.** | Independent, editable copies. |
| **Reimport** | Imports each image session's DICOM again into the destination through XNAT's importer (`DicomZipImporter`), so the site-wide script, an optional custom script and the destination project's script run on the way in. Image sessions only, not subjects or assessors; it sends the files in each scan's DICOM resource. | Re-anonymizing data for a new study context. |

### Cohort and anonymization options

- **CSV manifest (Reimport)** — select sessions by uploading a CSV (`source_subject_label`, `source_session_label`; a blank session label means all of the subject's sessions) instead of ticking rows. Other columns can set destination labels (`destination_subject_label`, `destination_session_label`) or feed `${csv.<column>}` placeholders in the script. A destination label in the manifest is used even when *Preserve source labels* is ticked; a blank cell falls back to that setting. Before submit, the screen checks the labels: two source subjects sent to one destination subject block Submit, and blank cells, a subject split across destination subjects, or a session label the destination already has are warnings.
- **Custom anonymization script (Reimport)** — a DicomEdit 6 script applied to each reimported session at ingest, in addition to the site-wide and destination project scripts (the REST API can also make it replace them). Needs the importer's `Anon-Script` parameter (XNAT 1.10.1 and later, or 1.9.3.7 and later on the 1.9 line); on builds without it the option is hidden. Requires edit access on the destination project.

REST details: [`docs/rest-api.md`](docs/rest-api.md).

---

## Entry points

1. **Project Actions menu** — the *Batch Transfer* item appears for project owners, edit-members, and admins.
2. **Data listings and search results** — *Options > Batch Transfer* opens the same screen with the listing's items.
3. **Tools > Batch Transfer History** — History only: the user's own transfers, for any logged-in user. Site admins also get a switch there to list every transfer.
4. **Administer > Plugin Settings > Batch Transfer > Transfer History** — every transfer on the site, for site admins.

---

## Build & test

Build and test with JDK 21 (Gradle wrapper 9.4.1). The plugin targets XNAT 1.10.2.1; for XNAT 1.9, use a 1.x release.

```bash
./gradlew build    # compile + unit tests + plugin jar
./gradlew jar      # plugin jar only
./gradlew test     # unit tests only
./gradlew dbTest   # database tests against PostgreSQL; needs Docker
```

`build` and `test` don't run the database tests, so they need no Docker. CI runs `dbTest` on every build.

Output: `build/libs/batch-transfer-<version>.jar`. Its dependencies, including `commons-csv` for manifests, come from XNAT at runtime.

## Install

Drop the jar into your XNAT instance's plugins directory (e.g. `${xnat.home}/plugins/`) and restart the XNAT server. Remove any older `batch-transfer-*.jar` first; XNAT would otherwise try to load both.


## License

See LICENSE.txt for details.
