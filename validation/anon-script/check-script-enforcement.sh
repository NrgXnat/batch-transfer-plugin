#!/usr/bin/env bash
#
# Validate the submit-time anon-script enforcement on a live XNAT.
#
# Every case below is a single POST /xapi/transfer that must be rejected 400 by ScriptCompiler.validateBatch
# BEFORE any data is touched (the requests are Reimport into DEST_PROJECT, so they clear the mode + edit
# checks and reach the script validation). Each case asserts the 400 AND a substring of the expected reason,
# so a generic 400 (e.g. a different guardrail) doesn't masquerade as a pass.
#
# Covers: parse-validate (malformed), version requirement, version range, verb deny-list, ${csv.*} binding,
# and value charset. (The Reimport-only 400 guardrail lives in check-guardrails.sh.)
#
# Usage:  ./check-script-enforcement.sh [config.env]
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
XNAT_URL="${XNAT_URL%/}"
command -v jq >/dev/null || { echo "jq is required"; exit 2; }

CURL=(curl -sS)
[ "${XNAT_INSECURE:-0}" = "1" ] && CURL+=(-k)

JSESSION="$("${CURL[@]}" -u "$XNAT_USER:$XNAT_PASS" "$XNAT_URL/data/JSESSION" | tr -d '[:space:]')"
[ -n "$JSESSION" ] || { echo "FAIL: could not authenticate"; exit 1; }
COOKIE=(-b "JSESSIONID=$JSESSION")
trap '"${CURL[@]}" "${COOKIE[@]}" -X DELETE "$XNAT_URL/data/JSESSION" >/dev/null 2>&1 || true' EXIT

# Gate: these cases must reach validateBatch (400), not the unsupported-build 409.
CAP="$("${CURL[@]}" "${COOKIE[@]}" "$XNAT_URL/xapi/transfer/capabilities")"
if [ "$(echo "$CAP" | jq -r '.per_import_anon // false')" != "true" ]; then
    echo "SKIP: per_import_anon != true — this build predates the Anon-Script param; these checks would 409,"
    echo "      not 400. Update xnat-web first.  capabilities: $CAP"
    exit 2
fi

PASS=0; FAIL=0

# check_case <label> <expected-body-substring> <json-body>
check_case() {
    local label="$1" expect="$2" body="$3" resp code
    resp="$(mktemp)"
    code="$("${CURL[@]}" "${COOKIE[@]}" -H "Content-Type: application/json" \
        -o "$resp" -w "%{http_code}" -X POST "$XNAT_URL/xapi/transfer" --data "$body")"
    local text; text="$(cat "$resp")"; rm -f "$resp"
    echo "-- $label"
    echo "   HTTP $code — $text"
    if [ "$code" = "400" ] && printf '%s' "$text" | grep -qiF "$expect"; then
        echo "   PASS (400, matched: \"$expect\")"
        PASS=$((PASS + 1))
    else
        echo "   FAIL (expected 400 containing \"$expect\")"
        FAIL=$((FAIL + 1))
    fi
}

# Body builder: a Reimport request, optional csv_values, with the given anon_script.
req() {  # req <anon_script> [csv_values_json]
    local script="$1" csv="${2:-null}"
    jq -n --arg id "$SOURCE_SESSION_ID" --arg dest "$DEST_PROJECT" --arg s "$script" --argjson csv "$csv" \
        '{tracking_id: "anon_script_enforcement",
          anon_script: $s,
          requests: [ ({ id: $id, mode: "Reimport", destination_project: $dest }
                       + (if $csv == null then {} else {csv_values: $csv} end)) ] }'
}

echo "== Submit-time script enforcement (all must be 400, no data touched) =="

# 1. Malformed DicomEdit — caught by the MizerService parse seam at submit.
check_case "malformed script -> parse error" "could not be parsed" \
    "$(req 'version "6.1"
(0010,0010) :== "X"')"

# 2. No version declaration.
check_case "missing version declaration" "must declare a version" \
    "$(req '(0010,0010) := "X"')"

# 3. Disallowed version (routes to the non-thread-safe DE4 path).
check_case "disallowed version 4.0" "not allowed" \
    "$(req 'version "4.0"
(0010,0010) := "X"')"

# 4. Restricted verb (thread-safety / untrusted-input deny-list).
check_case "restricted verb mapUID" "restricted command" \
    "$(req 'version "6.1"
(0010,0020) := mapUID(this)')"

# 5. Unbound ${csv.*} placeholder — referenced but no csv_values supplied.
check_case "unbound csv placeholder" "Unbound placeholder" \
    "$(req 'version "6.1"
(0010,0020) := "${csv.pid}"')"

# 6. Value outside the safe charset (a quote would break out of the DE6 string context).
check_case "unsafe csv value (charset)" "outside the allowed set" \
    "$(req 'version "6.1"
(0010,0020) := "${csv.pid}"' '{"pid":"bad\"value"}')"

echo
echo "== Result =="
echo "  passed: $PASS / $((PASS + FAIL))"
if [ "$FAIL" -eq 0 ]; then
    echo "PASS: every submit-enforcement case rejected its request with 400 before any transfer ran."
    exit 0
else
    echo "FAIL: $FAIL guardrail case(s) did not behave as designed (see above)."
    exit 1
fi
