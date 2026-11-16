# Batch Transfer Plugin — REST API

Endpoints behind the Batch Transfer screen: capability detection, script and manifest validation, the destination
label check, submission, and transfer history. Applies to plugin **2.0.0** (XNAT 1.10).

## Conventions

- Base path **`/xapi/transfer`**. JSON bodies use **snake_case**.
- **Authentication:** every endpoint requires a logged-in user.
- **Validation is advisory at `/validate/*`, enforced at submit.** The validate endpoints return a verdict
  with HTTP 200 (validity is in the body) so the UI can show inline feedback. Submit re-runs the same checks
  and rejects with 4xx, so a direct API caller can't bypass them.
- **Submission is asynchronous.** Progress is reported through XNAT's event-tracking API under the batch's
  `tracking_id` (prefix `batch_transfer_`).
- **Error bodies** on 4xx/5xx are plain-text messages (empty for a submit with no `requests`, and for a 404).
- **Times** are milliseconds since the epoch.
- **Custom anonymization scripts need the importer's `Anon-Script` parameter**, present in XNAT 1.10.1 and
  later (and 1.9.3.7 and later on the 1.9 line). `GET /capabilities` reports whether this XNAT build supports it.

### Endpoints

| Method and path | Purpose |
|---|---|
| `GET /xapi/transfer/capabilities` | Whether per-import anonymization is available, plus limits |
| `POST /xapi/transfer/validate/script` | Check a DicomEdit script (advisory) |
| `POST /xapi/transfer/validate/manifest` | Parse a manifest CSV and resolve each row to a source session (advisory) |
| `POST /xapi/transfer/validate/destination_labels` | List which session labels a destination project already has (advisory) |
| `POST /xapi/transfer` | Submit a batch of requests, including the matched rows of a manifest |
| `GET /xapi/transfer/history` | List transfer runs |
| `GET /xapi/transfer/history/{tracking_id}` | One run, with its items |
| `POST /xapi/transfer/history/{tracking_id}/stop` | Stop a run |

Related endpoints used by the screen:

| Method and path | Source | Purpose |
|---|---|---|
| `GET /xapi/anonymize/projects/{id}/enabled` | XNAT core | Destination anonymization status banner |
| `GET /xapi/event_tracking/{tracking_id}` | XNAT core | Live progress for a submitted batch |
| `GET`/`POST /xapi/batch_transfer/jms_queues` | This plugin (site admin only) | Queue concurrency and the script/manifest limits below. A concurrency change takes effect after XNAT restarts. |

---

## `GET /xapi/transfer/capabilities`

Always 200.

```json
{
  "per_import_anon": true,
  "anon_script_dialect": "DicomEdit 6",
  "placeholder_syntax": "${csv.<column>}",
  "manifest_max_rows": 5000,
  "max_anon_script_bytes": 262144,
  "anon_value_charset": "A-Za-z0-9 ^_.-"
}
```

- `per_import_anon` is false on XNAT builds without the importer's `Anon-Script` parameter. The UI then
  hides the custom-script controls, and any submit carrying `anon_script` returns 409.
- `manifest_max_rows`, `max_anon_script_bytes`, and `anon_value_charset` are site-admin settings.
  `anon_value_charset` is the body of a regex character class.

---

## `POST /xapi/transfer/validate/script`

Checks a DicomEdit 6 script without applying it.

**Request**

```json
{
  "anon_script": "version \"6.1\"\n(0010,0010) := \"${csv.patient_name}\"\n(0010,0020) := \"${csv.patient_id}\"",
  "manifest_columns": ["patient_name"]
}
```

`manifest_columns` is optional. When present, the response includes `binding`. An empty list means there is
no manifest to fill placeholders from, so every placeholder is reported unbound and the script is not valid.

**Response 200**

```json
{
  "valid": false,
  "dialect": "DicomEdit 6.1",
  "size_bytes": 85,
  "placeholders": ["patient_name", "patient_id"],
  "errors": [],
  "restriction_violations": [],
  "binding": { "bound": false, "unbound": ["patient_id"] }
}
```

| Field | Meaning |
|---|---|
| `valid` | True when there are no `errors`, no `restriction_violations`, and (if `binding` was computed) every placeholder is bound. True means submit would accept the script for these columns. |
| `dialect` | From the script's `version` declaration. Omitted when the script has none. |
| `placeholders` | Column names referenced as `${csv.<column>}`. |
| `errors` | Size-cap and parse errors, as messages. |
| `restriction_violations` | A missing or disallowed `version`, or a restricted command (see [Script rules](#script-rules)). |
| `binding` | Present only when `manifest_columns` was sent. `unbound` lists placeholders with no matching column. |
| `message` | Advisory note. Present when the script has placeholders but no `manifest_columns` were sent: the script was parsed with a stand-in value, so a manifest is needed to validate substitution fully. |

**Status codes:** 400 only when `anon_script` is missing.

---

## `POST /xapi/transfer/validate/manifest`

Parses a manifest CSV and resolves each row to a session in the source project. Nothing is transferred and
nothing is stored.

**Request (JSON)**

```json
{
  "source_project": "TEST005",
  "mode": "Reimport",
  "manifest_csv": "source_subject_label,source_session_label,destination_subject_label,patient_id,_notes\n...",
  "anon_script": "version \"6.1\"\n(0010,0020) := \"${csv.patient_id}\"",
  "file_count": true
}
```

**Request (multipart/form-data):** `manifest` (the CSV file), `source_project`, and optional `mode`,
`anon_script`, `file_count`.

- `mode` is `Share`, `Clone`, or `Reimport` (default `Reimport`).
- `anon_script`, when given, adds `script_binding` to the response.
- `file_count: true` adds each matched row's count of reimportable DICOM files. This walks each matched
  session's files, so it is off by default.

**Manifest columns**

| Column | Kind | Meaning |
|---|---|---|
| `source_subject_label`, `source_session_label` | Required | Identify the source session. A blank `source_session_label` makes a **subject row**, which covers all of the subject's image sessions (Reimport). The column itself must be present. |
| `destination_subject_label`, `destination_session_label` | Routing (optional, Reimport only) | Labels to use in the destination. Must be valid XNAT labels. They take precedence over `preserve_subject_label` / `preserve_session_label` for that session; a blank cell falls back to those options, and then to labels derived from DICOM tags. A subject row can't set `destination_session_label`. Also available to the script as `${csv.destination_subject_label}` / `${csv.destination_session_label}`. |
| Columns starting with `_` | Reserved | Notes; ignored and not available to the script |
| Any other column | Value | Available to the script as `${csv.<column>}` |

**Response 200**

```json
{
  "columns": ["source_subject_label", "source_session_label", "destination_subject_label", "patient_id", "_notes"],
  "required_present": true,
  "missing_columns": [],
  "reserved_columns": ["_notes"],
  "routing_columns": ["destination_subject_label"],
  "value_columns": ["patient_id"],
  "total_rows": 4,
  "summary": { "matched": 2, "not_found": 2, "no_sessions": 0, "duplicate_session": 0 },
  "rows": [
    {
      "index": 1,
      "row_number": 2,
      "source_subject_label": "Gough_Todd",
      "source_session_label": "73487045",
      "subject_row": false,
      "destination_subject_label": "ANON-001",
      "destination_session_label": null,
      "status": "matched",
      "resolved_id": "XNAT_E00166",
      "file_count": 1,
      "csv_values": { "patient_id": "ANON-001", "destination_subject_label": "ANON-001" },
      "value_errors": [],
      "value_warnings": []
    },
    {
      "index": 2,
      "row_number": 3,
      "source_subject_label": "Townsend_Umaiza",
      "source_session_label": "55491987",
      "subject_row": false,
      "destination_subject_label": "ANON-002",
      "destination_session_label": null,
      "status": "matched",
      "resolved_id": "XNAT_E00172",
      "file_count": 1,
      "csv_values": { "patient_id": "bad\"value", "destination_subject_label": "ANON-002" },
      "value_errors": ["Value for 'patient_id' contains characters outside the allowed set."],
      "value_warnings": []
    },
    {
      "index": 3,
      "row_number": 4,
      "source_subject_label": "No_Such_Subject",
      "source_session_label": "123",
      "subject_row": false,
      "destination_subject_label": "ANON-003",
      "destination_session_label": null,
      "status": "subject_not_found",
      "resolved_id": null,
      "csv_values": { "patient_id": "ANON-003", "destination_subject_label": "ANON-003" },
      "value_errors": [],
      "value_warnings": []
    },
    {
      "index": 4,
      "row_number": 5,
      "source_subject_label": "Gough_Todd",
      "source_session_label": "999",
      "subject_row": false,
      "destination_subject_label": "ANON-004",
      "destination_session_label": null,
      "status": "session_not_found",
      "resolved_id": null,
      "csv_values": { "patient_id": "", "destination_subject_label": "ANON-004" },
      "value_errors": [],
      "value_warnings": ["Value for 'patient_id' is empty but the script binds ${csv.patient_id}."]
    }
  ],
  "script_binding": { "bound": true, "unbound": [] }
}
```

- `status` is one of:
  - `matched`: the row resolved to a session, given in `resolved_id`.
  - `subject_not_found` or `session_not_found`.
  - `no_sessions`: the subject has nothing the operation can transfer (for Reimport, no image sessions).
  - `duplicate_session`: another row covers the same session, such as a subject row plus one of its session rows,
    or a repeated row. This blocks submit.

  Resolution honours the caller's read access to `source_project`.
- A subject row is expanded into one entry per image session. Each entry has `subject_row: true`, the row's
  `index` and `row_number`, and the resolved session's `source_session_label`.
- `index` counts data rows from 1. `row_number` is the row as a spreadsheet numbers the file: the header is row 1,
  and blank lines take a row. Messages refer to rows by `row_number`.
- `summary.matched` counts sessions. The other counts are manifest rows: `not_found` covers both kinds of not found.
- A row can be transferred when `status` is `matched` **and** `value_errors` is empty. `value_errors`
  (a value outside the allowed character set, or an invalid routing label) block submit; `value_warnings`
  (for example, an empty value for a placeholder the script uses) don't.
- When required columns are missing, the response is still 200 with `required_present: false` and
  `missing_columns`; `summary` is null and rows are not resolved.
- `script_binding` is null unless `anon_script` was sent.

**Status codes:** 400 for an empty or unparseable CSV, a repeated column, a missing `source_project`, or an invalid `mode`;
403 without read access to `source_project`; 413 when the manifest exceeds `manifest_max_rows`.

---

## `POST /xapi/transfer/validate/destination_labels`

Of the session labels given, lists those an experiment in the destination project already has, owned by it or
shared into it with that label. A Reimport session given one of these labels fails, or merges into the existing
session when it holds the same study. Nothing is changed.

**Request**

```json
{ "destination_project": "HA_OPEN", "session_labels": ["HAOPEN001_MR1", "HAOPEN020_MR1"] }
```

`destination_project` is an ID or alias. Blank and repeated labels are ignored. Matching is exact, as XNAT's own
label lookup is.

**Response 200**

```json
{ "destination_project": "HA_OPEN", "existing_session_labels": ["HAOPEN001_MR1"] }
```

`destination_project` is the project's ID. `existing_session_labels` is sorted.

**Status codes:** 400 without `destination_project`; 403 without read access to the destination project's data;
413 for more than 50,000 labels.

The screen sends each manifest session's `destination_session_label` and its source session label, and flags
the sessions whose label (the manifest's, or the source's when Preserve session labels is on) is already taken.

---

## `POST /xapi/transfer`

Submits a batch of requests. Only `requests` is required.

**Request**

```json
{
  "tracking_id": "batch_transfer_1762311000000_1a2b3c4d",
  "source_project": "TEST005",
  "anon_script": "version \"6.1\"\n(0010,0020) := \"${csv.patient_id}\"",
  "anon_replace_pipeline": false,
  "requests": [
    {
      "id": "XNAT_E00166",
      "mode": "Reimport",
      "destination_project": "AnonTargetProject",
      "preserve_subject_label": true,
      "preserve_session_label": true,
      "destination_subject_label": "ANON-001",
      "csv_values": { "patient_id": "ANON-001", "destination_subject_label": "ANON-001" },
      "row_number": 2
    }
  ]
}
```

| Field | Meaning |
|---|---|
| `tracking_id` | Optional. Generated as `batch_transfer_<millis>_<8 hex digits>` when absent. A `tracking_id` that already has a run returns 409. |
| `source_project` | Optional. The project the transfer was started from, by ID or alias. The caller needs read access to it. When every item belongs to it or is shared into it, the run is recorded under it, with subject labels as that project has them; otherwise under the project that owns the items. |
| `anon_script` | Optional DicomEdit 6 script, applied to every item at ingest. Allowed only when every request is a Reimport. `${csv.<column>}` placeholders are filled from each request's `csv_values`. |
| `anon_replace_pipeline` | Optional, default false: the script runs in addition to the site-wide and destination project scripts. True: it runs instead of them (the plugin also sends `PREVENT_ANON`). |
| `requests[].id`, `mode`, `destination_project` | The item's ID, `Share`, `Clone` or `Reimport`, and the destination project. |
| `requests[].preserve_subject_label`, `preserve_session_label` | Reimport: keep the source labels instead of deriving them from DICOM tags. |
| `requests[].destination_subject_label`, `destination_session_label` | Reimport: explicit destination labels. They take precedence over the preserve options. |
| `requests[].csv_values` | Reimport: values for the script's placeholders. |
| `requests[].row_number` | Optional: the manifest row the item came from, recorded in History. |

**Response:** 200 with an empty body. Follow the run with `GET /xapi/transfer/history/{tracking_id}`.

**Submitting a manifest.** Validate it with `/validate/manifest`, then send one request per entry whose `status` is
`matched` and whose `value_errors` is empty. Give each request the entry's `resolved_id` as `id`, `Reimport` as
`mode`, and the entry's destination labels, `csv_values` and `row_number`. Also send `source_project`. The screen
does the same. The manifest isn't resolved again at submit, but the script and values are checked again, as below.

**When `anon_script` is present**, these checks run in order and the first failure is returned:

1. 409 if this XNAT build lacks the `Anon-Script` parameter.
2. 400 if any request is not a Reimport.
3. 403 without edit access on every destination project.
4. 400 if the script exceeds `max_anon_script_bytes`, breaks a [script rule](#script-rules), has a
   placeholder missing from any request's `csv_values`, has a value outside `anon_value_charset`, or doesn't
   parse.

Other status codes: 400 when `requests` is empty; 403 without read access to `source_project`; 409 when
`tracking_id` already has a run; 500 if an edit-permission check fails unexpectedly.

Each item is then checked when it runs, and a failure fails that item only, with the reason in History. Examples:
no read access to the item; no permission to create data in the destination; a destination that is the item's own
project; an image assessor in a Reimport.

---

## `GET /xapi/transfer/history`

Lists transfer runs, newest first, one page at a time.

| Parameter | Meaning |
|---|---|
| `project` | Optional. Runs that have this project, by ID, as their source or destination. |
| `status` | Optional. One of `Queued`, `Running`, `Stopping`, `Completed`, `Warnings`, `Failed`, `Stopped`. |
| `page` | Optional, from 0 (default 0). |
| `size` | Optional, default 25, at most 200. |
| `all` | Optional, default false. True lists every run on the site, for site admins only. |

Without `project` or `all`, the list is the caller's own runs. Apart from site admins, a caller only ever sees runs
they started or whose source project they can read.

**Response 200**

```json
{
  "runs": [
    {
      "tracking_id": "batch_transfer_1794496410856_965fc96b",
      "status": "Completed",
      "operation": "Reimport",
      "source_project": "HA_MRI",
      "destination_project": "HA_OPEN",
      "item_count": 7,
      "passed_count": 7,
      "failed_count": 0,
      "stopped_count": 0,
      "custom_script": true,
      "anon_replaced_pipeline": false,
      "preserve_subject_label": true,
      "preserve_session_label": true,
      "created_by": "jlee",
      "created": 1794496410871,
      "finished": 1794496421740
    }
  ],
  "total": 90,
  "page": 0,
  "size": 25
}
```

| Field | Meaning |
|---|---|
| `status` | `Queued`, `Running`, `Stopping` while a stop takes effect, then one final status: `Stopped` when any item was stopped; otherwise `Completed` when no item failed, `Failed` when none passed, and `Warnings` for a mix. |
| `item_count`, `passed_count`, `failed_count`, `stopped_count` | The run's items, and how they ended. |
| `custom_script`, `anon_replaced_pipeline` | Whether the run had an `anon_script`, and whether it replaced the other scripts. |
| `preserve_subject_label`, `preserve_session_label` | The run's preserve options. Omitted for Share and Clone. |
| `stop_requested_by`, `stop_requested_at` | Present once someone asked to stop the run. |

**Status codes:** 400 for an unknown `status`; 403 for `all=true` from a caller who isn't a site admin.

---

## `GET /xapi/transfer/history/{tracking_id}`

One run with its items.

| Parameter | Meaning |
|---|---|
| `changed_since` | Optional. A previous response's `items_as_of`. Returns only the items changed since then (minus 30 seconds, so changes that committed late aren't missed), and no `anon_script`. |

**Response 200**

```json
{
  "run": { "tracking_id": "batch_transfer_1794496410856_965fc96b", "status": "Completed", "...": "as in the list" },
  "can_stop": false,
  "anon_script": "version \"6.1\"\n...",
  "items": [
    {
      "item_id": "XNAT02_E00247",
      "mode": "Reimport",
      "row_number": 2,
      "source_subject_label": "HA0012",
      "source_label": "HA0012_MR01",
      "destination_project": "HA_OPEN",
      "destination_subject_label": "HAOPEN003",
      "destination_session_label": "HAOPEN003_MR1",
      "preserve_subject_label": true,
      "preserve_session_label": true,
      "csv_values": { "release": "R1" },
      "status": "Done",
      "destination_id": "XNAT01_E00385",
      "destination_label": "HAOPEN003_MR1",
      "started": 1794496410881,
      "finished": 1794496411645
    }
  ],
  "items_as_of": 1794496421702
}
```

| Field | Meaning |
|---|---|
| `can_stop` | Whether the caller can stop the run now: it is `Queued` or `Running`, and the caller started it or is a site admin. |
| `items[].status` | `Queued`, `Running`, `Done`, `Failed` or `Stopped`. `message` gives the reason for a failure. |
| `items[].destination_id`, `destination_label` | The session the item created or shared in the destination. Absent when it went to the prearchive or failed. |
| `items_as_of` | Pass it as `changed_since` to poll for changes. |

**Who sees what.**

- The run's creator and site admins see everything. Anyone else needs read access to the run's source project.
- For an item whose destination project the caller can't read, the destination labels and IDs are left out and
  `destination_hidden` is true.
- `csv_values` and `anon_script` are only shown to the creator, site admins, and users who can edit the source
  project.

**Status codes:** 403 when the caller can't see the run; 404 when no run has this tracking ID.

---

## `POST /xapi/transfer/history/{tracking_id}/stop`

Stops a run. Items that haven't started are skipped and end `Stopped`. Items already transferring finish. Nothing
that already transferred is undone. The run reads `Stopping` until its last item ends, then `Stopped`.

**Response 200:** the run, as in the list. Asking again while it is still stopping also returns 200.

**Status codes:** 403 unless the caller started the run or is a site admin; 404 when no run has this tracking ID;
409 when the run has already finished.

---

## Script rules

These apply to `/validate/script`, to `script_binding` in `/validate/manifest`, and at submit.

- **Version.** The script must declare `version "x.y"` matching the admin setting *allowed DicomEdit version
  pattern* (default `^6\.([0-7]|10)$`: DicomEdit 6.0 to 6.7 and 6.10).
- **Restricted commands.** Commands that are unsafe when sessions are reimported in parallel are rejected.
  Default list: `mapUID`, `mapReferencedUIDs`, `lookup`, `getURL`, `alterPixels`, `newUID`. Use `hashUID`
  for deterministic UID remapping. Commands named inside string literals or `//` comments are ignored.
- **Size.** At most `max_anon_script_bytes` (default 256 KB).
- **Placeholders.** `${csv.<column>}`, where `<column>` matches `[A-Za-z0-9_]+`. Substitution is plain
  text replacement before DicomEdit compiles the script, and only the `csv.` namespace is substituted, so
  DicomEdit's own `${...}` references are left alone.
- **Values are restricted, not escaped.** A placeholder can sit inside a quoted string (`:= "${csv.name}"`)
  or stand bare as a numeric argument, so no single escape is correct for both. Every value must match `anon_value_charset` (default letters, digits, space, and `^ _ . -`).
  Characters that could break or inject DicomEdit syntax (`"`, `\`, newline, `$`) are rejected with a
  per-cell message.
- **Parsing.** A script with placeholders can't be parsed as-is, so the substituted form is parsed:
  - With manifest values (submit): one representative row. Values are restricted, so
    substitution can't change the script's structure and one row stands for all.
  - Without values (`/validate/script` with no manifest): each placeholder is replaced with a bare `0`
    for a structural parse, and `message` says a manifest is needed to validate fully.
- **Empty values.** A bound placeholder with an empty value is substituted as an empty string and reported
  as a `value_warnings` entry. An unbound placeholder blocks submit.
