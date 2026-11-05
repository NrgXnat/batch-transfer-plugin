# Batch Transfer Plugin — REST API

Endpoints behind the Batch Transfer screen: capability detection, script and manifest validation, and
submission. Applies to plugin **1.2.0**.

## Conventions

- Base path **`/xapi/transfer`**. JSON bodies use **snake_case**.
- **Authentication:** every endpoint requires a logged-in user.
- **Validation is advisory at `/validate/*`, enforced at submit.** The validate endpoints return a verdict
  with HTTP 200 (validity is in the body) so the UI can show inline feedback. Submit re-runs the same checks
  and rejects with 4xx, so a direct API caller can't bypass them.
- **Submission is asynchronous.** Progress is reported through XNAT's event-tracking API under the batch's
  `tracking_id` (prefix `batch_transfer_`).
- **Error bodies** on 4xx/5xx are plain-text messages (empty for a submit with no `requests`).
- **Custom anonymization scripts need the importer's `Anon-Script` parameter**, present in XNAT 1.10.1 and
  later (and 1.9.3.7 and later on the 1.9 line). `GET /capabilities` reports whether this XNAT build supports it.

### Endpoints

| Method and path | Purpose |
|---|---|
| `GET /xapi/transfer/capabilities` | Whether per-import anonymization is available, plus limits |
| `POST /xapi/transfer/validate/script` | Check a DicomEdit script (advisory) |
| `POST /xapi/transfer/validate/manifest` | Parse a manifest CSV and resolve each row to a source session (advisory) |
| `POST /xapi/transfer` | Submit a batch of explicit requests |
| `POST /xapi/transfer/manifest` | Resolve a manifest and submit its matched rows in one call |

Related endpoints used by the screen:

| Method and path | Source | Purpose |
|---|---|---|
| `GET /xapi/anonymize/projects/{id}/enabled` | XNAT core | Destination anonymization status banner |
| `GET /xapi/event_tracking/{tracking_id}` | XNAT core | Live progress for a submitted batch |
| `GET`/`POST /xapi/batch_transfer/jms_queues` | This plugin (site admin only) | Queue concurrency and the script/manifest limits below |

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

`manifest_columns` is optional. When present, the response includes `binding`.

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
| `source_subject_label`, `source_session_label` | Required | Identify the source session |
| `destination_subject_label`, `destination_session_label` | Routing (optional, Reimport only) | Labels to use in the destination. Must be valid XNAT labels. Also available to the script as `${csv.destination_subject_label}` / `${csv.destination_session_label}`. |
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
  "summary": { "matched": 2, "not_found": 2 },
  "rows": [
    {
      "index": 1,
      "source_subject_label": "Gough_Todd",
      "source_session_label": "73487045",
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
      "source_subject_label": "Townsend_Umaiza",
      "source_session_label": "55491987",
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
      "source_subject_label": "No_Such_Subject",
      "source_session_label": "123",
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
      "source_subject_label": "Gough_Todd",
      "source_session_label": "999",
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

- `status` is `matched`, `subject_not_found`, or `session_not_found`. Resolution honours the caller's read
  access to `source_project`.
- A row can be transferred when `status` is `matched` **and** `value_errors` is empty. `value_errors`
  (a value outside the allowed character set, or an invalid routing label) block submit; `value_warnings`
  (for example, an empty value for a placeholder the script uses) don't.
- When required columns are missing, the response is still 200 with `required_present: false` and
  `missing_columns`; `summary` is null and rows are not resolved.
- `script_binding` is null unless `anon_script` was sent.

**Status codes:** 400 for an empty or unparseable CSV, a missing `source_project`, or an invalid `mode`;
403 without read access to `source_project`; 413 when the manifest exceeds `manifest_max_rows`.

---

## `POST /xapi/transfer`

Submits a batch of explicit requests. Request bodies without the new optional fields behave as before.

**Request**

```json
{
  "tracking_id": "batch_transfer_1762311000000",
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
      "csv_values": { "patient_id": "ANON-001" }
    }
  ]
}
```

| Field | Meaning |
|---|---|
| `tracking_id` | Optional. Generated as `batch_transfer_<millis>` when absent. |
| `anon_script` | Optional DicomEdit 6 script, applied to every item at ingest. Allowed only when every request is a Reimport. `${csv.<column>}` placeholders are filled from each request's `csv_values`. |
| `anon_replace_pipeline` | Optional, default false: the script runs in addition to the destination project's anonymization. True: it runs instead (the plugin also sends `PREVENT_ANON`). |
| `requests[].id`, `mode`, `destination_project` | As before. `mode` is `Share`, `Clone`, or `Reimport`. |
| `requests[].preserve_subject_label`, `preserve_session_label` | Reimport: keep the source labels instead of deriving them from DICOM tags. |
| `requests[].destination_subject_label`, `destination_session_label` | Reimport: explicit destination labels. Take precedence over the preserve options. |
| `requests[].csv_values` | Reimport: values for the script's placeholders. |

**Response:** 200 with an empty body.

**When `anon_script` is present**, these checks run in order and the first failure is returned:

1. 409 if this XNAT build lacks the `Anon-Script` parameter.
2. 400 if any request is not a Reimport.
3. 403 without edit access on every destination project.
4. 400 if the script exceeds `max_anon_script_bytes`, breaks a [script rule](#script-rules), has a
   placeholder missing from any request's `csv_values`, has a value outside `anon_value_charset`, or doesn't
   parse.

Other status codes: 400 when `requests` is empty; 500 if an edit-permission check fails unexpectedly.

---

## `POST /xapi/transfer/manifest`

Resolves a manifest (as `/validate/manifest` does) and submits the matched rows, each carrying its
`csv_values` and routing labels. For scripted callers who skip the review step.

**Request (JSON)**

```json
{
  "source_project": "TEST005",
  "destination_project": "AnonTargetProject",
  "mode": "Reimport",
  "manifest_csv": "source_subject_label,source_session_label,patient_id\n...",
  "anon_script": "version \"6.1\"\n(0010,0020) := \"${csv.patient_id}\"",
  "anon_replace_pipeline": false,
  "tracking_id": "optional"
}
```

**Request (multipart/form-data):** `manifest` (the CSV file), `source_project`, `destination_project`, and
optional `mode`, `anon_script`, `anon_replace_pipeline`, `tracking_id`.

**Response 200**

```json
{ "tracking_id": "batch_transfer_1762311000000", "item_count": 56, "skipped_not_found": 2 }
```

Rows that don't resolve are skipped and counted in `skipped_not_found`.

**Status codes:**

- 400: a missing `source_project` or `destination_project`, an invalid `mode`, an unparseable CSV, missing
  required columns, any matched row with `value_errors`, no matched rows, or a failed script check.
- 403: no read access to `source_project`, or no edit access on the destination when `anon_script` is set.
- 409: `anon_script` sent to a build without `Anon-Script` support.
- 413: the manifest exceeds `manifest_max_rows`.

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
  - With manifest values (submit, `/manifest`): one representative row. Values are restricted, so
    substitution can't change the script's structure and one row stands for all.
  - Without values (`/validate/script` with no manifest): each placeholder is replaced with a bare `0`
    for a structural parse, and `message` says a manifest is needed to validate fully.
- **Empty values.** A bound placeholder with an empty value is substituted as an empty string and reported
  as a `value_warnings` entry. An unbound placeholder blocks submit.
