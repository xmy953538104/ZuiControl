"""Run actual UperfImportCommand with isolated filesystem and platform primitives."""
from pathlib import Path
import subprocess,tempfile,json
ROOT=Path(__file__).resolve().parents[3]
BASE=ROOT/'framework_patch/src/services/com/zui/server/control'
with tempfile.TemporaryDirectory(prefix='uperf-retention-') as tmp:
    work=Path(tmp);files=[]
    def put(name,text):
        path=work/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(text,encoding='utf8');files.append(str(path))
    put('android/os/SystemClock.java','package android.os;public class SystemClock{public static long time=1000;public static long elapsedRealtime(){return time;}}')
    put('android/os/SystemProperties.java','package android.os;public class SystemProperties{public static String get(String k,String d){return d;}public static void set(String k,String v){throw new AssertionError("no service restart in upload fixture");}}')
    put('PolicyCommand.java','''package com.zui.server.control;
import java.io.*;import java.nio.file.*;import static com.zui.server.control.PolicyJson.*;
class PolicyCommand{
 static final String UPERF='''+json.dumps((work/'data').as_posix())+''';
 static final class Disk implements AppPolicyStore.Storage{
  final File directory;Disk(File file)throws Exception{directory=file;Files.createDirectories(file.toPath());}
  Path path(String name){require(name.matches("[a-zA-Z0-9_.-]{1,100}"),"name");Path p=directory.toPath().resolve(name);require(!Files.isSymbolicLink(p),"symlink");return p;}
  public String[] names(){return directory.list();}
  public byte[] read(String name)throws Exception{Path p=path(name);return Files.exists(p)?Files.readAllBytes(p):new byte[0];}
  public void write(String name,byte[] bytes)throws Exception{Files.write(path(name),bytes);}
  public void remove(String name)throws Exception{Files.deleteIfExists(path(name));}
 }
}''')
    source=(BASE/'UperfImportCommand.java').read_text('utf8')
    system=work/'system';system.mkdir()
    # Payload packaging normalizes text to LF; preserve pinned bytes on Windows too.
    for name in ('uperf-sm8650.json','uperf-compatibility.json'):
        (system/name).write_bytes((ROOT/'payload/system/etc/zui_control'/name).read_bytes().replace(b'\r\n',b'\n'))
    source=source.replace('"/system/etc/zui_control/"',json.dumps(system.as_posix()+'/'))
    boot=work/'boot';boot.write_text('11111111-2222-3333-4444-555555555555\n')
    source=source.replace('"/proc/sys/kernel/random/boot_id"',json.dumps(boot.as_posix()))
    put('UperfImportCommand.java',source)
    put('ImportRetentionFixture.java','''package com.zui.server.control;
import java.util.*;import static com.zui.server.control.PolicyJson.*;
public class ImportRetentionFixture{
 public static void main(String[] args)throws Exception{
 byte[] data=new byte[8192];Arrays.fill(data,(byte)71);int accepted=0,rejected=0;
 for(int i=0;i<300;i++){android.os.SystemClock.time+=600001;String tx=String.format("%024x",i);
  try{UperfImportCommand.run("ui_begin",tx+":"+data.length+":"+hash(data)+":0");
   UperfImportCommand.run("ui_chunk",tx+":0:"+Base64.getEncoder().encodeToString(data));accepted++;
  }catch(IllegalArgumentException|IllegalStateException bounded){if(i==0)System.out.println("FIRST_REFUSAL="+bounded);rejected++;}
 }
 PolicyCommand.Disk disk=new PolicyCommand.Disk(new java.io.File(PolicyCommand.UPERF));long count=0,bytes=0;
 for(String name:disk.names())if(name.startsWith("uperf-upload-")&&name.endsWith(".bin")){count++;bytes+=disk.read(name).length;}
 System.out.println("attempts=300 accepted="+accepted+" rejected="+rejected+" payloadCount="+count+" payloadBytes="+bytes);
 if(accepted==0)throw new AssertionError("no upload admitted");
 if(count>16||bytes>16L*UperfConfigStore.LIMIT)throw new AssertionError("expired Uperf upload retention unbounded");
 }
}''')
    classes=['PolicyJson.java','GpuRange.java','AppPolicyStore.java','SettingsBackup.java','UperfConfigStore.java']
    subprocess.run(['javac','-encoding','UTF-8','-d',tmp,*[str(BASE/n) for n in classes],*files],check=True)
    subprocess.run(['java','-cp',tmp,'com.zui.server.control.ImportRetentionFixture'],check=True)
