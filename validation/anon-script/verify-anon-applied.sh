#!/usr/bin/env bash
#
# Confirm the custom anon script's sentinel edit landed in the destination session.
# Downloads one DICOM from the destination session and checks for StudyDescription = "API_ANON_OK".
#
# Usage:  ./verify-anon-applied.sh [DEST_SESSION_ID]   [config.env]
#   DEST_SESSION_ID  optional; if omitted, the newest experiment in DEST_PROJECT is used (a guess).
#
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SESSION="${1:-}"
CONFIG="${2:-$HERE/config.env}"
[ -f "$CONFIG" ] || { echo "Config not found: $CONFIG"; exit 2; }
# shellcheck disable=SC1090
source "$CONFIG"
: "${XNAT_URL:?}"; : "${XNAT_USER:?}"; : "${XNAT_PASS:?}"; : "${DEST_PROJECT:?}"
XNAT_URL="${XNAT_URL%/}"
SENTINEL="${SENTINEL:-API_ANON_OK}"

command -v jq >/dev/null || { echo "jq is required"; exit 2; }
CURL=(curl -sS)
[ "${XNAT_INSECURE:-0}" = "1" ] && CURL+=(-k)

JSESSION="$("${CURL[@]}" -u "$XNAT_USER:$XNAT_PASS" "$XNAT_URL/data/JSESSION" | tr -d '[:space:]')"
[ -n "$JSESSION" ] || { echo "FAIL: could not authenticate"; exit 1; }
COOKIE=(-b "JSESSIONID=$JSESSION")
trap '"${CURL[@]}" "${COOKIE[@]}" -X DELETE "$XNAT_URL/data/JSESSION" >/dev/null 2>&1 || true' EXIT

if [ -z "$SESSION" ]; then
    SESSION="$("${CURL[@]}" "${COOKIE[@]}" \
        "$XNAT_URL/data/projects/$DEST_PROJECT/experiments?format=json&columns=ID,label,insert_date" \
        | jq -r '.ResultSet.Result | sort_by(.insert_date) | last | .ID // empty')"
    echo "Auto-selected newest session in $DEST_PROJECT: ${SESSION:-<none>}  (pass an ID to override)"
fi
[ -n "$SESSION" ] || { echo "FAIL: no destination session found in $DEST_PROJECT"; exit 1; }

SCAN="$("${CURL[@]}" "${COOKIE[@]}" "$XNAT_URL/data/experiments/$SESSION/scans?format=json" \
    | jq -r '.ResultSet.Result[0].ID // empty')"
[ -n "$SCAN" ] || { echo "FAIL: no scans on session $SESSION"; exit 1; }

URI="$("${CURL[@]}" "${COOKIE[@]}" \
    "$XNAT_URL/data/experiments/$SESSION/scans/$SCAN/resources/DICOM/files?format=json" \
    | jq -r '.ResultSet.Result[0].URI // empty')"
[ -n "$URI" ] || { echo "FAIL: no DICOM files on $SESSION scan $SCAN"; exit 1; }

TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' RETURN 2>/dev/null || true
DCM="$TMP/sample.dcm"
"${CURL[@]}" "${COOKIE[@]}" "$XNAT_URL$URI" -o "$DCM"
echo "Downloaded $SESSION / scan $SCAN -> $DCM"

if command -v dcmdump >/dev/null; then
    DUMP="$(dcmdump "$DCM" 2>/dev/null || true)"
elif command -v python3 >/dev/null && python3 -c 'import pydicom' >/dev/null 2>&1; then
    DUMP="$(python3 -c 'import pydicom,sys; print(pydicom.dcmread(sys.argv[1], force=True))' "$DCM" 2>/dev/null || true)"
else
    echo "No dcmdump or pydicom available — inspect manually for '$SENTINEL':"
    echo "  $DCM"
    exit 2
fi

echo "== Result =="
if printf '%s' "$DUMP" | grep -q "$SENTINEL"; then
    echo "PASS: sentinel '$SENTINEL' found in the destination DICOM — the API anon script was applied."
    exit 0
else
    echo "FAIL: sentinel '$SENTINEL' not found in the destination DICOM."
    echo "      Likely the destination project's own anonymization overwrote StudyDescription (the custom"
    echo "      script is additive). Use a destination whose script leaves (0008,1030) alone, or change the"
    echo "      sentinel tag in anonymize.des. Tags seen for (0008,1030):"
    printf '%s' "$DUMP" | grep -i "0008,1030\|StudyDescription\|Study Description" || echo "  (none)"
    exit 1
fi
