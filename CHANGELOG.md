# Changelog

All notable changes to the **Batch Transfer Plugin** will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [2.0.0]

Moves the plugin to **XNAT 1.10.2.1** and **JDK 21**. Adds transfer history with Stop, History pages that don't need a project, subject rows in manifests, and checks on the labels a Reimport will give each session. 2.0.0 also includes everything listed under 1.2.0, which was not released, so a site upgrading from 1.1.1 gets both sets of changes.

### Added

- **Transfer history** — every submit is recorded as a run, with one record per item: its request (manifest values and row number included), its labels, and its outcome. Two tables are created on startup: `xhbm_batch_transfer_history` and `xhbm_batch_transfer_item`.
- **History tab** — the Batch Transfer screen has **New transfer** and **History** tabs. History lists the project's transfers as source or destination, newest first, 25 a page, with outcome counts or progress. A badge counts running transfers. Opening a transfer shows its details and custom script, and every item grouped by subject, failures first, with a filter for failed and stopped items. A submit opens History with the new transfer highlighted.
- **Stop** — the user who started a transfer, or a site admin, can stop it while it's Queued or Running. Items that haven't started are skipped and end Stopped. Items already transferring finish, and nothing is undone. Stop works on every node.
- **Export results and Export for rerun** — Export results writes every item and its outcome as CSV. Export for rerun writes a manifest of the failed and stopped sessions, to upload unchanged. It's offered only when nothing it needs is hidden from the viewer.
- **History without a project** — **Tools > Batch Transfer History** shows any logged-in user their own transfers, including those started from search results; site admins can switch to every transfer. Site admins also find every transfer on a **Transfer History** tab under **Administer > Plugin Settings > Batch Transfer**, with run details, Stop and the exports. The address names the History view (`#history`, `#history/all`, `#history/run/<tracking id>`), so a list or a transfer can be linked to.
- **Subject rows in manifests** — a row with a blank `source_session_label` covers all of the subject's image sessions. A subject with none is `no_sessions`. Rows that overlap are `duplicate_session` and block Submit. Each row carries a `row_number` as a spreadsheet numbers the file, and messages name rows by it.
- **Destination label checks before a Reimport** — on a manifest, two source subjects sent to one destination subject block Submit. These are warnings, which don't block Submit:
  - a source subject whose sessions would go to more than one destination subject;
  - a blank destination label cell;
  - a session label the destination already has.

  The Destination cells are highlighted with the reason, the summary line counts each kind, and the confirmation lists them under Check. The labels chip, the confirmation and History say where each session's labels come from: the manifest, the source, or DICOM tags.
- **Script from a file** — upload a file into the script editor, or drop it on the script panel, with Undo. An empty file, a binary file, or a file over the size limit is refused.
- **REST**:
  - `GET /xapi/transfer/history` (`all=true` for site admins);
  - `GET /xapi/transfer/history/{tracking_id}`, with `changed_since` for polling;
  - `POST /xapi/transfer/history/{tracking_id}/stop`;
  - `POST /xapi/transfer/validate/destination_labels`.

  `POST /xapi/transfer` takes an optional `source_project` and per-request `row_number`. It returns 409 for a `tracking_id` that already has a run. See [`docs/rest-api.md`](docs/rest-api.md).
- **Quick-start guide** — [`docs/quick-start.md`](docs/quick-start.md) walks through each operation and History with screenshots.

### Changed

- **Breaking: requires XNAT 1.10.2.1 and JDK 21.** The plugin is compiled to Java 21 bytecode against XNAT 1.10.2.1 and does not load on XNAT 1.9.x. XNAT 1.9 sites stay on 1.1.1.
- **DicomEdit script version 6.10 allowed** — the default allowed-version pattern is now `^6\.([0-7]|10)$`, matching DicomEdit 6.10.0 in XNAT 1.10.2.1 (6.8 and 6.9 were never script versions). An install that still has the previous default `^6\.[0-7]$` stored is updated at startup; a pattern an admin changed is kept.
- **New transfer layout** — a column of help topics on the left, which collapses to a strip and is remembered per viewer. A setup card holds the Transfer, Operation and Anonymization rows, and the cohort mode toggle moves into the Cohort builder header. A one-sentence summary names the source and destination.
- **Anonymization chip** — for Reimport, it lists the scripts that will run, in order: site-wide, custom, then destination. It's grey when no script is known to run, and warns when a custom script runs alongside another. The confirmation names the same scripts.
- **`POST /xapi/transfer` checks the batch** — it returns 400 when an item has no id, the same item is listed twice, a destination label isn't a valid XNAT label, or two Reimport items are sent to the same `destination_session_label`.
- **Default tracking IDs** have a random suffix: `batch_transfer_<millis>_<8 hex digits>`.
- **Failure messages** report the deepest non-blank message in the error's cause chain.
- **Build** — Gradle 9.4.1, Mockito 5, Lombok from the XNAT BOM.

### Fixed

- **Scripts with syntax errors are rejected** — *Validate* and submit now reject a custom script that DicomEdit can't parse, and report DicomEdit's line, column and reason (for example `Failed to parse at 2:14 due to extraneous input '='`). Before, DicomEdit only logged the parse error, so a malformed script passed validation and was submitted.
- **Repeated manifest columns are rejected** — `validate/manifest` returns 400 naming the column. Before, the last copy was used and the others were silently ignored. Notes columns (the `_` prefix) may still repeat.
- **Blank-looking manifest lines are skipped** — a line of only spaces or commas, as spreadsheets export for an emptied row, is skipped instead of being read as the header or as a row with no subject.
- **Protected projects** — `validate/manifest` returns 403 for a project whose data the caller can't read, instead of `subject_not_found` on every row.
- **Opening the screen without a project or search** shows History instead of an error page.

### Security

- **The screen's `destination` parameter** is used only when it names an existing project, and is passed on as that project's ID and escaped wherever the page uses it.

### Reimport through XNAT's importer, compared with 1.1.1

For a Reimport without a custom script or manifest labels, 2.0.0 calls XNAT's importer exactly as 1.1.1 did: the same importer, parameters, files, per-session queueing and concurrency. 2.0.0 adds two optional inputs to that call, a custom anonymization script and destination labels from a manifest, plus Stop and History tracking around it. The import itself is XNAT's, so its behaviour follows the XNAT version (1.10.2.1 rather than 1.9.3.3).

### XNAT 1.10 behavior that affects transfers

These come from XNAT 1.10 itself, not from the plugin.

- **Series import filters no longer apply to Reimport.** XNAT 1.10 removed them, so a destination that relied on a filter (for example, to drop secondary captures) now receives every series. Use the DicomEdit `reject` command in the destination's anonymization script or in a custom script instead.
- **PET/MR sessions are no longer split.** A reimported PET/MR session arrives as one session.
- **Reimported files keep their transfer syntax.** XNAT 1.10 no longer decompresses DICOM at ingest.
- **Don't enable Direct Archive append on sites that use Clone.** Clone hard-links files, so the source and the clone share them. Direct Archive append (`enableDirectArchiveAppend`, off by default) can rewrite those shared files in place, which changes every project that links to them. XNAT is deprecating Direct Archive, so the plugin does not work around it.
- **Activity tab state now lives in browser storage.** Transfers still running during the upgrade drop out of the tab; let them finish first.

### Known issues

- **On XNAT 1.10.2.1, Reimports that run at the same time soon after XNAT starts can fail.** Some sessions fail with an error such as `Invalid attribute 'x' of 'xnat:mrScanData/parameters/voxelRes'` or `Invalid attribute 'manufacturer' of 'xnat:mrSessionData/scanner'` and stay in the destination project's prearchive. The cause is a race in XNAT's schema lookups, fixed in [NrgXnat/xnat#104](https://github.com/NrgXnat/xnat/pull/104) but not yet in an XNAT release. To recover, delete the failed sessions from the prearchive and run them again; once XNAT has warmed up, they succeed.

## [1.2.0] - Unreleased

**Not released.** Its changes ship in 2.0.0, which also moves the plugin to XNAT 1.10; XNAT 1.9 sites stay on 1.1.1.

Custom per-session anonymization for **Reimport**, CSV manifests as an alternative way to pick a cohort, and explicit destination labels. Everything except the custom script runs on XNAT 1.9.3.3; the custom script needs **XNAT 1.9.3.7 or later**, which adds the importer's `Anon-Script` parameter (XNAT-8777). The plugin detects that parameter at runtime and hides the custom-script controls on older builds.

### Added

- **Custom anonymization script for Reimport** — supply a DicomEdit 6 script that is applied to every reimported session at ingest. It runs *in addition to* the destination project's anonymization; through the REST API, `anon_replace_pipeline` makes it run *instead* (the plugin also sends `PREVENT_ANON`). Requires edit access on every destination project.
- **`${csv.<column>}` placeholders** — a script can reference manifest columns; each session's row values are substituted before the script is applied. Values are restricted to a safe character set (default `A-Za-z0-9 ^_.-`), so a cell can't inject DicomEdit syntax.
- **CSV manifest cohorts for Reimport** — instead of ticking rows in the tree, upload a CSV with `source_subject_label` and `source_session_label` columns. Each row is resolved against the source project before submit and shown as matched, subject not found, or session not found; only matched rows are transferred. Columns prefixed with `_` are notes and are ignored.
- **Destination label routing** — optional `destination_subject_label` / `destination_session_label` manifest columns set where each reimported session lands. Precedence per label: manifest value, then the *preserve source label* option, then the label derived from DICOM tags.
- **Script validation and preflight** — a *Validate* control checks the script's size, version, restricted commands, placeholder binding against the manifest columns, and parse result before submit. Submit repeats the same checks server-side, so a direct API caller can't bypass them.
- **REST endpoints** — `GET /xapi/transfer/capabilities`, `POST /xapi/transfer/validate/script`, and `POST /xapi/transfer/validate/manifest`. `POST /xapi/transfer` accepts optional `anon_script` and `anon_replace_pipeline`, plus per-request `csv_values`, `destination_subject_label`, and `destination_session_label`; existing request bodies behave as before. See [`docs/rest-api.md`](docs/rest-api.md).
- **Admin settings** — alongside queue concurrency: maximum script size (default 256 KB), allowed value character set, restricted DicomEdit commands (default `mapUID`, `mapReferencedUIDs`, `lookup`, `getURL`, `alterPixels`, `newUID`, which are unsafe under parallel Reimport), allowed DicomEdit version pattern (default `6.0`–`6.7`), and maximum manifest rows (default 5000).

---

## [1.1.1]

Multi-node correctness fixes for deployments using a **shared external JMS broker**, which spreads a batch's work across nodes. Single-node deployments are unaffected.

### Fixed

- **Reimport double-archive across nodes** — the queued `Rebuild → Archive` chain ran on both nodes and raced XNAT's non-atomic archive gate (`Session already exists with matching files` / missing `.xml`). Reimport now archives inline (`action=commit`), so nothing is queued for a second node; `AA=true` is set only for auto-archive destinations.
- **Batch never reports "complete" on multi-node** — the terminal event relied on a per-JVM counter, so a batch split across nodes finished without ever reaching its total. The count now lives in a shared `BatchTransferProgress` row incremented under a `SELECT … FOR UPDATE` lock, so exactly one node fires the event once.

---

## [1.1.0]

### Added

- **Preserve source labels on Reimport** ([#10](https://github.com/NrgXnat/batch-transfer-plugin/issues/10)) — the Reimport panel now offers two checkboxes (both on by default) to preserve the source **subject label** and/or **session label**. When enabled, those XNAT labels are passed to the DICOM importer as `SUBJECT_ID` / `EXPT_LABEL` overrides so the destination uses them instead of deriving labels from DICOM tags (e.g. `PatientName` / `PatientID`).

---

## [1.0.1]

A performance and hardening release: Reimport and Clone now run in parallel through JMS queues, mixed-operation batches route correctly, the listing query is parameterized, and workflow history is consistent across all three operations. The `/xapi/transfer` request format and the Java packages are unchanged from 1.0.0.

### Performance

- **Parallel processing via JMS queues** — Reimport and Clone no longer run in a single sequential loop. Reimport is dispatched per image session and Clone per subject onto in-process (`vm://`) JMS queues; an in-memory `BatchTransferMonitor` fans the results back in and emits a single terminal *Transfer Complete* / *Warning* event once every item — across every operation in the batch — has finished.
- **Admin-tunable consumer concurrency** — new site-admin settings (and `GET` / `POST /xapi/batch_transfer/jms_queues`) set each queue's `min–max` consumer count. Defaults: **Reimport 4–8**, **Clone 1–2**; set `min = max = 1` for a serial kill-switch.
- **Faster Clone file copy** — the archive directory copy no longer opens every file to check whether it is a catalog; only catalog/XML files are read and the rest are hard-linked directly, sharply cutting per-file I/O on large multi-scan sessions.

> **Concurrency & storage note** — Reimport throughput is bound by prearchive **storage** and the anonymization pipeline, not CPU or plugin locking. On slow or bind-mounted storage, raising consumer concurrency is counter-productive (concurrent writers contend, and one slot can out-throughput many); tune concurrency up only on fast local/SAN prearchive storage. See `docs/ROADMAP.md`.

### Changed

- **Operation-aware batch routing** — a batch containing a mix of Share / Clone / Reimport items is now split by operation and each group routed to its correct path (Reimport queue, Clone queue, or the in-process sequential path for Share). Previously every item in a batch was assumed to share a single operation.
- **Unified workflow history** — the completed-action workflow recorded on the **source** item now uses one consistent past-tense label for all three operations: *"Cloned / Shared / Reimported `<item>` to project `<dest>`"*.

### Removed

- **Redundant "Files Cloned" workflow** — each cloned item previously produced a second, destination-side *"Files Cloned"* workflow on top of the source-side *"Cloned …"* workflow (four workflow DB writes per item). The destination-side record is gone (two writes now); clone provenance is still captured by the source workflow plus the preserved `original-project` field, and rollback-on-failure is unchanged.

### Fixed

- **Reimport expand arrows** — a session whose only expandable children are out-of-scope image assessors no longer shows a dead expand arrow in Reimport mode. Share / Clone are unaffected.
- **Stale anonymization status** — the destination project's anonymization status is re-queried on each selection change instead of being read once at page load, so the *About this transfer* banner always reflects the project actually selected.

### Security

- **Parameterized listing query** — the subject / experiment listing SQL behind the transfer screen is now fully parameterized (`:userId`, `:project`, `:ids`) via `NamedParameterJdbcTemplate`; request-derived values are no longer concatenated into the SQL string, closing a SQL-injection vector in the project / id filters.

---

## [1.0.0]

### Renamed — Batch Share → Batch Transfer

The plugin has been rebranded to **Batch Transfer**. The umbrella term *transfer* honestly describes all three operations the plugin runs, without conflicting with XNAT's overloaded use of "share" or dcm4che's persistent "routing" rules. The version line is reset at **1.0.0** to mark the rebranded product as a new release line — pre-rename releases ran on Batch Share `2.0.0-SNAPSHOT`.

### Operation renames

| Before | After | What it does (unchanged) |
|---|---|---|
| `Share` | `Share` (kept) | Adds data to destination without copying — XNAT's standard share relationship. |
| `Copy` | **`Clone`** | Full file duplication into destination project archive; no anonymization. |
| `Import` | **`Reimport`** | Re-ingests image sessions through destination project's anonymization pipeline. |

User-facing gerunds change accordingly: *Sharing*, *Cloning*, *Reimporting*.

### Changes

- **Action menu** — *Batch Share* link replaced by **Batch Transfer**.
- **Search-results bulk action** — *Share Data* relabeled **Batch Transfer**.
- **Screen header** — *Bulk Share — `<sourceProject>`* → *Batch Transfer — `<sourceProject>`*.
- **Operation card subtitles** — Share: *"Add to destination without copying"*; Clone: *"Full data duplication"*; Reimport: *"Re-anonymize image sessions"*.
- **About this transfer** panel — new sidebar section between the Operation cards and the Summary, containing the per-operation description and operation-aware alert banners (anon status + storage caution).
- **Status events** — *Batch Share Queued / Complete* → *Transfer Queued / Transfer Complete*; per-item event messages use the new gerunds.
- **Submit button** binds to the current selection: *Share to `<Dest>`* / *Clone to `<Dest>`* / *Reimport to `<Dest>`*.
- **Confirmation modal** — new format: *"Transfer N subjects, M sessions to `<Dest>` by `<gerund>`? This is a one-time operation."* (no one-time clause for Share).
- **Sidebar summary** — single *Items selected* count replaced by per-type rows (Subjects / Sessions / Assessors).
- **Anonymization status** — inline banner moved out of the *Destination Project* picker section and folded into the *About this transfer* panel. Severity is restricted to **warn** / **good** / **caution**; an "info" tone is no longer shown — when there's nothing actionable to flag, no banner appears.

### REST API

- **Endpoint moved** — `POST /xapi/batch_share` → `POST /xapi/transfer`.
- **Request body field renamed** — `operation` → `mode`. Permitted values: `Share | Clone | Reimport`.
- **Tracking-id prefix** — `batch_share_<ts>` → `batch_transfer_<ts>`.

External integrators must update their POST URL and rename the `operation` field to `mode` in their request payloads.

### Java package / class renames

The package was moved from `org.nrg.xnatx.plugins.batchshare` to `org.nrg.xnatx.plugins.transfer`. Public class names follow:

| Before | After |
|---|---|
| `org.nrg.xnatx.plugins.batchshare` (package) | `org.nrg.xnatx.plugins.transfer` |
| `BatchSharePlugin` | `BatchTransferPlugin` |
| `BatchShareApi` | `BatchTransferApi` |
| `BatchShareService` / `BatchShareServiceImpl` | `BatchTransferService` / `BatchTransferServiceImpl` |
| `BatchShareEvent` / `BatchShareEventListener` / `BatchShareEventTrackingLog` | `BatchTransferEvent` / `BatchTransferEventListener` / `BatchTransferEventTrackingLog` |
| `BatchShare` (model) | `BatchTransfer` |
| `ShareRequest` (per-item) | `TransferRequest` |
| `CopyOperation` (enum; constants `COPY`/`SHARE`/`IMPORT`) | `TransferMode` (enum; constants `CLONE`/`SHARE`/`REIMPORT`) |
| `BulkShareAction` | `BatchTransferAction` |
| `XDATScreen_bulk_share` (`.java` + `.vm`) | `XDATScreen_batch_transfer` |
| `actionsBox/BulkShare.vm` | `actionsBox/BatchTransfer.vm` |
| `search/plugins/pre/BulkShare.vm` | `search/plugins/pre/BatchTransfer.vm` |
| `BatchShareDirectoryCopy` | `BatchTransferDirectoryCopy` |
| `BatchShareServiceConfig` / `BatchShareServiceImplTest` (tests) | `BatchTransferServiceConfig` / `BatchTransferServiceImplTest` |

### Infra & build

- **Logback** — `batch_share_logback.xml` → `batch_transfer_logback.xml`; appender + log file renamed (`${xnat.home}/logs/batch-transfer.log`); logger name realigned with the new Java package.
- **Plugin ID** — `@XnatPlugin(value = "batchSharePlugin")` → `value = "batchTransferPlugin"`; generated descriptor is now `META-INF/xnat/batchTransferPlugin-plugin.properties`.
- **Spring `@ComponentScan`** — paths refreshed to `org.nrg.xnatx.plugins.transfer.{api,service,event}`.
- **Build artifact** — `batch-share-2.0.0-SNAPSHOT.jar` → `batch-transfer-1.0.0-SNAPSHOT.jar` (settings.gradle `rootProject.name` is now `batch-transfer`).

### Internals also renamed (frontend)

- CSS class prefix `bs-*` → `bt-*`.
- JS namespace `XNAT.plugin.batchshare` → `XNAT.plugin.batchtransfer`.
- File paths under `META-INF/resources/scripts/xnat/plugin/batchTransfer/` and `style/xnat/plugin/batchTransfer/`.

### Upgrade notes

1. Remove the old `batch-share-*.jar` from your XNAT plugins directory.
2. Drop in `batch-transfer-1.0.0-SNAPSHOT.jar`.
3. Restart XNAT.
4. Update any external REST clients to POST `{ mode: … }` to `/xapi/transfer`.
