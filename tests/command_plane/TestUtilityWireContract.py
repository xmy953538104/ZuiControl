"""App-produced bytes -> real Java/native parser -> exact shell business dispatch."""
from UtilityWireContract import *
import subprocess,tempfile,os,json,shlex,sys
TX='d'*24
ROWS=[('status','','','status'),('policy','','e30=','native policy payload'),
 ('sb_export','','','policy_command pkg'),('sb_restore',TX,'','policy_command pkg'),('sb_recover','','','policy_command pkg'),
 ('export_logs','','','export_logs'),('restart_scheduler','','','restart_scheduler')]
for cmd in ('state','reset','read','begin','chunk','commit','abort','enable','disable','rollback'):
    ROWS.append(('zo_'+cmd, 'g'+'a'*24 if cmd=='read' else TX, '0' if cmd=='read' else '', 'ZUIopt --control pkg mode'))
for cmd in ('begin','chunk','commit','state','reset'):
    ROWS.append(('ui_'+cmd,TX if cmd not in ('state','reset') else '', '0', 'policy_command pkg:mode / state=- / reset=mode'))
shell=(ROOT/'payload/system/bin/zui_controld').read_text('utf8')
parser=shell[shell.index('    request_tail="${request#'):shell.index('    count_request_fields "$request"',shell.index('    request_tail="${request#'))]
handler=shell[shell.index('handle_command() {'):shell.index('\nzuiopt_command() {')]
base=ROOT/'framework_patch/src/services/com/zui/server/control'
with tempfile.TemporaryDirectory(prefix='utility-wire-') as folder:
    p=Path(folder)
    java='''package com.zui.server.control; public class WireFixture {public static void main(String[] a)throws Exception{
 Object parsed=UtilityTransport.fields(a[0]);String[] names={"id","command","packageName","mode"};
 for(int i=0;i<4;i++){String value=parsed instanceof String[]?((String[])parsed)[i<2?i:i+1]:(String)parsed.getClass().getDeclaredField(names[i]).get(parsed);
 if(!value.equals(a[i+1]))throw new AssertionError(names[i]);}
 }}'''
    (p/'WireFixture.java').write_text(java,'utf8')
    classes=['PolicyJson','GpuRange','AppPolicyStore','SettingsBackup','UperfConfigStore','UtilityTransport','RequestIdentity']
    subprocess.run(['javac','-encoding','UTF-8','-d',folder,*[str(base/(n+'.java')) for n in classes],str(p/'WireFixture.java')],check=True)
    if '--native' in sys.argv:
        (p/'wire.cpp').write_text('#include "Transport.h"\nint main(int n,char** a){command::Request r(a[1]);r.authenticate(a[2],command::sha256(a[1]));command::require(r.command==a[3],"command");}\n')
        subprocess.run(['g++','-std=c++17','-I',str(ROOT/'native/command'),str(p/'wire.cpp'),'-o',str(p/'wire')],check=True)
    for command,pkg,mode,business in ROWS:
        wire=app_wire('wire',command,pkg,mode)
        subprocess.run(['java','-cp',folder,'com.zui.server.control.WireFixture',wire,'wire',command,pkg,mode],check=True)
        if '--native' in sys.argv:subprocess.run([str(p/'wire'),wire,'wire',command],check=True)
        script='set -eu\nrequest='+shlex.quote(wire)+'\n'+parser+'\n'
        script+='test "$cmd" = '+shlex.quote(command)+'\ntest "$pkg" = '+shlex.quote(pkg)+'\ntest "$mode" = '+shlex.quote(mode)+'\n'
        script+=handler+'\nLOG_FILE=/dev/null\nCURRENT_REQUEST_ID=wire\ntrusted_sha256=hash\n'
        script+='policy_command(){ printf "%s/%s" "$1" "$2"; }; restart_scheduler(){ echo restart_scheduler; }; export_logs(){ echo export_logs; }; zuiopt_command(){ printf "%s/%s/%s" "$1" "$2" "$3"; };\n'
        script+='handle_command "$cmd" "$pkg" "$mode"\nprintf "\\n%s\\n" "$REQUEST_RESULT_DETAIL"\n'
        result=subprocess.run([os.environ.get('BASH','bash'),'-c',script],capture_output=True,text=True,check=True)
        if command.startswith('sb_'):assert command+'/'+pkg in result.stdout
        if command.startswith('zo_'):assert command+'/'+pkg+'/'+mode in result.stdout
        if command.startswith('ui_'):
            argument='-' if command=='ui_state' else mode if command=='ui_reset' else pkg+':'+mode
            assert command+'/'+argument in result.stdout
        if command in ('export_logs','restart_scheduler'):assert command in result.stdout
        print(json.dumps(dict(command=command,field3_package=pkg,field4_mode=mode,business_argument=business,wire=wire),ensure_ascii=False))
    print('UTILITY_CROSS_LAYER_WIRE=PASS')
