#!/usr/bin/env bash
#
# Validate API-provided custom anonymization for a Batch Transfer Reimport on a live XNAT.
#   auth -> capabilities gate -> submit Reimport with a custom DicomEdit script -> poll to completion.
#
# Usage:  ./submit-anon-transfer.sh [config.env]
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

ANON_SCRIPT_FILE="${ANON_SCRIPT_FILE:-$HERE/anonymize.des}"
TRACKING_ID="${TRACKING_ID:-anon_validation_$(date +%Y%m%d_%H%M%S)}"
POLL_INTERVAL="${POLL_INTERVAL:-5}"
POLL_TIMEOUT="${POLL_TIMEOUT:-600}"
XNAT_URL="${XNAT_URL%/}"

command -v jq  >/dev/null || { echo "jq (>= 1.6) is required"; exit 2; }
command -v curl >/dev/null || { echo "curl is required"; exit 2; }
[ -f "$ANON_SCRIPT_FILE" ] || { echo "Anon script not found: $ANON_SCRIPT_FILE"; exit 2; }

CURL=(curl -sS)
[ "${XNAT_INSECURE:-0}" = "1" ] && CURL+=(-k)

echo "== 1. Authenticate =="
JSESSION="$("${CURL[@]}" -u "$XNAT_USER:$XNAT_PASS" "$XNAT_URL/data/JSESSION" | tr -d '[:space:]')" \
    || { echo "FAIL: authentication request failed"; exit 1; }
[ -n "$JSESSION" ] || { echo "FAIL: empty JSESSION (bad credentials or URL?)"; exit 1; }
COOKIE=(-b "JSESSIONID=$JSESSION")
cleanup() { "${CURL[@]}" "${COOKIE[@]}" -X DELETE "$XNAT_URL/data/JSESSION" >/dev/null 2>&1 || true; }
trap cleanup EXIT
echo "  ok — JSESSION acquired (reused for all calls, deleted on exit)"

echo "== 2. Capabilities gate =="
CAP="$("${CURL[@]}" "${COOKIE[@]}" "$XNAT_URL/xapi/transfer/capabilities")"
echo "  $CAP"
if [ "$(echo "$CAP" | jq -r '.per_import_anon // false')" != "true" ]; then
    echo "FAIL: per_import_anon != true — this XNAT build does not support per-import anonymization."
    echo "      The custom anon script cannot be applied. Update xnat-web (the Anon-Script param) first."
    exit 1
fi
echo "  ok — per_import_anon = true"

echo "== 3. Submit Reimport with custom anon script =="
echo "  source session : $SOURCE_SESSION_ID"
echo "  destination    : $DEST_PROJECT"
echo "  anon script    : $ANON_SCRIPT_FILE"
echo "  tracking id    : $TRACKING_ID"
BODY="$(jq -n \
    --rawfile script "$ANON_SCRIPT_FILE" \
    --arg id "$SOURCE_SESSION_ID" \
    --arg dest "$DEST_PROJECT" \
    --arg tid "$TRACKING_ID" \
    '{tracking_id: $tid,
      anon_script: $script,
      requests: [ { id: $id, mode: "Reimport", destination_project: $dest } ] }')"

RESP="$(mktemp)"
CODE="$("${CURL[@]}" "${COOKIE[@]}" -H "Content-Type: application/json" \
    -o "$RESP" -w "%{http_code}" -X POST "$XNAT_URL/xapi/transfer" --data "$BODY")"
echo "  HTTP $CODE"
if [ "$CODE" != "200" ] && [ "$CODE" != "202" ]; then
    echo "FAIL: submit returned $CODE"
    echo "  body: $(cat "$RESP")"
    rm -f "$RESP"
    exit 1
fi
rm -f "$RESP"
echo "  ok — accepted"

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
    echo "Next: ./verify-anon-applied.sh   (confirm the script's edits landed in $DEST_PROJECT)"
    exit 0
elif [ "$succeeded" = "false" ]; then
    echo "FAIL: reimport reported failure.  finalMessage: ${finalmsg:-<none>}"
    echo "Tracking payload:"; echo "$pojo" | jq -r '.payload // (. | tostring)' 2>/dev/null || echo "$pojo"
    exit 1
else
    echo "INCONCLUSIVE: no terminal status after ${POLL_TIMEOUT}s."
    echo "Check the activity monitor / \${xnat.home}/logs/batch-transfer.log on the server."
    exit 1
fi
