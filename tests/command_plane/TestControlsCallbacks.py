"""Execute the production controls channel: dedup, reconnect, stale death and user isolation."""
from pathlib import Path
import subprocess,tempfile,sys
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'tests/monitor'))
from android_stubs import stubs
stubs["android/os/RemoteException.java"]="package android.os;public class RemoteException extends Exception {public RemoteException(){}public RemoteException(String message){super(message);}}"
fixture='''package com.zui.server.control;
import android.os.*;
public class ControlsTest {
 static void check(boolean v){if(!v)throw new AssertionError();}
 static class Client implements IBinder {
  DeathRecipient death;int calls;String text;boolean failed;
  public void linkToDeath(DeathRecipient d,int f){death=d;}
  public boolean unlinkToDeath(DeathRecipient d,int f){if(death==d)death=null;return true;}
  public boolean transact(int c,Parcel d,Parcel r,int f){check(c==1&&f==FLAG_ONEWAY&&r==null);if(failed)return false;calls++;text=d.text;return true;}
 }
 public static void main(String[] args)throws Exception{
  ControlsCallbacks channel=new ControlsCallbacks();Client first=new Client(),otherUser=new Client();
  channel.register(first,0,"initial");channel.register(otherUser,10,"ok=0\\nerror=inactive_user");
  channel.publish(0,"initial");check(first.calls==0&&otherUser.calls==0);
  channel.publish(0,"changed");check(first.calls==1&&first.text.equals("changed")&&otherUser.calls==0);
  IBinder.DeathRecipient oldDeath=first.death;channel.register(first,0,"changed");oldDeath.binderDied();
  channel.publish(0,"new");check(first.calls==2);
  for(int i=0;i<50;i++)channel.publish(0,"new");check(first.calls==2);
  channel.publish(10,"user10");check(first.text.equals("ok=0\\nerror=inactive_user")&&otherUser.text.equals("user10"));
  first.failed=true;channel.publish(0,"failure");first.failed=false;int calls=first.calls;channel.publish(0,"after");check(first.calls==calls);
  channel.register(first,0,"after");channel.publish(0,"reconnected");check(first.calls==calls+1);
  channel.unregister(first);channel.publish(0,"gone");check(first.calls==calls+1);
  System.out.println("CONTROLS_DEDUP_RECONNECT_STALE_DEATH_USER_ISOLATION=PASS");
 }
}'''
with tempfile.TemporaryDirectory() as tmp:
 out=Path(tmp);files=[]
 for name in ['android/os/IBinder.java','android/os/Parcel.java','android/os/RemoteException.java']:
  p=out/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(stubs[name],encoding='utf8');files.append(str(p))
 p=out/'ControlsTest.java';p.write_text(fixture,encoding='utf8')
 subprocess.run(['javac','-encoding','UTF-8','-d',tmp,*files,str(p),str(ROOT/'framework_patch/src/services/com/zui/server/control/ControlsCallbacks.java')],check=True)
 subprocess.run(['java','-cp',tmp,'com.zui.server.control.ControlsTest'],check=True)
