package android.zui;

import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.ServiceManager;

public final class ZuiControlManager {
    private static final String SERVICE = "zui_control";
    private static final String DESCRIPTOR = "android.zui.IZuiControl";

    private static final int TX_GET_VERSION = 1;
    private static final int TX_GET_CAPABILITIES = 2;
    private static final int TX_GET_STATE = 3;
    private static final int TX_GET_CURRENT_SCENE = 4;
    private static final int TX_CYCLE_CURRENT_SCENE = 5;
    private static final int TX_SET_CURRENT_SCENE_PROFILE = 6;
    private static final int TX_SET_PROFILE = 7;
    private static final int TX_REMOVE_PROFILE = 8;
    private static final int TX_REFRESH_NOW = 9;
    private static final int TX_SET_MODULE_ENABLED = 10;
    private static final int TX_EXPORT_LOG = 11;
    private static final int TX_NOTIFY_CONTROL_REQUEST = 12;

    private final IBinder mRemote;

    private ZuiControlManager(IBinder remote) {
        mRemote = remote;
    }

    public static ZuiControlManager get() {
        IBinder remote = ServiceManager.getService(SERVICE);
        return remote == null ? null : new ZuiControlManager(remote);
    }

    public String getVersion() {
        return transact(TX_GET_VERSION, null);
    }

    public String getCapabilities() {
        return transact(TX_GET_CAPABILITIES, null);
    }

    public String getState() {
        return transact(TX_GET_STATE, null);
    }

    public String getCurrentScene() {
        return transact(TX_GET_CURRENT_SCENE, null);
    }

    public String cycleCurrentScene(final int displayHz, final int fpsCap, final String mode) {
        return transact(TX_CYCLE_CURRENT_SCENE, new Writer() {
            @Override
            public void write(Parcel data) {
                data.writeInt(displayHz);
                data.writeInt(fpsCap);
                data.writeString(mode);
            }
        });
    }

    public String setCurrentSceneProfile(final int displayHz, final int fpsCap, final String mode) {
        return transact(TX_SET_CURRENT_SCENE_PROFILE, new Writer() {
            @Override
            public void write(Parcel data) {
                data.writeInt(displayHz);
                data.writeInt(fpsCap);
                data.writeString(mode);
            }
        });
    }

    public String setProfile(final String packageName, final int userId, final int displayHz,
            final int fpsCap, final String mode) {
        return transact(TX_SET_PROFILE, new Writer() {
            @Override
            public void write(Parcel data) {
                data.writeString(packageName);
                data.writeInt(userId);
                data.writeInt(displayHz);
                data.writeInt(fpsCap);
                data.writeString(mode);
            }
        });
    }

    public String removeProfile(final String packageName, final int userId) {
        return transact(TX_REMOVE_PROFILE, new Writer() {
            @Override
            public void write(Parcel data) {
                data.writeString(packageName);
                data.writeInt(userId);
            }
        });
    }

    public String refreshNow() {
        return transact(TX_REFRESH_NOW, null);
    }

    public String setModuleEnabled(final String module, final boolean enabled) {
        return transact(TX_SET_MODULE_ENABLED, new Writer() {
            @Override
            public void write(Parcel data) {
                data.writeString(module);
                data.writeInt(enabled ? 1 : 0);
            }
        });
    }

    public String exportLog() {
        return transact(TX_EXPORT_LOG, null);
    }

    public String notifyControlRequest(final String requestId, final String requestSha256) {
        return transact(TX_NOTIFY_CONTROL_REQUEST, new Writer() {
            @Override
            public void write(Parcel data) {
                data.writeString(requestId);
                data.writeString(requestSha256);
            }
        });
    }

    private String transact(int code, Writer writer) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            if (writer != null) {
                writer.write(data);
            }
            mRemote.transact(code, data, reply, 0);
            reply.readException();
            String text = reply.readString();
            return text == null ? "" : text;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private interface Writer {
        void write(Parcel data);
    }
}
