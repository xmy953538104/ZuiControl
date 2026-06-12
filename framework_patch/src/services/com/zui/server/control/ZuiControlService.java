package com.zui.server.control;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.hardware.display.DisplayManager;
import android.hardware.display.DisplayManagerInternal;
import android.os.Binder;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.provider.Settings;
import android.util.AtomicFile;
import android.util.Log;
import android.view.Display;

import com.android.server.LocalServices;
import com.android.server.wm.ActivityRecord;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public final class ZuiControlService extends Binder {
    private static final String TAG = "ZuiControl";
    private static final String DESCRIPTOR = "android.zui.IZuiControl";
    private static final String APP_PACKAGE = "com.zui.zuicontrol";
    private static final String DATA_DIR = "/data/system/zui_control";
    private static final String PROFILE_FILE = DATA_DIR + "/profiles.prop";
    private static final String SETTING_PEAK_REFRESH_RATE = "peak_refresh_rate";
    private static final String RELEASE_CERT =
            "3fecf3a72ca0e0f24991d49e7306ef4a711711f48a66070755eb0237ecb3ed94";
    private static final String DEBUG_CERT =
            "b4cecd3923c11c203931c44e571e95b3d4208937617c6791d94215c077c043a9";

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
    private static final int PRIORITY_ZUI_CONTROL_PHYSICAL = 8; // PRIORITY_AUTH_OPTIMIZER_RENDER_FRAME_RATE

    private static final int[] DISPLAY_HZ = new int[] {60, 90, 120, 144, 165};
    private static volatile ZuiControlService sInstance;

    private final Context mContext;
    private final PackageManager mPm;
    private final DisplayManager mDisplayManager;
    private final AtomicFile mProfileFile;
    private final Map<String, Profile> mProfiles = new HashMap<>();

    private String mRawFocusedPackage = "";
    private String mCurrentScenePackage = "";
    private String mLastNonTransientScenePackage = "";
    private String mAppliedScenePackage = "";
    private int mCurrentUserId = 0;
    private int mCurrentUid = -1;
    private int mCurrentDisplayId = Display.DEFAULT_DISPLAY;
    private int mTargetDisplayHz = 120;
    private int mTargetFpsCap = 0;
    private String mTargetMode = "DISPLAY_ONLY";
    private String mLastApply = "init";
    private String mLastError = "";
    private int mLastAppliedDisplayId = -1;
    private int mLastAppliedModeId = -1;
    private int mLastAppliedDisplayHz = -1;
    private int mLastSyncedPeakHz = -1;

    public ZuiControlService(Context context) {
        mContext = context;
        mPm = context.getPackageManager();
        mDisplayManager = (DisplayManager) context.getSystemService(DisplayManager.class);
        File dir = new File(DATA_DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "failed to create " + DATA_DIR);
        }
        mProfileFile = new AtomicFile(new File(PROFILE_FILE));
        loadProfiles();
        sInstance = this;
        attachInterface(null, DESCRIPTOR);
        registerPeakObserver();
        publishState();
    }

    public static void start(Context context) {
        try {
            ServiceManager.addService("zui_control", new ZuiControlService(context));
            Log.i(TAG, "zui_control service published");
        } catch (Throwable t) {
            Log.e(TAG, "failed to publish zui_control", t);
        }
    }

    public static ZuiControlService getInstance() {
        return sInstance;
    }

    public void onFocusedAppChanged(ActivityRecord record, int displayId) {
        final String pkg;
        final int uid;
        final int userId;
        if (record == null) {
            pkg = "";
            uid = -1;
            userId = mCurrentUserId;
        } else {
            pkg = safe(record.packageName);
            uid = record.info != null && record.info.applicationInfo != null
                    ? record.info.applicationInfo.uid : -1;
            userId = record.mUserId;
        }
        handleFocusedApp(pkg, uid, userId, displayId);
    }

    private synchronized void handleFocusedApp(String pkg, int uid, int userId, int displayId) {
        mRawFocusedPackage = safe(pkg);
        mCurrentDisplayId = resolveDisplayId(displayId);
        if (APP_PACKAGE.equals(mRawFocusedPackage)) {
            mCurrentUid = uid;
            mCurrentUserId = userId;
            applyProfile(profileFor(APP_PACKAGE, mCurrentUserId), "controlPanel");
            publishState();
            return;
        }
        if (mRawFocusedPackage.isEmpty() || isTransientPackage(mRawFocusedPackage)) {
            String scene = !mAppliedScenePackage.isEmpty()
                    ? mAppliedScenePackage : (!mCurrentScenePackage.isEmpty()
                    ? mCurrentScenePackage : mLastNonTransientScenePackage);
            if (!scene.isEmpty()) {
                applyProfile(profileFor(scene, mCurrentUserId), "transient");
            }
            publishState();
            return;
        }
        mCurrentScenePackage = mRawFocusedPackage;
        mLastNonTransientScenePackage = mRawFocusedPackage;
        mCurrentUid = uid;
        mCurrentUserId = userId;
        applyProfile(profileFor(mCurrentScenePackage, mCurrentUserId), "focus");
        publishState();
    }

    @Override
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        try {
            if (code >= 1 && code <= TX_EXPORT_LOG) {
                data.enforceInterface(DESCRIPTOR);
            }
            String result;
            switch (code) {
                case TX_GET_VERSION:
                    result = "ok=1\nversion=19\nname=ZuiControl";
                    break;
                case TX_GET_CAPABILITIES:
                    result = capabilities();
                    break;
                case TX_GET_STATE:
                    enforceCallerAllowed();
                    result = state();
                    break;
                case TX_GET_CURRENT_SCENE:
                    enforceCallerAllowed();
                    result = "ok=1\ncurrentScenePackage=" + mCurrentScenePackage
                            + "\nlastNonTransientScenePackage=" + mLastNonTransientScenePackage;
                    break;
                case TX_CYCLE_CURRENT_SCENE:
                case TX_SET_CURRENT_SCENE_PROFILE:
                    enforceCallerAllowed();
                    result = setCurrentSceneProfile(data.readInt(), data.readInt(), data.readString());
                    break;
                case TX_SET_PROFILE:
                    enforceCallerAllowed();
                    result = setProfile(data.readString(), data.readInt(), data.readInt(),
                            data.readInt(), data.readString());
                    break;
                case TX_REMOVE_PROFILE:
                    enforceCallerAllowed();
                    result = removeProfile(data.readString(), data.readInt());
                    break;
                case TX_REFRESH_NOW:
                    enforceCallerAllowed();
                    result = refreshNow();
                    break;
                case TX_SET_MODULE_ENABLED:
                    enforceCallerAllowed();
                    result = "ok=1\nmodule=" + safe(data.readString())
                            + "\nenabled=" + (data.readInt() != 0);
                    break;
                case TX_EXPORT_LOG:
                    enforceCallerAllowed();
                    result = state();
                    break;
                default:
                    return super.onTransact(code, data, reply, flags);
            }
            reply.writeNoException();
            reply.writeString(result);
            return true;
        } catch (Throwable t) {
            reply.writeException(t instanceof Exception ? (Exception) t : new RuntimeException(t));
            return true;
        }
    }

    @Override
    protected synchronized void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.print(state());
    }

    private synchronized String setCurrentSceneProfile(int displayHz, int fpsCap, String mode) {
        String pkg = !mLastNonTransientScenePackage.isEmpty()
                ? mLastNonTransientScenePackage : mCurrentScenePackage;
        if (pkg == null || pkg.isEmpty()) {
            return "ok=0\nerror=no_current_scene";
        }
        return setProfileLocked(pkg, mCurrentUserId, displayHz, fpsCap, mode,
                !isControlPanelContext());
    }

    private synchronized String setProfile(String pkg, int userId, int displayHz, int fpsCap, String mode) {
        if (!validPackage(pkg)) {
            return "ok=0\nerror=invalid_package";
        }
        if (!packageExists(pkg)) {
            return "ok=0\nerror=package_not_found";
        }
        return setProfileLocked(pkg, userId, displayHz, fpsCap, mode, shouldApplyPackageNow(pkg));
    }

    private String setProfileLocked(String pkg, int userId, int displayHz, int fpsCap,
            String mode, boolean applyNow) {
        Profile profile = makeProfile(pkg, userId, displayHz, fpsCap, mode);
        if (profile == null) {
            return "ok=0\nerror=" + mLastError;
        }
        if (!"default".equals(pkg) && isDefaultEquivalent(profile)) {
            mProfiles.remove(key(userId, pkg));
            saveProfiles();
            Profile fallback = profileFor(pkg, userId);
            if (applyNow) {
                applyProfile(fallback, "restoreDefault");
            }
            publishState();
            return "ok=1\npackage=" + pkg + "\nremovedProfile=1"
                    + "\ndisplayHz=" + fallback.displayHz
                    + "\nfpsCap=" + fallback.fpsCap + "\nmode=" + fallback.mode;
        }
        mProfiles.put(key(userId, pkg), profile);
        saveProfiles();
        if (applyNow) {
            applyProfile(profile, "binder");
        }
        publishState();
        return "ok=1\npackage=" + pkg + "\ndisplayHz=" + profile.displayHz
                + "\nfpsCap=" + profile.fpsCap + "\nmode=" + profile.mode;
    }

    private synchronized String removeProfile(String pkg, int userId) {
        if (!validPackage(pkg)) {
            return "ok=0\nerror=invalid_package";
        }
        mProfiles.remove(key(userId, pkg));
        saveProfiles();
        if (shouldApplyPackageNow(pkg)) {
            applyProfile(profileFor(pkg, userId), "remove");
        }
        publishState();
        return "ok=1\nremoved=" + pkg;
    }

    private synchronized String refreshNow() {
        String pkg = !mAppliedScenePackage.isEmpty() ? mAppliedScenePackage : mCurrentScenePackage;
        Profile profile = pkg.isEmpty()
                ? defaultProfile(mCurrentUserId) : profileFor(pkg, mCurrentUserId);
        applyProfile(profile, "refreshNow");
        publishState();
        return "ok=1\n" + state();
    }

    private Profile makeProfile(String pkg, int userId, int displayHz, int fpsCap, String mode) {
        String cleanMode = normalizeMode(mode);
        if (cleanMode == null) {
            mLastError = "invalid_mode";
            return null;
        }
        if (!isDisplayHzSupported(displayHz)) {
            mLastError = "unsupported_display_hz_" + displayHz;
            return null;
        }
        if (fpsCap < 0 || fpsCap > 240) {
            mLastError = "invalid_fps_cap";
            return null;
        }
        return new Profile(pkg, userId, displayHz, fpsCap, cleanMode);
    }

    private void applyProfile(Profile profile, String reason) {
        mAppliedScenePackage = profile.packageName;
        mTargetDisplayHz = profile.displayHz;
        mTargetFpsCap = profile.fpsCap;
        mTargetMode = profile.mode;
        if (SystemProperties.getBoolean("persist.zui_control.disable", false)
                || SystemProperties.getBoolean("persist.zui_control.refresh.disable", false)) {
            clearPhysicalVote(mCurrentDisplayId);
            syncPeakRefreshRate(120);
            resetLastApplied();
            mLastApply = "disabled:" + reason;
            return;
        }
        ModeMatch match = findMode(profile.displayHz, mCurrentDisplayId);
        if (match == null) {
            mLastError = "no_display_mode_" + profile.displayHz;
            mLastApply = "failed:" + reason;
            return;
        }
        try {
            DisplayManagerInternal dmi = LocalServices.getService(DisplayManagerInternal.class);
            if (dmi == null) {
                mLastError = "DisplayManagerInternal unavailable";
                mLastApply = "failed:" + reason;
                return;
            }
            int peakHz = syncPeakRefreshRate(profile.displayHz);
            if (mLastAppliedDisplayId == match.displayId
                    && mLastAppliedModeId == match.modeId
                    && mLastAppliedDisplayHz == profile.displayHz
                    && !"refreshNow".equals(reason)) {
                mLastError = "";
                mLastApply = reason + ":display=" + match.displayId + ":mode=" + match.modeId
                        + ":" + voteLabel(profile.displayHz) + "=" + profile.displayHz
                        + ":peakBridge=" + peakHz
                        + ":skipSame";
                return;
            }
            boolean hardPhysicalVote = useHardPhysicalVote(profile.displayHz);
            if (hardPhysicalVote) {
                dmi.setDisplayProperties(match.displayId, true, profile.displayHz,
                        match.modeId, profile.displayHz, profile.displayHz, false, false, false);
                applyPhysicalVote(dmi, match.displayId, profile.displayHz);
            } else {
                applyRenderVote(dmi, match.displayId, profile.displayHz);
                dmi.setDisplayProperties(match.displayId, true, profile.displayHz,
                        match.modeId, 0.0f, profile.displayHz, false, false, false);
            }
            mLastAppliedDisplayId = match.displayId;
            mLastAppliedModeId = match.modeId;
            mLastAppliedDisplayHz = profile.displayHz;
            mLastError = "";
            mLastApply = reason + ":display=" + match.displayId + ":mode=" + match.modeId
                    + ":" + voteLabel(profile.displayHz) + "=" + profile.displayHz
                    + ":peakBridge=" + peakHz;
        } catch (Throwable t) {
            mLastError = t.getClass().getSimpleName() + ":" + safe(t.getMessage());
            mLastApply = "failed:" + reason;
            Log.w(TAG, "apply display failed", t);
        }
    }

    private int syncPeakRefreshRate(int targetHz) {
        int peakHz = targetHz > 120 ? targetHz : 120;
        String desired = peakHz + ".0";
        long token = Binder.clearCallingIdentity();
        try {
            String current = Settings.System.getString(mContext.getContentResolver(),
                    SETTING_PEAK_REFRESH_RATE);
            if (!desired.equals(current)) {
                Settings.System.putString(mContext.getContentResolver(),
                        SETTING_PEAK_REFRESH_RATE, desired);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
        mLastSyncedPeakHz = peakHz;
        return peakHz;
    }

    private void registerPeakObserver() {
        try {
            mContext.getContentResolver().registerContentObserver(
                    Settings.System.getUriFor(SETTING_PEAK_REFRESH_RATE),
                    false,
                    new ContentObserver(new Handler(Looper.getMainLooper())) {
                        @Override
                        public void onChange(boolean selfChange) {
                            onPeakRefreshRateChanged();
                        }
                    });
        } catch (Throwable t) {
            Log.w(TAG, "peak observer unavailable", t);
        }
    }

    private synchronized void onPeakRefreshRateChanged() {
        if (SystemProperties.getBoolean("persist.zui_control.disable", false)
                || SystemProperties.getBoolean("persist.zui_control.refresh.disable", false)) {
            return;
        }
        if (!isPeakRefreshRateSynced(mTargetDisplayHz)) {
            int peakHz = syncPeakRefreshRate(mTargetDisplayHz);
            mLastError = "";
            mLastApply = "peakObserver:target=" + mTargetDisplayHz + ":peakBridge=" + peakHz;
            publishState();
        }
    }

    private boolean isPeakRefreshRateSynced(int targetHz) {
        int peakHz = targetHz > 120 ? targetHz : 120;
        String desired = peakHz + ".0";
        long token = Binder.clearCallingIdentity();
        try {
            return desired.equals(Settings.System.getString(mContext.getContentResolver(),
                    SETTING_PEAK_REFRESH_RATE));
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private void resetLastApplied() {
        mLastAppliedDisplayId = -1;
        mLastAppliedModeId = -1;
        mLastAppliedDisplayHz = -1;
    }

    private Profile profileFor(String pkg, int userId) {
        Profile profile = mProfiles.get(key(userId, pkg));
        if (profile != null) {
            return profile;
        }
        Profile fallback = defaultProfile(userId);
        return "default".equals(pkg) ? fallback
                : new Profile(pkg, userId, fallback.displayHz, fallback.fpsCap, fallback.mode);
    }

    private Profile defaultProfile(int userId) {
        Profile profile = mProfiles.get(key(userId, "default"));
        return profile != null ? profile : new Profile("default", userId, 120, 0, "DISPLAY_ONLY");
    }

    private boolean isDefaultEquivalent(Profile profile) {
        Profile fallback = defaultProfile(profile.userId);
        return profile.displayHz == fallback.displayHz
                && profile.fpsCap == fallback.fpsCap
                && profile.mode.equals(fallback.mode);
    }

    private void loadProfiles() {
        mProfiles.clear();
        try {
            byte[] raw = mProfileFile.readFully();
            String text = new String(raw, StandardCharsets.UTF_8);
            for (String line : text.split("\n")) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("version=")) {
                    continue;
                }
                String[] parts = line.split("\\|");
                Profile p = null;
                if (parts.length == 5 && "default".equals(parts[0])) {
                    p = makeProfile("default", parseInt(parts[1], 0), parseInt(parts[2], 120),
                            parseInt(parts[3], 0), parts[4]);
                } else if (parts.length == 6 && "pkg".equals(parts[0])) {
                    p = makeProfile(parts[2], parseInt(parts[1], 0), parseInt(parts[3], 120),
                            parseInt(parts[4], 0), parts[5]);
                }
                if (p != null && ("default".equals(p.packageName) || !isDefaultEquivalent(p))) {
                    mProfiles.put(key(p.userId, p.packageName), p);
                }
            }
        } catch (Throwable t) {
            mLastError = "profile_load:" + t.getClass().getSimpleName();
        }
        if (!mProfiles.containsKey(key(0, "default"))) {
            mProfiles.put(key(0, "default"), defaultProfile(0));
        }
    }

    private void saveProfiles() {
        FileOutputStream out = null;
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("# ZuiControl profiles v1\nversion=1\n");
            for (Profile p : mProfiles.values()) {
                if ("default".equals(p.packageName)) {
                    sb.append("default|").append(p.userId).append('|').append(p.displayHz)
                            .append('|').append(p.fpsCap).append('|').append(p.mode).append('\n');
                } else {
                    sb.append("pkg|").append(p.userId).append('|').append(p.packageName)
                            .append('|').append(p.displayHz).append('|').append(p.fpsCap)
                            .append('|').append(p.mode).append('\n');
                }
            }
            out = mProfileFile.startWrite();
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            mProfileFile.finishWrite(out);
            out = null;
        } catch (Throwable t) {
            mLastError = "profile_save:" + t.getClass().getSimpleName();
            if (out != null) {
                mProfileFile.failWrite(out);
            }
        }
    }

    private void applyPhysicalVote(DisplayManagerInternal dmi, int displayId, int hz) throws Exception {
        updatePhysicalVote(dmi, displayId, Float.valueOf(hz));
    }

    private void applyRenderVote(DisplayManagerInternal dmi, int displayId, int hz) throws Exception {
        updateRenderVote(dmi, displayId, Float.valueOf(hz));
    }

    private static boolean useHardPhysicalVote(int hz) {
        return hz <= 120;
    }

    private static String voteLabel(int hz) {
        return useHardPhysicalVote(hz) ? "physicalVote" : "softVote";
    }

    private void clearPhysicalVote(int displayId) {
        try {
            DisplayManagerInternal dmi = LocalServices.getService(DisplayManagerInternal.class);
            if (dmi != null) {
                updatePhysicalVote(dmi, displayId, null);
            }
            resetLastApplied();
        } catch (Throwable t) {
            Log.w(TAG, "clear physical vote failed", t);
        }
    }

    private void updatePhysicalVote(DisplayManagerInternal dmi, int displayId, Float hz)
            throws Exception {
        Object displayManagerService = readField(dmi, "this$0");
        Object director = readField(displayManagerService, "mDisplayModeDirector");
        Object votesStorage = readField(director, "mVotesStorage");
        Class<?> voteClass = Class.forName("com.android.server.display.mode.Vote");
        Object vote = null;
        if (hz != null) {
            Method forPhysical = voteClass.getDeclaredMethod("forPhysicalRefreshRates",
                    float.class, float.class);
            forPhysical.setAccessible(true);
            vote = forPhysical.invoke(null, hz.floatValue(), hz.floatValue());
        }
        Method updateVote = votesStorage.getClass().getDeclaredMethod("updateVote",
                int.class, int.class, voteClass);
        updateVote.setAccessible(true);
        updateVote.invoke(votesStorage, displayId, PRIORITY_ZUI_CONTROL_PHYSICAL, vote);
    }

    private void updateRenderVote(DisplayManagerInternal dmi, int displayId, Float hz)
            throws Exception {
        Object displayManagerService = readField(dmi, "this$0");
        Object director = readField(displayManagerService, "mDisplayModeDirector");
        Object votesStorage = readField(director, "mVotesStorage");
        Class<?> voteClass = Class.forName("com.android.server.display.mode.Vote");
        Object vote = null;
        if (hz != null) {
            Method forRender = voteClass.getDeclaredMethod("forRenderFrameRates",
                    float.class, float.class);
            forRender.setAccessible(true);
            vote = forRender.invoke(null, 0.0f, hz.floatValue());
        }
        Method updateVote = votesStorage.getClass().getDeclaredMethod("updateVote",
                int.class, int.class, voteClass);
        updateVote.setAccessible(true);
        updateVote.invoke(votesStorage, displayId, PRIORITY_ZUI_CONTROL_PHYSICAL, vote);
    }

    private static Object readField(Object target, String name) throws Exception {
        Class<?> c = target.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private synchronized String state() {
        return "ok=1"
                + "\nrawFocusedPackage=" + mRawFocusedPackage
                + "\ncurrentScenePackage=" + mCurrentScenePackage
                + "\nlastNonTransientScenePackage=" + mLastNonTransientScenePackage
                + "\nappliedScenePackage=" + mAppliedScenePackage
                + "\ntargetDisplayHz=" + mTargetDisplayHz
                + "\nactualDisplayHz=" + actualHz()
                + "\ntargetFpsCap=" + mTargetFpsCap
                + "\nmode=" + mTargetMode
                + "\nrefreshOwner=system"
                + "\nsystemServiceAlive=true"
                + "\ndaemonRefreshDisabled=true"
                + "\nsupportedDisplayHz=" + supportedDisplayHz()
                + "\nsupportedFpsCaps=" + supportedFpsCaps(mTargetDisplayHz)
                + "\npeakBridgeHz=" + mLastSyncedPeakHz
                + "\nprofileCount=" + mProfiles.size()
                + profileStateLines()
                + "\nlastApply=" + mLastApply
                + "\nlastError=" + mLastError;
    }

    private String profileStateLines() {
        StringBuilder sb = new StringBuilder();
        for (Profile p : mProfiles.values()) {
            if ("default".equals(p.packageName)) {
                continue;
            }
            sb.append("\nprofile=")
                    .append(p.userId).append('|')
                    .append(p.packageName).append('|')
                    .append(p.displayHz).append('|')
                    .append(p.fpsCap).append('|')
                    .append(p.mode);
        }
        return sb.toString();
    }

    private String capabilities() {
        return "ok=1\nsupportedDisplayHz=" + supportedDisplayHz()
                + "\nsupportedFpsCaps=" + supportedFpsCaps(mTargetDisplayHz)
                + "\nfpsCapPhase=display_divisors_only";
    }

    private void publishState() {
        long token = Binder.clearCallingIdentity();
        try {
            Settings.System.putString(mContext.getContentResolver(),
                    "zui_control_top_package", mCurrentScenePackage);
            Settings.System.putString(mContext.getContentResolver(),
                    "zui_control_active_refresh", String.valueOf(mTargetDisplayHz));
            Settings.System.putString(mContext.getContentResolver(),
                    "zui_control_scene_event_text",
                    android.os.SystemClock.elapsedRealtimeNanos() + "|" + mCurrentScenePackage);
            Settings.System.putString(mContext.getContentResolver(),
                    "zui_control_status_text", state());
        } catch (Throwable ignored) {
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private void enforceCallerAllowed() {
        int uid = Binder.getCallingUid();
        if (uid == android.os.Process.SYSTEM_UID) {
            return;
        }
        String[] packages = mPm.getPackagesForUid(uid);
        if (packages == null || !Arrays.asList(packages).contains(APP_PACKAGE)) {
            throw new SecurityException("caller package is not ZuiControl");
        }
        boolean certOk = mPm.hasSigningCertificate(uid, hex(RELEASE_CERT),
                PackageManager.CERT_INPUT_SHA256)
                || mPm.hasSigningCertificate(uid, hex(DEBUG_CERT),
                PackageManager.CERT_INPUT_SHA256);
        if (!certOk) {
            throw new SecurityException("ZuiControl cert mismatch");
        }
    }

    private boolean isControlPanelContext() {
        return APP_PACKAGE.equals(mRawFocusedPackage)
                || APP_PACKAGE.equals(mAppliedScenePackage);
    }

    private boolean shouldApplyPackageNow(String pkg) {
        return safe(pkg).equals(mAppliedScenePackage);
    }

    private boolean packageExists(String pkg) {
        try {
            ApplicationInfo info = mPm.getApplicationInfo(pkg, 0);
            return info != null;
        } catch (Throwable t) {
            return false;
        }
    }

    private int resolveDisplayId(int displayId) {
        if (mDisplayManager != null && mDisplayManager.getDisplay(displayId) != null) {
            return displayId;
        }
        if (mDisplayManager != null && mDisplayManager.getDisplay(Display.DEFAULT_DISPLAY) != null) {
            return Display.DEFAULT_DISPLAY;
        }
        Display[] displays = mDisplayManager == null ? new Display[0] : mDisplayManager.getDisplays();
        return displays.length == 0 ? Display.DEFAULT_DISPLAY : displays[0].getDisplayId();
    }

    private boolean isDisplayHzSupported(int hz) {
        if (!isDisplayHzAllowed(hz)) {
            return false;
        }
        return findMode(hz, resolveDisplayId(mCurrentDisplayId)) != null;
    }

    private boolean isDisplayHzAllowed(int hz) {
        for (int allowed : DISPLAY_HZ) {
            if (allowed == hz) {
                return true;
            }
        }
        return false;
    }

    private ModeMatch findMode(int hz, int preferredDisplayId) {
        if (mDisplayManager == null) {
            return null;
        }
        Display preferred = mDisplayManager.getDisplay(preferredDisplayId);
        ModeMatch match = findModeOnDisplay(preferred, hz);
        if (match != null) {
            return match;
        }
        for (Display display : mDisplayManager.getDisplays()) {
            match = findModeOnDisplay(display, hz);
            if (match != null) {
                return match;
            }
        }
        return null;
    }

    private ModeMatch findModeOnDisplay(Display display, int hz) {
        if (display == null) {
            return null;
        }
        for (Display.Mode mode : display.getSupportedModes()) {
            if (Math.abs(mode.getRefreshRate() - hz) <= 0.5f) {
                return new ModeMatch(display.getDisplayId(), mode.getModeId());
            }
        }
        return null;
    }

    private String supportedDisplayHz() {
        StringBuilder sb = new StringBuilder();
        for (int hz : DISPLAY_HZ) {
            if (isDisplayHzSupported(hz)) {
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append(hz);
            }
        }
        return sb.toString();
    }

    private int actualHz() {
        Display display = mDisplayManager == null ? null : mDisplayManager.getDisplay(mCurrentDisplayId);
        if (display == null || display.getMode() == null) {
            return 0;
        }
        return Math.round(display.getMode().getRefreshRate());
    }

    private String supportedFpsCaps(int displayHz) {
        StringBuilder sb = new StringBuilder();
        for (int fps = displayHz; fps >= 24; fps--) {
            if (displayHz % fps == 0) {
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append(fps);
            }
        }
        return sb.toString();
    }

    private static boolean isTransientPackage(String pkg) {
        String p = safe(pkg).toLowerCase(Locale.US);
        return p.equals("com.android.systemui")
                || p.equals(APP_PACKAGE)
                || p.equals("android")
                || p.contains("permissioncontroller")
                || p.contains("packageinstaller")
                || p.contains("resolver")
                || p.contains("chooser")
                || p.contains("inputmethod")
                || p.contains("keyboard")
                || p.contains("overlay");
    }

    private static String normalizeMode(String mode) {
        String m = safe(mode).toUpperCase(Locale.US);
        if (m.isEmpty()) {
            return "DISPLAY_ONLY";
        }
        if ("DISPLAY_ONLY".equals(m) || "FPS_CAP_ONLY".equals(m) || "DISPLAY_AND_FPS".equals(m)) {
            return m;
        }
        return null;
    }

    private static boolean validPackage(String pkg) {
        if (pkg == null || pkg.isEmpty() || pkg.startsWith(".") || pkg.endsWith(".")
                || pkg.contains("..")) {
            return false;
        }
        for (int i = 0; i < pkg.length(); i++) {
            char c = pkg.charAt(i);
            if (!(c == '.' || c == '_' || (c >= '0' && c <= '9')
                    || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z'))) {
                return false;
            }
        }
        return true;
    }

    private static int parseInt(String value, int def) {
        try {
            return Integer.parseInt(value);
        } catch (Throwable t) {
            return def;
        }
    }

    private static String key(int userId, String pkg) {
        return userId + ":" + pkg;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static byte[] hex(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static final class Profile {
        final String packageName;
        final int userId;
        final int displayHz;
        final int fpsCap;
        final String mode;

        Profile(String packageName, int userId, int displayHz, int fpsCap, String mode) {
            this.packageName = packageName;
            this.userId = userId;
            this.displayHz = displayHz;
            this.fpsCap = fpsCap;
            this.mode = mode;
        }
    }

    private static final class ModeMatch {
        final int displayId;
        final int modeId;

        ModeMatch(int displayId, int modeId) {
            this.displayId = displayId;
            this.modeId = modeId;
        }
    }
}
