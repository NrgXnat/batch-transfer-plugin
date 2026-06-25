# Changelog

All notable changes to the **Batch Transfer Plugin** (formerly *Batch Share Plugin*) will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [1.0.1-RC]

A performance and hardening release on top of the 1.0.0 rebrand: Reimport and Clone now run in parallel through JMS queues, mixed-operation batches route correctly, the listing query is parameterized, and workflow history is consistent across all three operations. The `/xapi/transfer` request format and the Java packages are unchanged from 1.0.0.

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

---

## Earlier history (pre-rebrand)

The plugin previously developed under the name *Batch Share Plugin* on a `2.x` line through `2.0.0-SNAPSHOT`. See git history for commits prior to the 1.0.0 release.
