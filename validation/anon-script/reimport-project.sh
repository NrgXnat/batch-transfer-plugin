#!/usr/bin/env bash
#
# Reimport EVERY image session in a source project into a destination project — with or without a custom
# DicomEdit anon script. Built for load-profiling: run it once each way and compare the wall-clock here
# against the server-side profile you capture (JFR, container stats, etc.).
#
# Usage:
#   ./reimport-project.sh [--with-script | --no-script] [--script FILE] [--no-wait] [config.env]
#
#   --with-script   include a custom anon_script (the anon pipeline path)
#   --no-script     omit it (baseline path)            [default]
#   --script FILE   use FILE as the anon script (implies --with-script)
#   --no-wait       submit and exit without polling for completion
#
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WITH_SCRIPT=0; SCRIPT_FILE=""; WAIT=1; CONFIG=""
while [ $# -gt 0 ]; do
    case "$1" in
        --with-script) WITH_SCRIPT=1 ;;
        --no-script)   WITH_SCRIPT=0 ;;
        --script)      WITH_SCRIPT=1; SCRIPT_FILE="${2:?--script needs a file}"; shift ;;
        --no-wait)     WAIT=0 ;;
        -h|--help)     echo "Usage: $0 [--with-script|--no-script] [--script FILE] [--no-wait] [config.env]"; exit 0 ;;
        *)             CONFIG="$1" ;;
    esac
    shift
done

CONFIG="${CONFIG:-$HERE/config.env}"
[ -f "$CONFIG" ] || { echo "Config not found: $CONFIG  (cp config.env.example config.env, then edit)"; exit 2; }
# shellcheck disable=SC1090
source "$CONFIG"

: "${XNAT_URL:?set XNAT_URL in config.env}"
: "${XNAT_USER:?set XNAT_USER}"; : "${XNAT_PASS:?set XNAT_PASS}"
: "${SOURCE_PROJECT:?set SOURCE_PROJECT in config.env}"
: "${DEST_PROJECT:?set DEST_PROJECT}"
XNAT_URL="${XNAT_URL%/}"
SCRIPT_FILE="${SCRIPT_FILE:-${ANON_SCRIPT_FILE:-$HERE/anonymize.des}}"
POLL_INTERVAL="${POLL_INTERVAL:-5}"
POLL_TIMEOUT="${POLL_TIMEOUT:-3600}"
RESULTS="${RESULTS_FILE:-$HERE/profile-results.csv}"
MODE=$([ "$WITH_SCRIPT" = 1 ] && echo "with-script" || echo "no-script")
TID="reimport_profile_$([ "$WITH_SCRIPT" = 1 ] && echo script || echo plain)_$(date +%Y%m%d_%H%M%S)"

command -v jq >/dev/null || { echo "jq (>= 1.6) is required"; exit 2; }
CURL=(curl -sS); [ "${XNAT_INSECURE:-0}" = "1" ] && CURL+=(-k)

echo "== Reimport profile: $MODE =="
echo "  source -> dest : $SOURCE_PROJECT -> $DEST_PROJECT"
[ "$WITH_SCRIPT" = 1 ] && echo "  anon script    : $SCRIPT_FILE"
echo "  tracking id    : $TID"

echo "== 1. Authenticate =="
JSESSION="$("${CURL[@]}" -u "$XNAT_USER:$XNAT_PASS" "$XNAT_URL/data/JSESSION" | tr -d '[:space:]')"
[ -n "$JSESSION" ] || { echo "FAIL: authentication failed"; exit 1; }
COOKIE=(-b "JSESSIONID=$JSESSION")
trap '"${CURL[@]}" "${COOKIE[@]}" -X DELETE "$XNAT_URL/data/JSESSION" >/dev/null 2>&1 || true' EXIT
echo "  ok"

if [ "$WITH_SCRIPT" = 1 ]; then
    echo "== 2. Capabilities gate =="
    CAP="$("${CURL[@]}" "${COOKIE[@]}" "$XNAT_URL/xapi/transfer/capabilities")"
    [ "$(echo "$CAP" | jq -r '.per_import_anon // false')" = "true" ] \
        || { echo "FAIL: per_import_anon != true — this build can't apply a custom script. ($CAP)"; exit 1; }
    [ -f "$SCRIPT_FILE" ] || { echo "FAIL: anon script not found: $SCRIPT_FILE"; exit 1; }
    echo "  ok"
fi

echo "== 3. Enumerate image sessions in $SOURCE_PROJECT =="
IDS_JSON="$("${CURL[@]}" "${COOKIE[@]}" \
    "$XNAT_URL/data/projects/$SOURCE_PROJECT/experiments?format=json&columns=ID,xsiType,label" \
    | jq '[.ResultSet.Result[] | select(.xsiType | endswith("SessionData")) | .ID]')"
COUNT="$(echo "$IDS_JSON" | jq 'length')"
echo "  found $COUNT image session(s)"
[ "$COUNT" -gt 0 ] || { echo "FAIL: no image sessions in $SOURCE_PROJECT (or no read access)"; exit 1; }

echo "== 4. Build + submit batch ($COUNT Reimport requests) =="
if [ "$WITH_SCRIPT" = 1 ]; then
    BODY="$(jq -n --argjson ids "$IDS_JSON" --arg dest "$DEST_PROJECT" --arg tid "$TID" --rawfile script "$SCRIPT_FILE" \
        '{tracking_id:$tid, anon_script:$script,
          requests: ($ids | map({id:., mode:"Reimport", destination_project:$dest}))}')"
else
    BODY="$(jq -n --argjson ids "$IDS_JSON" --arg dest "$DEST_PROJECT" --arg tid "$TID" \
        '{tracking_id:$tid,
          requests: ($ids | map({id:., mode:"Reimport", destination_project:$dest}))}')"
fi

START="$(date +%s)"
RESP="$(mktemp)"
HTTP="$("${CURL[@]}" "${COOKIE[@]}" -H "Content-Type: application/json" \
    -o "$RESP" -w "%{http_code}" -X POST "$XNAT_URL/xapi/transfer" --data "$BODY")"
echo "  submit HTTP $HTTP"
if [ "$HTTP" != "200" ] && [ "$HTTP" != "202" ]; then
    echo "FAIL: submit returned $HTTP — $(cat "$RESP")"; rm -f "$RESP"; exit 1
fi
rm -f "$RESP"

if [ "$WAIT" = 0 ]; then
    echo "Submitted $COUNT sessions ($MODE), tracking_id=$TID — not waiting (--no-wait)."
    echo "Poll later: ${CURL[*]} -b JSESSIONID=... $XNAT_URL/xapi/event_tracking/$TID"
    exit 0
fi

echo "== 5. Poll to completion (timeout ${POLL_TIMEOUT}s) =="
elapsed=0; succeeded=""; pojo='{}'
while [ "$elapsed" -lt "$POLL_TIMEOUT" ]; do
    pojo="$("${CURL[@]}" "${COOKIE[@]}" "$XNAT_URL/xapi/event_tracking/$TID" 2>/dev/null || echo '{}')"
    succeeded="$(echo "$pojo" | jq -r '.succeeded // empty' 2>/dev/null || echo '')"
    [ -n "$succeeded" ] && break
    sleep "$POLL_INTERVAL"; elapsed=$((elapsed + POLL_INTERVAL))
    printf '  ... %ss\n' "$elapsed"
done
END="$(date +%s)"; WALL=$((END - START))
finalmsg="$(echo "$pojo" | jq -r '.finalMessage // empty' 2>/dev/null || echo '')"
[ -z "$succeeded" ] && succeeded="timeout"

echo "== Result =="
echo "  mode           : $MODE"
echo "  sessions       : $COUNT"
echo "  wall clock     : ${WALL}s"
echo "  per session    : $(awk "BEGIN{printf \"%.2f\", $WALL/$COUNT}")s  (wall / sessions)"
echo "  succeeded      : $succeeded"
echo "  finalMessage   : ${finalmsg:-<none>}"

[ -f "$RESULTS" ] || echo "timestamp,mode,source,dest,sessions,submit_http,wall_seconds,succeeded,final_message" > "$RESULTS"
printf '%s,%s,%s,%s,%s,%s,%s,%s,"%s"\n' \
    "$(date +%Y-%m-%dT%H:%M:%S)" "$MODE" "$SOURCE_PROJECT" "$DEST_PROJECT" "$COUNT" "$HTTP" "$WALL" "$succeeded" "${finalmsg//\"/\"\"}" \
    >> "$RESULTS"
echo "  appended row   : $RESULTS"

[ "$succeeded" = "true" ] && exit 0 || exit 1
