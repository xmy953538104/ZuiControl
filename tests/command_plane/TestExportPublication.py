"""Verbatim production export functions, failure injected only at publication boundary."""
from pathlib import Path
import os,shutil,subprocess,tempfile
ROOT=Path(__file__).resolve().parents[2]
source=(ROOT/'payload/system/bin/zui_controld').read_text('utf8')
parts=[]
for name in ('settings_put_quiet','export_logs','handle_command'):
    start=source.index(name+'() {');parts.append(source[start:source.index('\n}',start)+2])
script='''REQUEST_USER=0
REQUEST_ACK_KEY=zui_control_request_ack
LOG_EXPORT_KEY=zui_control_log_export
UPERF_MODE=fixture
UPERF_EFFECTIVE_MODE=fixture
UPERF_PERAPP=fixture
LOG_FILE=fixture
UPERF_LOG=fixture
ts(){ printf fixture-time; }
cat(){ printf fixture-mode; }
tail(){ printf fixture-log; }
getprop(){ printf fixture-property; }
settings(){ [ "$INJECT_EXIT" != 0 ] || STORED_EXPORT="$6"; return "$INJECT_EXIT"; }
request_transport(){ [ "$INJECT_EXIT" != 0 ] || STORED_EXPORT="$2"; return "$INJECT_EXIT"; }
'''+ '\n'.join(parts)+'''
for INJECT_EXIT in 0 17; do
 STORED_EXPORT=OLD_EXPORT_SENTINEL
 REQUEST_RESULT_DETAIL=
 handle_command export_logs '' ''
 result=$?
 printf 'publication_exit=%s command_exit=%s detail=%s stale=' "$INJECT_EXIT" "$result" "$REQUEST_RESULT_DETAIL"
 [ "$STORED_EXPORT" = OLD_EXPORT_SENTINEL ] && echo yes || echo no
 if [ "$INJECT_EXIT" = 0 ]; then
  [ "$result" = 0 ] && [ "$STORED_EXPORT" != OLD_EXPORT_SENTINEL ] || exit 91
 else
  [ "$result" != 0 ] && [ "$REQUEST_RESULT_DETAIL" != logs=exported ] || exit 92
 fi
done
'''
with tempfile.TemporaryDirectory(prefix='export-publication-') as tmp:
    path=Path(tmp)/'test.sh';path.write_text(script,encoding='utf8',newline='\n')
    subprocess.run([os.environ.get('BASH') or shutil.which('bash'),str(path)],check=True)
