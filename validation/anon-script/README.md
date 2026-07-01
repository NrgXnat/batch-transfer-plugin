# Live validation — API-provided custom anonymization (Batch Transfer 2.0.0)

These scripts validate, against a **running XNAT**, that a custom DicomEdit script supplied with a
`POST /xapi/transfer` Reimport request is accepted, validated, and actually applied at ingest.

**Static-script** path:

1. **Auth** — acquire one JSESSION and reuse it for every call (logout at the end).
2. **Capabilities** — `GET /xapi/transfer/capabilities`; abort early unless `per_import_anon == true`
   (i.e. the deployed xnat-web carries the `Anon-Script` importer param).
3. **Submit** — `POST /xapi/transfer` with a client-chosen `tracking_id`, the `anon_script` body, and one
   `Reimport` request.
4. **Poll** — `GET /xapi/event_tracking/{tracking_id}` until the run reports a terminal `succeeded`.
5. **Verify** (separate script) — download a DICOM from the destination session and confirm the script's
   sentinel edit (`StudyDescription = "API_ANON_OK"`) is present.
6. **Guardrail** (separate script) — confirm a custom script on a non-Reimport request is rejected `400`.

**Templated (`${csv.*}`) path:**

7. **`${csv.*}` substitution** — submit a Reimport with a `${csv.*}` *template* + per-request `csv_values`;
   the plugin substitutes per item, and verify confirms the substituted value landed
   (`submit-csv-anon-transfer.sh`). Optionally `anon_replace_pipeline=true` to suppress the destination
   pipeline so only the custom script runs.
8. **Submit-time enforcement** — six deterministic `400` cases proving `ScriptCompiler.validateBatch`
   rejects bad input *before any data is touched*: malformed DE6, missing/disallowed version, restricted
   verb, unbound placeholder, and an unsafe value (`check-script-enforcement.sh`).

## Files

| File | Purpose |
|---|---|
| `config.env.example` | Copy to `config.env` and fill in. **`config.env` is gitignored** (holds credentials). |
| `anonymize.des` | Sample static DicomEdit 6 script with a grep-able sentinel. |
| `anonymize-csv.des` | `${csv.*}` **template** fixture (substituted from `csv_values`). |
| `submit-anon-transfer.sh` | Steps 1–4 — the static-script positive test. |
| `submit-csv-anon-transfer.sh` | Step 7 — `${csv.*}` substitution (+ optional replace-mode) positive test. |
| `verify-anon-applied.sh` | Step 5 — reads the sentinel back from the destination DICOM (override `SENTINEL`). |
| `check-guardrails.sh` | Step 6 — the Reimport-only `400` guardrail (script + Share → 400). |
| `check-script-enforcement.sh` | Step 8 — the six submit-enforcement `400` cases (no data touched). |
| `reimport-project.sh` | Load profiling — reimport **all** sessions in a project, with/without a script. |

## Prerequisites

- `bash`, `curl`, and `jq` (**≥ 1.6**, for `--rawfile`).
- For `verify-anon-applied.sh`: `dcmdump` (dcm4che/DCMTK) **or** `python3` with `pydicom`. Without either,
  it downloads the file and tells you to inspect it manually.

## Setup

```bash
cd validation/anon-script
cp config.env.example config.env      # then edit config.env
chmod +x *.sh
```

Fill in `config.env`:

```sh
XNAT_URL=https://localhost              # no trailing slash needed
XNAT_USER=admin
XNAT_PASS=...                           # never commit this
SOURCE_SESSION_ID=XNAT_E00001           # single-session test: the source session's accession/ID (NOT its label)
SOURCE_PROJECT=SRC_PROJECT              # profiling: reimport every image session in this project
DEST_PROJECT=ANON_TARGET                # destination project; you must have EDIT access (use a scratch project)
# ANON_SCRIPT_FILE=./anonymize.des      # optional; defaults to the bundled sample
# XNAT_INSECURE=1                        # set for self-signed certs (adds curl -k)
# POLL_TIMEOUT=600  POLL_INTERVAL=5      # optional
```

> Find `SOURCE_SESSION_ID`: open the session in XNAT — it's the **Accession #** / ID (e.g. `XNAT_E00001`),
> not the human label.

## Run

```bash
# Static script
./submit-anon-transfer.sh            # auth → capabilities → submit → poll;  PASS = run completed
./verify-anon-applied.sh             # confirm the sentinel edit in the newest destination session
#   or target a specific session:  ./verify-anon-applied.sh XNAT_E00099
./check-guardrails.sh                # confirm the 400 Reimport-only guardrail

# Templated substitution + submit enforcement
./check-script-enforcement.sh        # six 400 cases (parse, version, verb, binding, charset); no data touched
./submit-csv-anon-transfer.sh        # ${csv.*} template + csv_values → reimport; PASS = run completed
SENTINEL="CSV_ANON_OK" ./verify-anon-applied.sh   # confirm the SUBSTITUTED value landed
```

Each script exits `0` on PASS, non-zero on FAIL/inconclusive, and prints a `== Result ==` line.
`check-script-enforcement.sh` touches no data; the `submit-*` scripts create a session in `DEST_PROJECT`.

## Interpreting results

- **Capabilities `per_import_anon != true`** → the running build predates the `Anon-Script` change. The
  feature is inert; nothing else will work until xnat-web is updated. (This is the gate, not a bug.)
- **Submit `409`** → same as above but caught at submit (shouldn't happen if step 2 passed).
- **Submit `400`** → the request was rejected at submit. Either malformed (empty `requests`, or a
  non-Reimport item carried the script — `check-guardrails.sh`), or it failed a
  `validateBatch` check: unparsable DE6, missing/disallowed version, a restricted verb, an unbound
  `${csv.*}` placeholder, or a value outside the safe charset (`check-script-enforcement.sh`). The response body
  names the reason.
- **Submit `403`** → you lack edit access to `DEST_PROJECT`.
- **Poll `succeeded:false`** → the reimport failed *during ingest* (after submit passed). A
  **malformed or restricted script is rejected up front at submit (`400`)**, so this is a runtime/data
  failure (e.g. no DICOM in the source, a prearchive error), not a script-syntax problem. Read
  `finalMessage` / the payload, or `batch-transfer.log` on the server.
- **Verify FAIL (sentinel missing)** → the run completed but the script's edit isn't in the destination.
  Most likely the **destination project's own anonymization overwrote `(0008,1030)`** — the custom script
  runs *in addition to* the destination pipeline (additive), so pick a `DEST_PROJECT` whose script doesn't
  rewrite StudyDescription, or change the sentinel tag in `anonymize.des` to one the pipeline leaves alone.

## Load profiling — reimport a whole project (`reimport-project.sh`)

Reimports **every image session** in `SOURCE_PROJECT` into `DEST_PROJECT` as one batch, with or without a
custom anon script, and reports end-to-end wall-clock — for comparing the two paths under load:

```bash
./reimport-project.sh --no-script              # baseline path
./reimport-project.sh --with-script            # anon-pipeline path (default script: anonymize.des)
./reimport-project.sh --script big.des         # a heavier / representative script
./reimport-project.sh --with-script --no-wait  # submit only (poll / profile separately)
```

Each completed run appends a row to **`profile-results.csv`** (gitignored) —
`timestamp, mode, source, dest, sessions, submit_http, wall_seconds, succeeded, final_message` — so the
two paths line up for diffing. Sessions are enumerated by `xsiType` ending in `SessionData` (all
image-session types; assessors/subjects excluded, since Reimport is session-only).

**For meaningful numbers:**
- The wall-clock here is **client-side, end-to-end** (submit → all items terminal). Capture the real load
  (CPU, I/O, the anon pipeline) **server-side** during the run — JFR, `docker stats`, prearchive disk.
- Reimport throughput is bound by the **JMS consumer concurrency** (`BatchTransferQueuePrefsBean`, default
  Reimport 4–8) and **prearchive storage**, not the plugin. Keep concurrency identical across the two runs
  you compare; expect the `--with-script` delta to reflect the per-file anon re-parse cost (~10% in the
  JFR work), not a plugin bottleneck.
- Use a **scratch `DEST_PROJECT`**, and start each A/B run from the **same destination state** (empty, or
  cleared between runs) — a second reimport into a populated destination merges/relabels and skews timing.

## Notes

- **Reimport creates a new session** in `DEST_PROJECT` (re-ingested DICOM). Clean up test runs as needed.
- The scripts reuse a **single JSESSION** and `DELETE` it on exit.
- `config.env` is gitignored; keep credentials out of version control.
