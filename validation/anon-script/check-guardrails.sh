#!/usr/bin/env bash
#
# Validate the submit guardrail that gates a custom anon script to Reimport.
# Deterministic check: a custom anon_script on a non-Reimport (Share) request must be rejected 400,
# BEFORE any data is touched. (409 requires a build without Anon-Script; 403 requires a no-edit user —
# both are situational and noted, not asserted here.)
#
# Usage:  ./check-guardrails.sh [config.env]
#
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CONFIG="${1:-$HERE/config.env}"
[ -f "$CONFIG" ] || { echo "Config not found: $CONFIG"; exit 2; }
# shellcheck disable=SC1090
source "$CONFIG"
: "${XNAT_URL:?}"; : "${XNAT_USER:?}"; : "${XNAT_PASS:?}"; : "${SOURCE_SESSION_ID:?}"; : "${DEST_PROJECT:?}"
XNAT_URL="${XNAT_URL%/}"
command -v jq >/dev/null || { echo "jq is required"; exit 2; }

CURL=(curl -sS)
[ "${XNAT_INSECURE:-0}" = "1" ] && CURL+=(-k)

JSESSION="$("${CURL[@]}" -u "$XNAT_USER:$XNAT_PASS" "$XNAT_URL/data/JSESSION" | tr -d '[:space:]')"
[ -n "$JSESSION" ] || { echo "FAIL: could not authenticate"; exit 1; }
COOKIE=(-b "JSESSIONID=$JSESSION")
trap '"${CURL[@]}" "${COOKIE[@]}" -X DELETE "$XNAT_URL/data/JSESSION" >/dev/null 2>&1 || true' EXIT

# A custom script attached to a Share request — rejected at submit, before processing.
BODY="$(jq -n --arg id "$SOURCE_SESSION_ID" --arg dest "$DEST_PROJECT" \
    '{tracking_id: "anon_guardrail_check",
      anon_script: "version \"6.1\"\n(0008,1030) := \"X\"",
      requests: [ { id: $id, mode: "Share", destination_project: $dest } ] }')"

RESP="$(mktemp)"
CODE="$("${CURL[@]}" "${COOKIE[@]}" -H "Content-Type: application/json" \
    -o "$RESP" -w "%{http_code}" -X POST "$XNAT_URL/xapi/transfer" --data "$BODY")"

echo "== Guardrail: custom anon_script + Share request =="
echo "  HTTP $CODE — $(cat "$RESP")"
rm -f "$RESP"

echo "== Result =="
if [ "$CODE" = "400" ]; then
    echo "PASS: a custom anon_script on a non-Reimport request is rejected (400), before any transfer runs."
    echo "(Not asserted here: 409 needs a build lacking Anon-Script; 403 needs a user without edit on $DEST_PROJECT.)"
    exit 0
else
    echo "FAIL: expected 400, got $CODE. The Reimport-only guardrail is not enforced as designed."
    exit 1
fi
