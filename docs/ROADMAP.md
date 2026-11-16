# Batch Transfer Plugin — Roadmap

**Plugin Version**: 2.0.0
**Target XNAT**: 1.10.2.1 (JDK 21)
**Last Updated**: 2026-11-12

The Batch Transfer Plugin enables bulk data operations across XNAT projects. Users can Share, Clone, or Reimport subjects, sessions, and assessors in batch from a single interface. **Share** adds data into a destination project without copying (XNAT's standard sharing relationship); **Clone** duplicates data into the destination, producing an independent editable copy; **Reimport** re-ingests image sessions through the destination project's anonymization pipeline.

---


## Completed

## [1.2.0]

Cohort building and custom anonymization for Reimport. REST details: [rest-api.md](rest-api.md).

- **Manifest CSV cohorts** — pick sessions by uploading a CSV instead of ticking rows; each row is resolved on upload and shown as matched / not found before submit
- **Custom DicomEdit anonymization for Reimport** — a one-off script applied at ingest on top of the destination project's anonymization, with per-session `${csv.*}` values substituted from the manifest. Rides the importer's `Anon-Script` parameter (XNAT 1.9.3.7+) and is feature-detected, so the option is hidden on builds without it
- **Destination label routing** — `destination_subject_label` / `destination_session_label` manifest columns set where each reimported session lands
- **Validation and preflight** — script size, version, restricted commands, placeholder binding, and value charset checked before submit and enforced again at submit
- **UI** — horizontal band layout (Project → Operation → Cohort → Anonymization → Summary), page-level scrolling with a pinned rail and action bar, inline-styled confirmation dialog

## [1.1.1]

Multi-node correctness for deployments using a shared external JMS broker (single-node unaffected).

- **Reimport double-archive across nodes** — the queued `Rebuild → Archive` chain ran on both nodes and raced XNAT's non-atomic archive gate; Reimport now archives inline (`action=commit`), with `AA=true` only for auto-archive destinations
- **Batch never reports "complete" on multi-node** — the per-JVM completion counter is replaced by a shared `BatchTransferProgress` row incremented under `SELECT … FOR UPDATE`, so exactly one node fires the terminal event once
- **Root-cause surfaced to users** — item failures now show the underlying reason (e.g. schema-validation errors) in the activity monitor and workflow details instead of a generic wrapper


## [1.1.0]

### Added

- **Preserve source labels on Reimport** ([#10](https://github.com/NrgXnat/batch-transfer-plugin/issues/10)) — the Reimport panel now offers two checkboxes (both on by default) to preserve the source **subject label** and/or **session label**. When enabled, those XNAT labels are passed to the DICOM importer as `subject_ID` / `EXPT_LABEL` overrides so the destination uses them instead of deriving labels from DICOM tags (e.g. `PatientName` / `PatientID`).

## 1.0.1 — Performance & hardening
- **Parallel processing via JMS queues** — Reimport (per session) and Clone (per subject) dispatched onto in-process (`vm://`) JMS queues instead of a single sequential loop; in-memory `BatchTransferMonitor` fan-in emits one terminal event per batch
- **Admin-tunable queue concurrency** — site-admin settings + `GET`/`POST /xapi/batch_transfer/jms_queues`; defaults Reimport 4–8, Clone 1–2 (`min = max = 1` for serial)
- **Operation-aware batch routing** — mixed Share/Clone/Reimport batches split by operation and routed to the correct queue or the sequential path
- **Faster Clone copy** — archive copy reads only catalog/XML files and hard-links the rest (no longer opens every file)
- **Unified workflow history** — consistent past-tense source-item labels ("Cloned/Shared/Reimported `<item>` to project `<dest>`"); removed the redundant destination-side "Files Cloned" workflow (4 → 2 workflow writes per cloned item)
- **Parameterized listing query** — subject/experiment listing SQL fully parameterized (`:userId`, `:project`, `:ids`); closes a SQL-injection vector

## 1.0.0

### UI Modernization
- Two-panel layout replacing legacy jsTree-based form (sidebar + data table)
- Hierarchical data table with expand/collapse, search filtering, and bulk select
- Destination project anonymization status indicators surfaced in the *About this transfer* panel
- *About this transfer* sidebar panel with per-operation description + operation-aware alert banners (anon status + storage caution)

### Operations & Workflow
- **Share**: Adds data to destination without copying (existing)
- **Clone**: Full data duplication with resource copying and path remapping
- **Reimport**: DICOM session import with destination project anonymization script
- Reimport mode restrictions (subjects disabled, only SessionData assessors selectable)
- Launch Batch Transfer from source project Action Menu
  - Include all XNAT experiment data types

### Backend & API
- Reimport operation using DICOM zip uploader - I/O is streamed directly through zip uploader without writing files to disk
- Anonymization status check endpoint integration
- Populate Batch Transfer with all project subjects and assessors
- REST endpoint surfaced under `/xapi/transfer` with request body `{ requests: [{ id, mode, destination_project }], tracking_id }` — `mode` enumerates `Share` / `Clone` / `Reimport`

### Testing & Release
- XNAT 1.9 compatibility

---

## Planned

### Transfer history

Follows the 1.2.0 cohort-building and custom-anonymization work. Deliberately does *not* adopt conduit's job engine (no job/row tables, dashboard, or per-row retry).

- **Runs list and run detail** — a filterable list of past batches with per-item detail, backed by a single **`batch_transfer_history`** header table (structured / filterable / permanent) plus **`jobid`-grouped workflows** for per-item detail (requires stamping `jobid = tracking_id` on each item's workflow); live progress stays on `EventTrackingData`. One run-header table, **no per-row table**.
- **Two top tabs** — *New transfer* (landing) / *Transfer history*.
- **Script audit** — record a SHA-256 of the custom anonymization script on each Reimport workflow and show it in run detail.
- **Possible REST additions** — `GET /xapi/transfer/history`, `GET /xapi/transfer/history/{tracking_id}`, and `POST /xapi/transfer/history/{tracking_id}/retry-failed` (seed a new run from a run's failed items).


### Manifest profile view (2.1.0)
- A data profile of an uploaded manifest above the CSV list: count tiles (sessions, rows that need fixing, skipped, warnings, worth a look), a sessions-per-subject histogram, and a per-column summary (filled, distinct, and flags such as values outside the allowed characters or a numeric outlier). Every number filters the list. Client-side; builds on 2.0.0's summary + pickers pane.

### Operations & Workflow
- Expand Reimport type support to include scans-based operations
  - Appending scans to an existing session is what XNAT's *Upload Scans* action does. [NrgXnat/xnat#156](https://github.com/NrgXnat/xnat/pull/156) (open, targets `develop`) moves that action onto the Compressed Uploader, which archives into the prefilled session with `prearchive_code=1` and `overwrite=append`. It also fixes `/data/services/import` rejecting the valid `overwrite` values `append` and `delete` for archive imports, which fail on 1.10.2.1.
  - The plugin calls `DicomZipImporter` in-process, so that REST validation bug doesn't block a scan-level Reimport. The import should still use the same append semantics.
  - XNAT only offers *Upload Scans* when the site's *Upload Additional Scans* setting is on (off by default); decide whether scan-level transfer should honor it.
- Implement UX cohort building filters for large (>10k+) datasets where the select/deselect item workflow would be tedious 
- Offer the manifest CSV cohort only when Batch Transfer is opened from a project's Actions menu; from search results, the selection tree is the only way to pick a cohort. Today CSV mode turns on for search results that all come from one project, and the manifest is then matched against the whole project, not the search results.
- Support multiple source projects in both cohort builders:
  - Manifest CSV: a manifest's labels are matched within one source project today, so CSV upload is disabled when the search results span several projects.
  - Selection tree: when the source spans several projects, group sessions by project or show each row's project label. Today subjects from every project are listed together with nothing to tell them apart; each row carries `data-project`, but the tree doesn't show it.
- Launch Advanced Search from destination project Action Menu 
  - Include all XNAT experiment data types
- Computed size estimate in the operation-detail panel — replace the static duplication warning with a real byte count for the current selection (e.g. "≈ 4.2 GB will be duplicated to *DestProject*"). Requires either a new XAPI endpoint that walks the archive, or per-row `data-size` attributes emitted by `XDATScreen_batch_transfer.java`, plus rollup logic in `batchTransfer.js`.

### Backend & API
- **Reimport every DICOM scan resource, not only folders named `DICOM`.** Reimport sends a session's files only from scan folders named `DICOM` (`StreamingZipFileWriter` in `BatchTransferServiceImpl`), and the manifest's file count (`PreflightResolver`) uses the same rule. XNAT stores Secondary Capture images in a scan resource labelled `secondary`, with format `DICOM`, so Reimport leaves them out. A session made only of them, such as a PET saved as Secondary Capture, fails with "No DICOM files found". A session that also has them, such as an MR with a dose-report image, arrives without those scans and nothing reports it. Present since 1.0.0.
  - **Fix:** choose files by each scan resource's format, not its folder name. Read the session's scan resources (`XnatImagescandata.getFile()`), keep those whose `format` is `DICOM`, and walk each one's catalog directory. Keep the folder-name rule as a fallback for resources with no format recorded. Session-level resources and assessors stay out, as now.
  - Use one helper for both the Reimport zip and the manifest file count, so the count shown before submit matches what is sent.
  - Decide what to do when two DICOM-format resources in one session hold the same instances: send both and let the importer resolve them, or skip repeats by SOP Instance UID.
  - **Tests:** a unit test on a session folder with `DICOM`, `secondary` (format `DICOM`) and `NIFTI` scan resources that expects files from the first two only. Live: reimport an xnat-synth-dicom PET session (Secondary Capture) and an MR session with a Secondary Capture scan, and check that every scan arrives.
- BatchTransferEvent refactor to limit event/DB traffic (partially addressed in 1.0.1-RC: in-memory `BatchTransferMonitor` fan-in collapses per-batch terminal events to one)
- Rethink BatchTransferEvent status to reflect import process as reflected in prearch import.
  - "Transfer Queued (%s items)" and "Transfer Complete" do not yet reflect true Share/Clone/Reimport status nuance
- **Migrate progress tracking from `event_tracking` to the workflow table** — each item already creates a persistent workflow *and* runs a parallel `event_tracking` stream; the redundancy is what forced 1.1.1's `BatchTransferProgress` counter. Deriving batch status by querying `wrk_workflowData` (as BulkLaunch's `WorkflowMonitorApi`/`WorkflowRepository` do) is inherently multi-node-safe and would let us delete that counter. Cost: tag each item's workflow with the batch `tracking_id` (workflows otherwise group by project/user/time); handle reimport items that fail before their workflow is created mid-`importExperiment` (the reason the counter was chosen); and replace the activity-tab UI with a workflow-table view. Ref: 1.1.1 PR discussion.
- REST API extension to support directional sharing workflow (transfer-to vs. transfer-from) once the inbound flow is built
- Extend PROJECT_SHARING_FEATURE and PROJECT_COPYING_FEATURE controls to include a separate PROJECT_IMPORTING_FEATURE
- Per-experiment Reimport timeout to bound importer hangs. `BatchTransferServiceImpl.runImporter` invokes `DicomZipImporter.call()` synchronously, with no upper bound — a stuck prearchive write (wedged NFS mount, deadlocked rebuild queue, slow remote disk) parks the batch-loop thread indefinitely and blocks all subsequent requests in the batch. The 5-minute heartbeat logger makes a hang observable, but recovery still requires a Tomcat restart. Possible fix: wrap `importExperiment` in `Future.get(timeoutMs)` on a separate executor; on timeout, cancel the future, emit a Failed event, and let the batch loop continue with the next request. Caveats: `cancel(true)` cannot reliably stop uninterruptible I/O so the underlying thread leaks; the prearchive has no transactional rollback so a cancelled import may leave partial state; choosing a default timeout is environment-specific (large legitimate sessions can run 30+ min). Defer until a customer incident motivates it; if added, expose the timeout as a plugin config and document explicitly that it means "we gave up waiting", not "we cleaned up".

