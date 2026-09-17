"""Execute production profile methods, including the authenticated global setter."""
from pathlib import Path
import subprocess,tempfile,unittest

ROOT=Path(__file__).resolve().parents[2]

class GpuProfiles(unittest.TestCase):
    def test_real_parser_serializer_global_transaction(self):
        source=(ROOT/'framework_patch/src/services/com/zui/server/control/ZuiControlService.java').read_text(encoding='utf8')
        def method(start,end):
            return source[source.index(start):source.index(end,source.index(start))]
        methods=method('    private void loadProfiles()', '    private void applyGlobalRenderVote(')
        methods+=method('    private synchronized String setGlobalGpuRange(', '    private synchronized String setGpuRange(')
        methods+=method('    private static boolean isUperfMode(', '    private String profileStateLines(')
        java=r'''package com.zui.server.control;
import java.io.*; import java.nio.charset.StandardCharsets; import java.nio.file.*; import java.util.*;
public class ProfileFixture {
 static class Log {static void w(String a,String b){} static void w(String a,String b,Throwable c){}}
 static class Binder {static int uid=1000; static int getCallingUid(){return uid;}}
 static class AtomicFile {
  final File file; boolean fail;
  AtomicFile(File f){file=f;}
  byte[] readFully()throws Exception{return Files.readAllBytes(file.toPath());}
  FileOutputStream startWrite()throws Exception{if(fail)throw new IOException("test");return new FileOutputStream(file);}
  void finishWrite(FileOutputStream f)throws Exception{f.close();}
  void failWrite(FileOutputStream f)throws Exception{f.close();}
 }
 static class Policy {int refresh; void refreshGpu(){refresh++;}}
 static class Profile {String packageName,mode;int userId,displayHz,fpsCap;
  Profile(String p,int u,int h,int f,String m){packageName=p;userId=u;displayHz=h;fpsCap=f;mode=m;}}
 final Map<String,Profile> mProfiles=new LinkedHashMap<>();
 final Map<String,GpuRange> mGpuOverrides=new LinkedHashMap<>(),mGpuGlobalRanges=new LinkedHashMap<>();
 final Policy mUperfScenePolicy=new Policy();
 final AtomicFile mProfileFile;String mLastError="";static final String TAG="test";
 ProfileFixture(File f){mProfileFile=new AtomicFile(f);loadProfiles();}
 static String key(int u,String p){return u+":"+p;}
 static boolean validPackage(String p){return p.matches("[a-zA-Z0-9_]+(\\.[a-zA-Z0-9_]+)+");}
 static boolean isTransientPackage(String p){return p.equals("com.android.systemui");}
 static int parseInt(String s,int d){try{return Integer.parseInt(s);}catch(Exception e){return d;}}
 static Profile neutralProfile(int u){return defaultProfile(u);}
 static Profile defaultProfile(int u){return new Profile("default",u,120,0,"DISPLAY_ONLY");}
 static Profile makeProfile(String p,int u,int h,int f,String m){return new Profile(p,u,h,f,m);}
 static boolean isDefaultEquivalent(Profile p){return p.displayHz==120&&p.fpsCap==0;}
'''+methods+r'''
 static void check(boolean b){if(!b)throw new AssertionError();}
 public static void main(String[] args)throws Exception {
  File file=new File(args[0]);String legacy="# ZuiControl profiles v1\nversion=1\ndefault|0|120|0|DISPLAY_ONLY\n";
  Files.write(file.toPath(),legacy.getBytes(StandardCharsets.UTF_8));
  ProfileFixture one=new ProfileFixture(file);check(one.mGpuGlobalRanges.isEmpty());
  check(one.setGlobalGpuRange("performance",0,231,500).equals("ok=1"));
  check(one.mGpuGlobalRanges.size()==4 && one.mUperfScenePolicy.refresh==1);
  ProfileFixture reboot=new ProfileFixture(file);
  check(reboot.mGpuGlobalRanges.size()==4);
  check(reboot.mGpuGlobalRanges.get("0:performance").same(new GpuRange(231,500)));
  check(reboot.mGpuGlobalRanges.get("0:fast").same(new GpuRange(629,903)));
  reboot.mGpuOverrides.put("0:com.kurogame.mingchao",new GpuRange(422,500));check(reboot.saveProfiles());
  ProfileFixture again=new ProfileFixture(file);
  check(again.mGpuOverrides.get("0:com.kurogame.mingchao").same(new GpuRange(422,500)));
  again.mGpuOverrides.clear();check(again.saveProfiles());
  check(new ProfileFixture(file).mGpuOverrides.isEmpty());
  check(new ProfileFixture(file).mGpuGlobalRanges.get("0:performance").same(new GpuRange(231,500)));
  String saved=Files.readString(file.toPath());again.mProfileFile.fail=true;
  check(again.setGlobalGpuRange("performance",0,231,903).startsWith("ok=0"));
  check(again.mGpuGlobalRanges.get("0:performance").same(new GpuRange(231,500)));
  check(again.mUperfScenePolicy.refresh==0 && Files.readString(file.toPath()).equals(saved));
  check(again.setGlobalGpuRange("performance",1,231,903).startsWith("ok=0"));
  check(again.setGlobalGpuRange("invalid",0,231,903).startsWith("ok=0"));
  for(String bad:new String[]{"gpuGlobal|0|performance|333|500","gpuGlobal|-1|performance|231|500",
   "gpuGlobal|0|invalid|231|500","gpuGlobal|0|performance|500|231"}) {
   Files.writeString(file.toPath(),legacy+bad+"\n");check(new ProfileFixture(file).mGpuGlobalRanges.isEmpty());
  }
  System.out.println("GPU_GLOBAL_SAVE_RELOAD_LEGACY_RESET_AUTH_ROLLBACK=PASS");
 }
}
'''
        with tempfile.TemporaryDirectory() as tmp:
            tmp=Path(tmp);(tmp/'ProfileFixture.java').write_text(java,encoding='utf8')
            subprocess.run(['javac','-encoding','UTF-8','-d',str(tmp),str(tmp/'ProfileFixture.java'),
                str(ROOT/'framework_patch/src/services/com/zui/server/control/GpuRange.java')],check=True)
            subprocess.run(['java','-cp',str(tmp),'com.zui.server.control.ProfileFixture',str(tmp/'profile')],check=True)

    def test_binder_and_visual_binding(self):
        source=(ROOT/'framework_patch/src/services/com/zui/server/control/ZuiControlService.java').read_text(encoding='utf8')
        self.assertIn('case TX_SET_GLOBAL_GPU_RANGE:\n                    enforceCommandCallerAllowed();',source)
        bar=(ROOT/'app/src/main/java/com/zui/zuicontrol/GpuRangeBar.kt').read_text(encoding='utf8')
        self.assertIn('canvas.drawLine(x(231), y, x(903), y, paint)',bar)
        self.assertIn('canvas.drawLine(x(range.min), y, x(range.max), y, paint)',bar)
        self.assertIn('canvas.drawText(minLabel, x(range.min), baseline, labelPaint)',bar)
        self.assertIn('canvas.drawText(maxLabel, x(range.max), baseline + stagger, labelPaint)',bar)
        self.assertIn('y + 16f * unit - labelPaint.fontMetrics.ascent',bar)

if __name__=='__main__':unittest.main()
