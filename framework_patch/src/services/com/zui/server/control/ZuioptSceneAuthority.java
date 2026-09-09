package com.zui.server.control;

import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

/** Private notification ABI; this class never owns or schedules a task. */
final class ZuioptSceneAuthority {
    static final int REGISTER = 1001;
    static final int UNREGISTER = 1002;
    static final int ACK = 1003;
    static final String CALLBACK_DESCRIPTOR = "com.zui.server.control.IZuioptSceneCallback";
    private long mSeq;
    private long mAck = -1;
    private Registration mRegistration;

    private final class Registration implements IBinder.DeathRecipient {
        final IBinder callback;
        final int pid;
        Registration(IBinder binder, int caller) { callback = binder; pid = caller; }
        @Override public void binderDied() {
            synchronized (ZuioptSceneAuthority.this) {
                if (mRegistration == this) mRegistration = null;
            }
        }
    }

    synchronized void register(IBinder callback, int pid) throws RemoteException {
        if (callback == null || pid <= 0) throw new IllegalArgumentException("scene callback");
        if (mRegistration != null && mRegistration.callback.isBinderAlive()
                && (mRegistration.pid != pid || !mRegistration.callback.equals(callback))) {
            throw new SecurityException("scene callback already registered");
        }
        clear();
        Registration next = new Registration(callback, pid);
        callback.linkToDeath(next, 0);
        mRegistration = next;
        mAck = -1; // A prior daemon's ACK cannot describe this registration.
        replay(); // Includes seq=0 and scenes accepted while no daemon was registered.
    }

    synchronized void unregister(IBinder callback, int pid) {
        if (matches(callback, pid)) clear();
    }

    synchronized void ack(IBinder callback, int pid, long seq) {
        if (!matches(callback, pid) || seq < 0 || seq > mSeq) {
            throw new SecurityException("invalid scene acknowledgement");
        }
        if (seq > mAck) mAck = seq;
    }

    private boolean matches(IBinder callback, int pid) {
        return mRegistration != null && mRegistration.pid == pid
                && mRegistration.callback.equals(callback);
    }

    private void clear() {
        Registration old = mRegistration;
        mRegistration = null;
        if (old != null) old.callback.unlinkToDeath(old, 0);
    }

    synchronized void changed() {
        mSeq = Math.addExact(mSeq, 1L);
        replay();
    }

    private void replay() {
        if (mRegistration == null) return;
        Parcel data = Parcel.obtain();
        long identity = Binder.clearCallingIdentity();
        try {
            data.writeInterfaceToken(CALLBACK_DESCRIPTOR);
            data.writeLong(mSeq);
            if (!mRegistration.callback.transact(1, data, null, IBinder.FLAG_ONEWAY)) clear();
        } catch (RemoteException e) {
            clear();
        } finally {
            Binder.restoreCallingIdentity(identity);
            data.recycle();
        }
    }

    synchronized String stateLines() {
        return "\nzuioptSceneSeq=" + mSeq + "\nzuioptSceneAck=" + mAck
                + "\nzuioptSceneSync=" + (mRegistration != null && mSeq == mAck ? "ok" : "pending");
    }
}
