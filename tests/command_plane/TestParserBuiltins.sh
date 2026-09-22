#!/system/bin/sh
set -eu
ZUI_CONTROLD_TEST_MODE=1
export ZUI_CONTROLD_TEST_MODE
ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)"
. "$ROOT/payload/system/bin/zui_controld"
TEST_ROOT="$(mktemp -d)"
trap 'rm -rf "$TEST_ROOT"' EXIT
LAST_REQUEST_RECEIPT="$TEST_ROOT/receipt"
ACTIVE_REQUEST_CLAIM="$TEST_ROOT/claim"
fail() { echo "FAIL $*"; exit 1; }
test "$(request_field 'a|b||pkg|' 3)" = '' || fail empty
test "$(request_field 'a|b||pkg|' 5)" = '' || fail trailing
test "$(request_field_count 'a|b||pkg|')" = 5 || fail count
test "$(request_field 'a|b||pkg|' 4)" = pkg || fail pkg
printf 'r|set_uperf_app||com.example.game|fast
r|done|set_uperf_app|ok
' > "$LAST_REQUEST_RECEIPT"
load_last_receipt || fail valid_receipt
printf '
' >> "$LAST_REQUEST_RECEIPT"
if load_last_receipt; then fail extra_line; fi
printf 'r|status|||
x|done|status|ok
' > "$LAST_REQUEST_RECEIPT"
if load_last_receipt; then fail mismatched_id; fi
printf 'r|status|||
r|processing|status|ok
' > "$LAST_REQUEST_RECEIPT"
if load_last_receipt; then fail nonterminal; fi
printf 'r|status|||
r|done|wrong|ok
' > "$LAST_REQUEST_RECEIPT"
if load_last_receipt; then fail mismatched_command; fi
printf 'r|status|||
' > "$ACTIVE_REQUEST_CLAIM"
load_request_claim || fail valid_claim
printf 'extra' >> "$ACTIVE_REQUEST_CLAIM"
if load_request_claim; then fail extra_claim; fi
test "$(ack_text 'r|bad' done status ok)" = 'r bad|done|status|ok' || fail sanitize
# Match the unchanged sanitizer for every field, including its control-character fallback.
ack_controls="$(printf 'left\rright\nsecond\tend')"
for ack_value in '' 'r._=:/-09' 'package=com.example.game;mode=fast' \
    'pipe|inside' "$ack_controls" '中文;value' 'literal$`"'; do
    sanitized="$(printf '%s' "$ack_value" | tr '|\r\n' '   ')"
    test "$(ack_text "$ack_value" "$ack_value" "$ack_value" "$ack_value")" = \
        "$sanitized|$sanitized|$sanitized|$sanitized" || fail ack_equivalence
done
(
    tr() { fail unnecessary_ack_sanitizer; }
    test "$(ack_text r done set_uperf_app 'package=com.example.game;mode=fast')" = \
        'r|done|set_uperf_app|package=com.example.game;mode=fast' || fail fast_ack
)
printf 'r|set_uperf_app||com.example.game|fast
r|done|set_uperf_app|ok
' > "$LAST_REQUEST_RECEIPT"
printf 'PASS: production receipt/parser rejection fixtures\n'
