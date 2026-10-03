"""Bind fixture bytes to the production Kotlin encoder, never a guessed layout."""
from pathlib import Path
import re
ROOT=Path(__file__).resolve().parents[2]
APP=ROOT/'app/src/main/java/com/zui/zuicontrol'
source=(APP/'ZuiControlRequest.kt').read_text('utf8')
encoder=source[source.index('    internal fun buildRequestText('):source.index('    internal fun hasPendingRequest(')]
match=re.fullmatch(r'\s*internal fun buildRequestText\(\s*requestId: String,\s*command: String,\s*packageName: String,\s*mode: String,\s*\): String = listOf\((.*?)\)\.joinToString\("([^"]*)"\)\s*',encoder,re.S)
assert match,'Production encoder changed: bind the new implementation explicitly'
TOKENS=[x.strip() for x in match[1].split(',')]
assert all(x in ('requestId','command','packageName','mode','""') for x in TOKENS)
SEPARATOR=match[2]
def app_wire(requestId,command,packageName='',mode=''):
    values=locals()
    return SEPARATOR.join('' if x=='""' else values[x] for x in TOKENS)
def java_encoder():
    return 'static String appWire(String requestId,String command,String packageName,String mode){return String.join("'+SEPARATOR+'",'+','.join(TOKENS)+');}'

# Bind business caller placement, independently of the consumer's numeric indices.
settings=(APP/'SettingsBackup.kt').read_text('utf8')
assert 'ZuiControlRequest.send(context,action,pkg=tx)' in settings
assert 'command(context,"sb_restore",inspection.transaction)' in settings
assert 'command(context,"sb_reset",tx)' in settings
confirm=settings.index('transport("backupConfirm",')
assert confirm < settings.index('command(context,"sb_restore",inspection.transaction)')
assert '.put("hash",inspection.hash)' in settings[confirm:settings.index('command(context,"sb_restore",inspection.transaction)')]
rules=(APP/'ZuioptRules.kt').read_text('utf8')
assert 'ZuiControlRequest.send(context, command, pkg = key, mode = value)' in rules
