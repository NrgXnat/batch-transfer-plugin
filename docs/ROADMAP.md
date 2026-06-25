# Batch Transfer Plugin — Roadmap

**Plugin Version**: 1.0.1-RC (rebranded from Batch Share Plugin 2.0.0-SNAPSHOT; version line reset at 1.0.0)
**Target XNAT**: 1.9.3.3
**Last Updated**: 2026-06-22

The Batch Transfer Plugin enables bulk data operations across XNAT projects. Users can Share, Clone, or Reimport subjects, sessions, and assessors in batch from a single interface. **Share** adds data into a destination project without copying (XNAT's standard sharing relationship); **Clone** duplicates data into the destination, producing an independent editable copy; **Reimport** re-ingests image sessions through the destination project's anonymization pipeline.

---

## Completed

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
- **Clone**: Full data duplication with resource copying and path remapping (formerly *Copy*)
- **Reimport**: DICOM session import with destination project anonymization script (formerly *Import*)
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
- **1.0.0 rebrand** (Batch Share → Batch Transfer; Copy → Clone, Import → Reimport)

---

## Planned

### Operations & Workflow
- Expand Reimport type support to include scans-based operations
- Implement UX cohort building filters for large (>10k+) datasets where the select/deselect item workflow would be tedious 
- Launch Advanced Search from destination project Action Menu
  - Include all XNAT experiment data types
- Computed size estimate in the operation-detail panel — replace the static duplication warning with a real byte count for the current selection (e.g. "≈ 4.2 GB will be duplicated to *DestProject*"). Requires either a new XAPI endpoint that walks the archive, or per-row `data-size` attributes emitted by `XDATScreen_batch_transfer.java`, plus rollup logic in `batchTransfer.js`.

### Backend & API
- BatchTransferEvent refactor to limit event/DB traffic (partially addressed in 1.0.1-RC: in-memory `BatchTransferMonitor` fan-in collapses per-batch terminal events to one)
- Rethink BatchTransferEvent status to reflect import process as reflected in prearch import.
  - "Transfer Queued (%s items)" and "Transfer Complete" do not yet reflect true Share/Clone/Reimport status nuance
- REST API extension to support directional sharing workflow (transfer-to vs. transfer-from) once the inbound flow is built
- Extend PROJECT_SHARING_FEATURE and PROJECT_COPYING_FEATURE controls to include a separate PROJECT_IMPORTING_FEATURE (feature-flag naming intentionally deferred from the rebrand)
- Per-experiment Reimport timeout to bound importer hangs. `BatchTransferServiceImpl.runImporter` invokes `DicomZipImporter.call()` synchronously, with no upper bound — a stuck prearchive write (wedged NFS mount, deadlocked rebuild queue, slow remote disk) parks the batch-loop thread indefinitely and blocks all subsequent requests in the batch. The 5-minute heartbeat logger makes a hang observable, but recovery still requires a Tomcat restart. Possible fix: wrap `importExperiment` in `Future.get(timeoutMs)` on a separate executor; on timeout, cancel the future, emit a Failed event, and let the batch loop continue with the next request. Caveats: `cancel(true)` cannot reliably stop uninterruptible I/O so the underlying thread leaks; the prearchive has no transactional rollback so a cancelled import may leave partial state; choosing a default timeout is environment-specific (large legitimate sessions can run 30+ min). Defer until a customer incident motivates it; if added, expose the timeout as a plugin config and document explicitly that it means "we gave up waiting", not "we cleaned up".

### Testing & Release
- Test admin/owner/member permissions
- Testing for Share/Clone/Reimport operations with assessors
- Scale testing on large project
