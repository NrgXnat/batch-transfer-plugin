# Live validation — API-provided custom anonymization (Batch Transfer 2.0.0, Phase 1A)

These scripts validate, against a **running XNAT**, that a custom DicomEdit script supplied with a
`POST /xapi/transfer` Reimport request is accepted and actually applied at ingest.

What they exercise (the Phase 1A path):

1. **Auth** — acquire one JSESSION and reuse it for every call (logout at the end).
2. **Capabilities** — `GET /xapi/transfer/capabilities`; abort early unless `per_import_anon == true`
   (i.e. the deployed xnat-web carries the `Anon-Script` importer param).
3. **Submit** — `POST /xapi/transfer` with a client-chosen `tracking_id`, the `anon_script` body, and one
   `Reimport` request.
4. **Poll** — `GET /xapi/event_tracking/{tracking_id}` until the run reports a terminal `succeeded`.
5. **Verify** (separate script) — download a DICOM from the destination session and confirm the script's
   sentinel edit (`StudyDescription = "API_ANON_OK"`) is present.
6. **Guardrails** (separate script) — confirm a custom script on a non-Reimport request is rejected `400`.

## Files

| File | Purpose |
|---|---|
| `config.env.example` | Copy to `config.env` and fill in. **`config.env` is gitignored** (holds credentials). |
| `anonymize.des` | Sample DicomEdit 6 script with a grep-able sentinel. |
| `submit-anon-transfer.sh` | Steps 1–4 (the main positive test). |
| `verify-anon-applied.sh` | Step 5 — reads the sentinel back from the destination DICOM. |
| `check-guardrails.sh` | Step 6 — the deterministic `400` guardrail (script + Share → 400). |
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
./submit-anon-transfer.sh            # auth → capabilities → submit → poll;  PASS = run completed
./verify-anon-applied.sh             # confirm the sentinel edit in the newest destination session
#   or target a specific session:  ./verify-anon-applied.sh XNAT_E00099
./check-guardrails.sh                # confirm the 400 Reimport-only guardrail
```

Each script exits `0` on PASS, non-zero on FAIL/inconclusive, and prints a `== Result ==` line.

## Interpreting results

- **Capabilities `per_import_anon != true`** → the running build predates the `Anon-Script` change. The
  feature is inert; nothing else will work until xnat-web is updated. (This is the gate, not a bug.)
- **Submit `409`** → same as above but caught at submit (shouldn't happen if step 2 passed).
- **Submit `400`** → the request was malformed (e.g. empty `requests`, or a non-Reimport item carried the
  script — see `check-guardrails.sh`).
- **Submit `403`** → you lack edit access to `DEST_PROJECT`.
- **Poll `succeeded:false`** → the reimport failed. A *malformed* script fails here (Phase 1A defers the
  upfront parse-validate to 1B, so a bad script surfaces as a per-item ingest failure). Read
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
