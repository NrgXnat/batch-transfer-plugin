# Changelog

All notable changes to the **Batch Transfer Plugin** (formerly *Batch Share Plugin*) will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [1.0.0] — Unreleased

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
