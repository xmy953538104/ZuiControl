"""Exact native terminal replay functions; isolated disk/transport/business boundaries.

--emit-script writes the same fixture for real Android /system/bin/sh qualification.
It never references live policy, admission, utility slots or service properties.
"""
from pathlib import Path
import os, subprocess, sys, tempfile

ROOT = Path(__file__).resolve().parents[2]
source = (ROOT/'payload/system/bin/zui_controld').read_text(encoding='utf8')
names = ('sanitize_ack_field', 'ack_text', 'publish_request_ack',
         'publish_request_progress', 'count_request_fields', 'persist_completion',
         'publish_pending_terminal_ack', 'finish_request', 'process_settings_request')
functions = []
for name in names:
    start = source.index('\n'+name+'() {')+1
    end = source.index('\n}\n', start)+3
    functions.append(source[start:end])
script = '''#!/system/bin/sh
set -u
CASE_ROOT="$1"
[ -d "$CASE_ROOT" ] || exit 1
CONTROL_DIR="$CASE_ROOT"
LAST_REQUEST_RECEIPT="$CASE_ROOT/receipt"
REQUEST_ACK_KEY=fixture_ack
LAST_SETTINGS_REQUEST=
LAST_COMPLETED_REQUEST_ID=
TERMINAL_ACK_PENDING=0
CURRENT_REQUEST_ID=
CURRENT_REQUEST_CMD=
business=0
atomic_write_text() { printf '%s' "$2" > "$1"; }
settings_put_quiet() { printf '%s' "$2" > "$CASE_ROOT/ack"; }
log_line() { :; }
timing_mark() { :; }
persist_request_claim() { :; }
clear_request_claim() { :; }
handle_command() {
 business=$((business+1))
 REQUEST_RESULT_DETAIL=fixture_result
 [ "$1" = "zo_state" ]
}
'''+ '\n'.join(functions)+'''
for command in zo_state zo_validate; do
 request="stress_$command|$command|||"
 before=$business
 process_settings_request "$request" >/dev/null 2>&1 || [ "$command" = "zo_validate" ] || exit 2
 [ "$business" -eq $((before+1)) ] || exit 3
 terminal_before="$(cat "$CASE_ROOT/ack")"
 receipt_before="$(cat "$LAST_REQUEST_RECEIPT")"
 case "$terminal_before" in *'|done|'*|*'|failed|'*) ;; *) exit 4 ;; esac
 i=0
 while [ "$i" -lt 100 ]; do
  process_settings_request "$request" || exit 5
  [ "$business" -eq $((before+1)) ] || exit 6
  [ "$(cat "$CASE_ROOT/ack")" = "$terminal_before" ] || exit 7
  [ "$(cat "$LAST_REQUEST_RECEIPT")" = "$receipt_before" ] || exit 8
  i=$((i+1))
 done
done
printf '%s\\n' 'NATIVE_TERMINAL_REPLAY_STRESS=PASS ITERATIONS=200 BUSINESS_EXECUTIONS=2 DUPLICATE_BUSINESS_EXECUTION_COUNT=0 TERMINAL_ACK_REGRESSION_COUNT=0'
'''
if len(sys.argv)==3 and sys.argv[1]=='--emit-script':
    Path(sys.argv[2]).write_text(script,encoding='utf8',newline='\n')
else:
    with tempfile.TemporaryDirectory(prefix='zui-terminal-replay-') as directory:
        path=Path(directory).resolve();fixture=path/'stress.sh'
        fixture.write_text(script,encoding='utf8',newline='\n')
        subprocess.run([os.environ.get('BASH','bash'),str(fixture),str(path)],check=True)
