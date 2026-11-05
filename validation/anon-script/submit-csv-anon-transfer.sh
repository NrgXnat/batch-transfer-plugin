#!/usr/bin/env bash
#
# Validate ${csv.*} substitution (and, optionally, replace-mode) for a Batch Transfer Reimport on
# a live XNAT.
#   auth -> capabilities gate -> submit Reimport with a ${csv.*} TEMPLATE + per-request csv_values -> poll.
# The plugin substitutes the template per item; on success, verify the substituted value landed:
#   SENTINEL="$CSV_STUDY_DESC" ./verify-anon-applied.sh
#
# Replace-mode: set ANON_REPLACE_PIPELINE=1 to also send anon_replace_pipeline=true (the destination's
# site/project anonymization is suppressed; only the custom script runs). Deeply confirming the bypass
# needs a DEST_PROJECT with a known site/project script — see the README note.
#
# Usage:  ./submit-csv-anon-transfer.sh [config.env]
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
: "${SOURCE_SESSION_ID:?set SOURCE_SESSION_ID in config.env}"
: "${DEST_PROJECT:?set DEST_PROJECT in config.env}"

CSV_ANON_SCRIPT_FILE="${CSV_ANON_SCRIPT_FILE:-$HERE/anonymize-csv.des}"
CSV_STUDY_DESC="${CSV_STUDY_DESC:-CSV_ANON_OK}"          # grep-able sentinel, substituted into StudyDescription
CSV_PATIENT_ID="${CSV_PATIENT_ID:-ANON_CSV_042}"         # both must pass the safe charset (A-Za-z0-9 ^_.-)
TRACKING_ID="${TRACKING_ID:-anon_csv_validation_$(date +%Y%m%d_%H%M%S)}"
POLL_INTERVAL="${POLL_INTERVAL:-5}"
POLL_TIMEOUT="${POLL_TIMEOUT:-600}"
XNAT_URL="${XNAT_URL%/}"

command -v jq >/dev/null || { echo "jq (>= 1.6) is required"; exit 2; }
[ -f "$CSV_ANON_SCRIPT_FILE" ] || { echo "CSV anon template not found: $CSV_ANON_SCRIPT_FILE"; exit 2; }

REPLACE="false"; [ "${ANON_REPLACE_PIPELINE:-0}" = "1" ] && REPLACE="true"

CURL=(curl -sS)
[ "${XNAT_INSECURE:-0}" = "1" ] && CURL+=(-k)

echo "== 1. Authenticate =="
JSESSION="$("${CURL[@]}" -u "$XNAT_USER:$XNAT_PASS" "$XNAT_URL/data/JSESSION" | tr -d '[:space:]')"
[ -n "$JSESSION" ] || { echo "FAIL: empty JSESSION (bad credentials or URL?)"; exit 1; }
COOKIE=(-b "JSESSIONID=$JSESSION")
trap '"${CURL[@]}" "${COOKIE[@]}" -X DELETE "$XNAT_URL/data/JSESSION" >/dev/null 2>&1 || true' EXIT
echo "  ok — JSESSION acquired (reused for all calls, deleted on exit)"

echo "== 2. Capabilities gate =="
CAP="$("${CURL[@]}" "${COOKIE[@]}" "$XNAT_URL/xapi/transfer/capabilities")"
echo "  $CAP"
[ "$(echo "$CAP" | jq -r '.per_import_anon // false')" = "true" ] \
    || { echo "FAIL: per_import_anon != true — update xnat-web (the Anon-Script param) first."; exit 1; }
echo "  ok — per_import_anon = true"

echo "== 3. Submit Reimport with a \${csv.*} template + csv_values =="
echo "  source session : $SOURCE_SESSION_ID"
echo "  destination    : $DEST_PROJECT"
echo "  template       : $CSV_ANON_SCRIPT_FILE"
echo "  csv_values     : study_desc=$CSV_STUDY_DESC  patient_id=$CSV_PATIENT_ID"
echo "  replace mode   : $REPLACE"
echo "  tracking id    : $TRACKING_ID"
BODY="$(jq -n \
    --rawfile script "$CSV_ANON_SCRIPT_FILE" \
    --arg id "$SOURCE_SESSION_ID" \
    --arg dest "$DEST_PROJECT" \
    --arg tid "$TRACKING_ID" \
    --arg sd "$CSV_STUDY_DESC" \
    --arg pid "$CSV_PATIENT_ID" \
    --argjson replace "$REPLACE" \
    '{tracking_id: $tid,
      anon_script: $script,
      anon_replace_pipeline: $replace,
      requests: [ { id: $id, mode: "Reimport", destination_project: $dest,
                    csv_values: { study_desc: $sd, patient_id: $pid } } ] }')"

RESP="$(mktemp)"
CODE="$("${CURL[@]}" "${COOKIE[@]}" -H "Content-Type: application/json" \
    -o "$RESP" -w "%{http_code}" -X POST "$XNAT_URL/xapi/transfer" --data "$BODY")"
echo "  HTTP $CODE"
if [ "$CODE" != "200" ] && [ "$CODE" != "202" ]; then
    echo "FAIL: submit returned $CODE — body: $(cat "$RESP")"
    rm -f "$RESP"; exit 1
fi
rm -f "$RESP"
echo "  ok — accepted (template bound, values passed charset, script parsed)"

echo "== 4. Poll for completion (timeout ${POLL_TIMEOUT}s) =="
elapsed=0; succeeded=""; pojo='{}'
while [ "$elapsed" -lt "$POLL_TIMEOUT" ]; do
    pojo="$("${CURL[@]}" "${COOKIE[@]}" "$XNAT_URL/xapi/event_tracking/$TRACKING_ID" 2>/dev/null || echo '{}')"
    succeeded="$(echo "$pojo" | jq -r '.succeeded // empty' 2>/dev/null || echo '')"
    [ -n "$succeeded" ] && break
    sleep "$POLL_INTERVAL"; elapsed=$((elapsed + POLL_INTERVAL))
    printf '  ... %ss\n' "$elapsed"
done
finalmsg="$(echo "$pojo" | jq -r '.finalMessage // empty' 2>/dev/null || echo '')"

echo "== Result =="
if [ "$succeeded" = "true" ]; then
    echo "PASS: reimport completed.  finalMessage: ${finalmsg:-<none>}"
    echo "Next: confirm the substituted value landed in $DEST_PROJECT —"
    echo "      SENTINEL=\"$CSV_STUDY_DESC\" ./verify-anon-applied.sh"
    exit 0
elif [ "$succeeded" = "false" ]; then
    echo "FAIL: reimport reported failure.  finalMessage: ${finalmsg:-<none>}"
    echo "$pojo" | jq -r '.payload // (. | tostring)' 2>/dev/null || echo "$pojo"
    exit 1
else
    echo "INCONCLUSIVE: no terminal status after ${POLL_TIMEOUT}s — check \${xnat.home}/logs/batch-transfer.log."
    exit 1
fi
