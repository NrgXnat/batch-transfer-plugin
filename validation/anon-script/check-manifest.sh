#!/usr/bin/env bash
#
# Validate the manifest preflight (POST /xapi/transfer/validate/manifest) on a live XNAT. No data is touched.
#
# Builds a small manifest against SOURCE_PROJECT with three rows:
#   1. a known-good row (MANIFEST_SUBJECT_LABEL / MANIFEST_SESSION_LABEL)      -> expect status "matched"
#   2. the same subject with a bogus session label                            -> expect "session_not_found"
#   3. a bogus subject + bogus session                                        -> expect "subject_not_found"
# then asserts the 200 result's column classification and per-row resolution. Also confirms the JSON body
# variant parses and that a manifest missing a required column is reported (not rejected). Sends the file as
# multipart (the shape the UI uploads).
#
# Usage:  ./check-manifest.sh [config.env]
#
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CONFIG="${1:-$HERE/config.env}"
[ -f "$CONFIG" ] || { echo "Config not found: $CONFIG  (cp config.env.example config.env, then edit)"; exit 2; }
# shellcheck disable=SC1090
source "$CONFIG"
: "${XNAT_URL:?set XNAT_URL in config.env}"
: "${XNAT_USER:?set XNAT_USER in config.env}"
: "${XNAT_PASS:?set XNAT_PASS in config.env}"
: "${SOURCE_PROJECT:?set SOURCE_PROJECT in config.env}"
: "${MANIFEST_SUBJECT_LABEL:?set MANIFEST_SUBJECT_LABEL in config.env (a subject in SOURCE_PROJECT)}"
: "${MANIFEST_SESSION_LABEL:?set MANIFEST_SESSION_LABEL in config.env (a session of that subject)}"
XNAT_URL="${XNAT_URL%/}"
command -v jq >/dev/null || { echo "jq is required"; exit 2; }

CURL=(curl -sS)
[ "${XNAT_INSECURE:-0}" = "1" ] && CURL+=(-k)

JSESSION="$("${CURL[@]}" -u "$XNAT_USER:$XNAT_PASS" "$XNAT_URL/data/JSESSION" | tr -d '[:space:]')"
[ -n "$JSESSION" ] || { echo "FAIL: could not authenticate"; exit 1; }
COOKIE=(-b "JSESSIONID=$JSESSION")
trap '"${CURL[@]}" "${COOKIE[@]}" -X DELETE "$XNAT_URL/data/JSESSION" >/dev/null 2>&1 || true' EXIT

PASS=0; FAIL=0
check() {  # check <label> <condition-result 0/1>
    if [ "$2" = "0" ]; then echo "   PASS $1"; PASS=$((PASS + 1));
    else echo "   FAIL $1"; FAIL=$((FAIL + 1)); fi
}

# A manifest with required + one value + one reserved column, and the three rows described above.
CSV="$(mktemp)"; trap 'rm -f "$CSV"' EXIT
{
    echo "source_subject_label,source_session_label,destination_patient_id,_notes"
    echo "$MANIFEST_SUBJECT_LABEL,$MANIFEST_SESSION_LABEL,ANON-001,known good"
    echo "$MANIFEST_SUBJECT_LABEL,__NO_SUCH_SESSION__,ANON-002,bad session"
    echo "__NO_SUCH_SUBJECT__,__NO_SUCH_SESSION__,ANON-003,bad subject"
} > "$CSV"

echo "== validate/manifest (multipart upload) against project $SOURCE_PROJECT =="
RESP="$(mktemp)"
CODE="$("${CURL[@]}" "${COOKIE[@]}" -o "$RESP" -w "%{http_code}" -X POST \
    "$XNAT_URL/xapi/transfer/validate/manifest" \
    -F "manifest=@$CSV;type=text/csv" \
    -F "source_project=$SOURCE_PROJECT")"
BODY="$(cat "$RESP")"; rm -f "$RESP"
echo "   HTTP $CODE"

if [ "$CODE" != "200" ]; then
    echo "   body: $BODY"
    echo "== Result =="
    echo "FAIL: expected 200 from validate/manifest, got $CODE."
    echo "  (403 = no read access to $SOURCE_PROJECT; 400 = the manifest couldn't be parsed.)"
    exit 1
fi

# Column classification.
check "required_present is true" \
    "$([ "$(echo "$BODY" | jq -r '.required_present')" = "true" ] && echo 0 || echo 1)"
check "value_columns = [destination_patient_id]" \
    "$([ "$(echo "$BODY" | jq -c '.value_columns')" = '["destination_patient_id"]' ] && echo 0 || echo 1)"
check "reserved_columns = [_notes]" \
    "$([ "$(echo "$BODY" | jq -c '.reserved_columns')" = '["_notes"]' ] && echo 0 || echo 1)"
check "total_rows = 3" \
    "$([ "$(echo "$BODY" | jq -r '.total_rows')" = "3" ] && echo 0 || echo 1)"

# Per-row resolution (rows carry a 1-based index).
row_status() { echo "$BODY" | jq -r ".rows[] | select(.index==$1) | .status"; }
check "row 1 matched" \
    "$([ "$(row_status 1)" = "matched" ] && echo 0 || echo 1)"
check "row 1 has a resolved_id" \
    "$([ -n "$(echo "$BODY" | jq -r '.rows[] | select(.index==1) | .resolved_id // empty')" ] && echo 0 || echo 1)"
check "row 1 carries csv_values.destination_patient_id = ANON-001" \
    "$([ "$(echo "$BODY" | jq -r '.rows[] | select(.index==1) | .csv_values.destination_patient_id')" = "ANON-001" ] && echo 0 || echo 1)"
check "row 2 session_not_found (+ available_sessions)" \
    "$([ "$(row_status 2)" = "session_not_found" ] \
        && [ "$(echo "$BODY" | jq -r '.rows[] | select(.index==2) | .available_sessions | type')" = "array" ] \
        && echo 0 || echo 1)"
check "row 3 subject_not_found" \
    "$([ "$(row_status 3)" = "subject_not_found" ] && echo 0 || echo 1)"
check "summary.matched = 1" \
    "$([ "$(echo "$BODY" | jq -r '.summary.matched')" = "1" ] && echo 0 || echo 1)"

# JSON body variant (same result shape) + a manifest missing a required column is reported, not rejected.
echo "== validate/manifest (JSON body) — missing required column is reported, not a 4xx =="
JSON="$(jq -n --arg p "$SOURCE_PROJECT" \
    '{source_project: $p, manifest_csv: "source_subject_label,destination_patient_id\nS,ANON\n"}')"
RESP2="$(mktemp)"
CODE2="$("${CURL[@]}" "${COOKIE[@]}" -H "Content-Type: application/json" \
    -o "$RESP2" -w "%{http_code}" -X POST "$XNAT_URL/xapi/transfer/validate/manifest" --data "$JSON")"
BODY2="$(cat "$RESP2")"; rm -f "$RESP2"
echo "   HTTP $CODE2"
check "JSON variant returns 200" \
    "$([ "$CODE2" = "200" ] && echo 0 || echo 1)"
check "required_present is false" \
    "$([ "$(echo "$BODY2" | jq -r '.required_present')" = "false" ] && echo 0 || echo 1)"
check "missing_columns names source_session_label" \
    "$(echo "$BODY2" | jq -e '.missing_columns | index("source_session_label")' >/dev/null 2>&1 && echo 0 || echo 1)"

echo
echo "== Result =="
echo "  passed: $PASS / $((PASS + FAIL))"
if [ "$FAIL" -eq 0 ]; then
    echo "PASS: the manifest preflight classifies columns and resolves matched / session_not_found /"
    echo "      subject_not_found rows correctly, over both multipart and JSON, touching no data."
    exit 0
else
    echo "FAIL: $FAIL manifest-preflight assertion(s) did not behave as designed (see above)."
    exit 1
fi
