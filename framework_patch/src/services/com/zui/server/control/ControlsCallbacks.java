package com.zui.server.control;

import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Authenticated registration is owned by the service; no sampling or policy authority here. */
final class ControlsCallbacks {
    static final String DESCRIPTOR="android.zui.IControlsSnapshot";
    static final String ACK_DESCRIPTOR="android.zui.IControlsSnapshotV2";
    private final Map<IBinder,Client> clients=new LinkedHashMap<>();
    private static final class Client { int user; String last,lastAck=""; boolean acks; IBinder.DeathRecipient death; }
    synchronized void register(IBinder binder,int user,String initial)throws RemoteException{
        if(binder==null)throw new IllegalArgumentException("controls callback required");
        unregister(binder);
        if(clients.size()>=8)throw new IllegalStateException("controls subscriber limit");
        Client c=new Client();c.user=user;c.last=initial;
        try{c.acks=ACK_DESCRIPTOR.equals(binder.getInterfaceDescriptor());}
        catch(RemoteException|RuntimeException legacy){c.acks=false;}
        c.death=()->{synchronized(ControlsCallbacks.this){if(clients.get(binder)==c)clients.remove(binder);}};
        binder.linkToDeath(c.death,0);clients.put(binder,c);
    }
    synchronized void unregister(IBinder binder){Client c=clients.remove(binder);if(c!=null)binder.unlinkToDeath(c.death,0);}
    synchronized void publish(int user,String snapshot){
        for(IBinder binder:clients.keySet().toArray(new IBinder[0])){
            Client c=clients.get(binder);
            String next=c.user==user?snapshot:"ok=0\nerror=inactive_user";
            if(next.equals(c.last))continue;
            Parcel data=Parcel.obtain();
            try {data.writeInterfaceToken(DESCRIPTOR);data.writeString(next);
                if(!binder.transact(1,data,null,IBinder.FLAG_ONEWAY))throw new RemoteException("controls callback rejected");
                c.last=next;
            }catch(RemoteException|RuntimeException failure){unregister(binder);}
            finally{data.recycle();}
        }
    }
    /** Notification only, after the existing private ACK and admission writes are durable. */
    synchronized void publishAck(int user,String id,String sha,String ack){
        if(id.length()>64 || sha.length()!=64 || ack.length()>8192)throw new IllegalArgumentException("ACK callback bound");
        String key=id+"|"+sha+"|"+ack;
        for(IBinder binder:clients.keySet().toArray(new IBinder[0])){
            Client c=clients.get(binder);
            if(c.user!=user || !c.acks || key.equals(c.lastAck))continue;
            Parcel data=Parcel.obtain();
            try{
                data.writeInterfaceToken(ACK_DESCRIPTOR);data.writeString(id);data.writeString(sha);data.writeString(ack);
                if(binder.transact(2,data,null,IBinder.FLAG_ONEWAY))c.lastAck=key;
                else c.acks=false; // Older clients retain their ordinary controls subscription.
            }catch(RemoteException|RuntimeException failure){unregister(binder);}
            finally{data.recycle();}
        }
    }
}
