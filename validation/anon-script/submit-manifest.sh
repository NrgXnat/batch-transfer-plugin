#!/usr/bin/env bash
#
# Validate the one-shot manifest submit (POST /xapi/transfer/manifest) on a live XNAT — the label-driven
# path: a manifest of source_subject_label / source_session_label is resolved to sessions server-side and
# the matched rows are submitted as a batch transfer. THIS CREATES DATA in DEST_PROJECT.
#
#   auth -> (capabilities gate, only if an anon script is used) -> upload manifest -> poll.
#
# By default it submits a plain Reimport (destination pipeline only). Options:
#   MANIFEST_FILE=./my.csv        use a real manifest instead of the one-row manifest built from the labels
#   MANIFEST_MODE=Reimport        Reimport (default) | Share | Clone
#   ANON_SCRIPT_FILE=./x.des      also apply a custom anon script (Reimport only; gates on capabilities)
#   ANON_REPLACE_PIPELINE=1       with a script, also send anon_replace_pipeline=true
#
# Usage:  ./submit-manifest.sh [config.env]
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
: "${DEST_PROJECT:?set DEST_PROJECT in config.env}"

MANIFEST_MODE="${MANIFEST_MODE:-Reimport}"
TRACKING_ID="${TRACKING_ID:-manifest_validation_$(date +%Y%m%d_%H%M%S)}"
POLL_INTERVAL="${POLL_INTERVAL:-5}"
POLL_TIMEOUT="${POLL_TIMEOUT:-600}"
XNAT_URL="${XNAT_URL%/}"

command -v jq >/dev/null || { echo "jq (>= 1.6) is required"; exit 2; }

REPLACE="false"; [ "${ANON_REPLACE_PIPELINE:-0}" = "1" ] && REPLACE="true"

# Build (or reuse) the manifest. The generated one is a single row from the configured labels; if
# DEST_SUBJECT_LABEL / DEST_SESSION_LABEL are set it also adds the destination_*_label routing columns, so
# the reimport is routed to that subject/session. Supply MANIFEST_FILE for a real cohort.
CLEANUP_CSV=""
if [ -n "${MANIFEST_FILE:-}" ]; then
    [ -f "$MANIFEST_FILE" ] || { echo "MANIFEST_FILE not found: $MANIFEST_FILE"; exit 2; }
    CSV="$MANIFEST_FILE"
else
    : "${MANIFEST_SUBJECT_LABEL:?set MANIFEST_SUBJECT_LABEL (or MANIFEST_FILE) in config.env}"
    : "${MANIFEST_SESSION_LABEL:?set MANIFEST_SESSION_LABEL (or MANIFEST_FILE) in config.env}"
    CSV="$(mktemp)"; CLEANUP_CSV="$CSV"
    if [ -n "${DEST_SUBJECT_LABEL:-}" ] || [ -n "${DEST_SESSION_LABEL:-}" ]; then
        {
            echo "source_subject_label,source_session_label,destination_subject_label,destination_session_label"
            echo "$MANIFEST_SUBJECT_LABEL,$MANIFEST_SESSION_LABEL,${DEST_SUBJECT_LABEL:-},${DEST_SESSION_LABEL:-}"
        } > "$CSV"
    else
        {
            echo "source_subject_label,source_session_label"
            echo "$MANIFEST_SUBJECT_LABEL,$MANIFEST_SESSION_LABEL"
        } > "$CSV"
    fi
fi

CURL=(curl -sS)
[ "${XNAT_INSECURE:-0}" = "1" ] && CURL+=(-k)

echo "== 1. Authenticate =="
JSESSION="$("${CURL[@]}" -u "$XNAT_USER:$XNAT_PASS" "$XNAT_URL/data/JSESSION" | tr -d '[:space:]')"
[ -n "$JSESSION" ] || { echo "FAIL: empty JSESSION (bad credentials or URL?)"; exit 1; }
COOKIE=(-b "JSESSIONID=$JSESSION")
trap '"${CURL[@]}" "${COOKIE[@]}" -X DELETE "$XNAT_URL/data/JSESSION" >/dev/null 2>&1 || true; [ -n "$CLEANUP_CSV" ] && rm -f "$CLEANUP_CSV" || true' EXIT
echo "  ok — JSESSION acquired (reused for all calls, deleted on exit)"

# The anon script (and only the anon script) needs the Anon-Script param; a plain reimport does not.
FORM=(-F "manifest=@$CSV;type=text/csv"
      -F "source_project=$SOURCE_PROJECT"
      -F "destination_project=$DEST_PROJECT"
      -F "mode=$MANIFEST_MODE"
      -F "tracking_id=$TRACKING_ID")
if [ -n "${ANON_SCRIPT_FILE:-}" ]; then
    [ -f "$ANON_SCRIPT_FILE" ] || { echo "ANON_SCRIPT_FILE not found: $ANON_SCRIPT_FILE"; exit 2; }
    echo "== 2. Capabilities gate (anon script supplied) =="
    CAP="$("${CURL[@]}" "${COOKIE[@]}" "$XNAT_URL/xapi/transfer/capabilities")"
    echo "  $CAP"
    [ "$(echo "$CAP" | jq -r '.per_import_anon // false')" = "true" ] \
        || { echo "FAIL: per_import_anon != true — update xnat-web (the Anon-Script param) first."; exit 1; }
    FORM+=(-F "anon_script=<$ANON_SCRIPT_FILE" -F "anon_replace_pipeline=$REPLACE")
fi

echo "== 3. Submit one-shot manifest =="
echo "  source project : $SOURCE_PROJECT"
echo "  destination    : $DEST_PROJECT"
echo "  mode           : $MANIFEST_MODE"
echo "  manifest       : ${MANIFEST_FILE:-<generated: $MANIFEST_SUBJECT_LABEL / $MANIFEST_SESSION_LABEL>}"
echo "  routing        : ${DEST_SUBJECT_LABEL:-<none>} / ${DEST_SESSION_LABEL:-<none>}"
echo "  anon script    : ${ANON_SCRIPT_FILE:-<none>}"
echo "  tracking id    : $TRACKING_ID"

RESP="$(mktemp)"
CODE="$("${CURL[@]}" "${COOKIE[@]}" -o "$RESP" -w "%{http_code}" -X POST \
    "$XNAT_URL/xapi/transfer/manifest" "${FORM[@]}")"
BODY="$(cat "$RESP")"; rm -f "$RESP"
echo "  HTTP $CODE — $BODY"
if [ "$CODE" != "200" ] && [ "$CODE" != "202" ]; then
    echo "== Result =="
    echo "FAIL: submit returned $CODE. (400 = no matched rows / bad manifest; 403 = no read on $SOURCE_PROJECT"
    echo "      or no edit on $DEST_PROJECT for a script; 409 = build lacks the Anon-Script param.)"
    exit 1
fi

ITEM_COUNT="$(echo "$BODY" | jq -r '.item_count // 0')"
SKIPPED="$(echo "$BODY" | jq -r '.skipped_not_found // 0')"
RESP_TID="$(echo "$BODY" | jq -r '.tracking_id // empty')"
[ -n "$RESP_TID" ] && TRACKING_ID="$RESP_TID"       # poll the id the server actually used
echo "  ok — accepted; item_count=$ITEM_COUNT  skipped_not_found=$SKIPPED  tracking_id=$TRACKING_ID"

if [ "$ITEM_COUNT" = "0" ]; then
    echo "== Result =="
    echo "FAIL: no rows resolved to a session (item_count=0). Check that the labels exist in $SOURCE_PROJECT"
    echo "      and are readable — run ./check-manifest.sh to see the per-row resolution."
    exit 1
fi

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
    echo "PASS: manifest resolved $ITEM_COUNT session(s) and the batch completed.  finalMessage: ${finalmsg:-<none>}"
    echo "      (skipped $SKIPPED not-found row(s).)"
    if [ -n "${DEST_SUBJECT_LABEL:-}" ]; then
        # Routing acceptance: the routed subject label must now exist in the destination project.
        RC="$("${CURL[@]}" "${COOKIE[@]}" -o /dev/null -w "%{http_code}" \
            "$XNAT_URL/data/projects/$DEST_PROJECT/subjects/$DEST_SUBJECT_LABEL")"
        if [ "$RC" = "200" ]; then
            echo "      routing verified: subject '$DEST_SUBJECT_LABEL' exists in $DEST_PROJECT."
        else
            echo "FAIL: routed subject '$DEST_SUBJECT_LABEL' not found in $DEST_PROJECT (HTTP $RC) —"
            echo "      the reimport completed but did not route to the requested subject."
            exit 1
        fi
    fi
    exit 0
elif [ "$succeeded" = "false" ]; then
    echo "FAIL: the batch reported failure.  finalMessage: ${finalmsg:-<none>}"
    echo "$pojo" | jq -r '.payload // (. | tostring)' 2>/dev/null || echo "$pojo"
    exit 1
else
    echo "INCONCLUSIVE: no terminal status after ${POLL_TIMEOUT}s — check \${xnat.home}/logs/batch-transfer.log."
    exit 1
fi
