package com.zui.server.control;

import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.hardware.display.DisplayManager;
import android.hardware.display.DisplayManagerInternal;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Parcel;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.provider.Settings;
import android.util.AtomicFile;
import android.util.Log;
import android.view.Display;

import com.android.server.LocalServices;
import com.android.server.wm.ActivityRecord;
import com.android.server.wm.ActivityTaskSupervisor;
import com.android.server.wm.WindowManagerInternal;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public final class ZuiControlService extends Binder {
    private static final String TAG = "ZuiControl";
    private static final String TIMING_TAG = "ZuiControlTiming";
    private static final String DESCRIPTOR = "android.zui.IZuiControl";
    private static final String APP_PACKAGE = "com.zui.zuicontrol";
    private static final String DATA_DIR = "/data/system/zui_control";
    private static final String PROFILE_FILE = DATA_DIR + "/profiles.prop";
    private static final String SETTING_PEAK_REFRESH_RATE = "peak_refresh_rate";
    private static final String SETTING_UPERF_MODE = "zui_control_uperf_mode";
    private static final String SETTING_UPERF_RULES = "zui_control_uperf_rules_text";
    private static final String SETTING_REQUEST_TEXT = "zui_control_request_text";
    private static final String PROP_UPERF_MODE = "sys.zui_control.uperf_mode";
    private static final String PROP_COMMAND_ID = "sys.zui_control.command_id";
    private static final String PROP_COMMAND_SHA256 = "sys.zui_control.command_sha256";
    private static final String PROP_COMMAND_SEQ = "sys.zui_control.command_seq";
    private static final String PROP_SCHEDULER_ACTIVE = "sys.zui_control.scheduler_active";
    private static final String PROP_UPERF_FAIL_SAFE = "sys.zui_control.uperf_fail_safe";
    private static final String PROP_UPERF_SERVICE = "init.svc.zui_uperf";
    private static final String PROP_ASOUL_SERVICE = "init.svc.zui_asoulopt";
    private static final String PROP_GLOBAL_DISABLE = "persist.zui_control.disable";
    private static final String PROP_REFRESH_DISABLE = "persist.zui_control.refresh.disable";
    private static final String GAME_HELPER_PACKAGE = "com.zui.game.service";
    private static final String SCREEN_SPLIT_CONTROL_PACKAGE = "com.lenovo.screensplit";
    private static final String FREEFORM_SIDEBAR_PACKAGE = "com.zui.freeform.sidebar";
    private static final String DEFAULT_SCENE = "default";
    private static final String IME_SCENE = "@ime";
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
    private static final int TX_NOTIFY_CONTROL_REQUEST = 12;
    private static final long TOP_RESUMED_NULL_REVALIDATE_DELAY_MS = 64L;
    private static final int PRIORITY_ZUI_CONTROL_RENDER = 8;
    private static final int[] DISPLAY_HZ = new int[] {60, 90, 120, 144, 165};
    private static volatile ZuiControlService sInstance;

    private final Context mContext;
    private final PackageManager mPm;
    private final DisplayManager mDisplayManager;
    private final AtomicFile mProfileFile;
    private final Handler mWorker;
    private final UperfScenePolicy mUperfScenePolicy;
    private final AtomicLong mTopResumedCallbackGeneration = new AtomicLong();
    private final TopResumedNullState mTopResumedState = new TopResumedNullState();
    private ActivityTaskSupervisor mTopResumedAuthority;
    private final Runnable mTopResumedNullRevalidation = new Runnable() {
        @Override
        public void run() {
            revalidateTopResumed("deferred");
        }
    };
    private final Map<String, Profile> mProfiles = new HashMap<>();

    private volatile FocusSnapshot mLatestFocus = new FocusSnapshot(
            "", -1, 0, Display.DEFAULT_DISPLAY, true);
    private volatile FocusSnapshot mLatestActivityFocus = new FocusSnapshot(
            "", -1, 0, Display.DEFAULT_DISPLAY, true);
    private volatile FocusSnapshot mLatestNonImeFocus = new FocusSnapshot(
            "", -1, 0, Display.DEFAULT_DISPLAY, true);
    private volatile boolean mLatestWindowFocusSeen;
    private volatile boolean mLatestWindowFocusEmpty;
    private volatile boolean mLatestImeVisible;
    private String mActivityFocusedPackage = "";
    private int mActivityFocusedUid = -1;
    private int mActivityFocusedUserId = 0;
    private int mActivityFocusedDisplayId = Display.DEFAULT_DISPLAY;
    private String mNonImeFocusedPackage = "";
    private int mNonImeFocusedUid = -1;
    private int mNonImeFocusedUserId = 0;
    private int mNonImeFocusedDisplayId = Display.DEFAULT_DISPLAY;
    private boolean mNonImeFocusTransient = true;
    private boolean mWindowFocusSeen;
    private boolean mImeVisible;
    private boolean mEmptyFocusTransitionPending;
    private int mEmptyFocusTransitionCount;
    private String mLastEmptyFocusActivityPackage = "";
    private String mLastEmptyFocusRetainedPackage = "";
    private String mRawFocusedPackage = "";
    private int mRawFocusedUserId = 0;
    private int mRawFocusedDisplayId = Display.DEFAULT_DISPLAY;
    private boolean mRawFocusTransient = true;
    private String mCurrentScenePackage = "";
    private String mLastNonTransientScenePackage = "";
    private String mDesiredScenePackage = DEFAULT_SCENE;
    private String mAttemptedScenePackage = "";
    private String mAppliedScenePackage = "";
    private int mCurrentUserId = 0;
    private int mCurrentUid = -1;
    private int mTargetDisplayHz = 120;
    private int mAttemptedDisplayHz = 0;
    private int mAppliedDisplayHz = 0;
    private int mTargetFpsCap = 0;
    private String mTargetMode = "DISPLAY_ONLY";
    private String mLastApplyReason = "init";
    private String mLastApplyError = "";
    private String mLastError = "";
    private String mLastSchedulerError = "";
    private int mLastAppliedDisplayId = -1;
    private int mLastAppliedModeId = -1;
    private int mLastAppliedDisplayHz = -1;
    private int mLastSyncedPeakHz = -1;
    private boolean mPeakBridgeOwned;
    private String mPeakRestoreValue;
    private String mPeakLastWritten = "";
    private String mPeakReleaseStatus = "notOwned";
    private boolean mRenderVoteOwned;
    private int mRenderVoteHz;
    private String mRenderVoteReleaseStatus = "notOwned";
    private boolean mAppRequestOwned;
    private String mAppRequestHandoff = "notRequested";
    private boolean mAppRequestHandoffPending;
    private boolean mRefreshDisabled;
    private int mRefreshDisableMask;
    private volatile int mObservedRefreshDisableMask;
    private final Object mRefreshPropertyLock = new Object();
    private boolean mDisableReleaseRetryPosted;
    private int mRefreshApplyCount;
    private int mSkipSameCount;
    private boolean mScreenInteractive = true;
    private long mCommandSequence;
    public ZuiControlService(Context context) {
        mContext = context;
        mPm = context.getPackageManager();
        mDisplayManager = (DisplayManager) context.getSystemService(DisplayManager.class);
        HandlerThread workerThread = new HandlerThread("ZuiControl");
        workerThread.start();
        mWorker = new Handler(workerThread.getLooper());
        mUperfScenePolicy = new UperfScenePolicy();
        File dir = new File(DATA_DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "failed to create " + DATA_DIR);
        }
        mProfileFile = new AtomicFile(new File(PROFILE_FILE));
        loadProfiles();
        mRefreshDisableMask = readRefreshDisableMask();
        mObservedRefreshDisableMask = mRefreshDisableMask;
        mRefreshDisabled = mRefreshDisableMask != 0;
        sInstance = this;
        attachInterface(null, DESCRIPTOR);
        registerPeakObserver();
        registerRefreshPropertyObserver();
        registerScreenObserver();
        mUperfScenePolicy.start(mScreenInteractive);
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
        if (displayId != Display.DEFAULT_DISPLAY) {
            return;
        }
        final long eventNanos = SystemClock.elapsedRealtimeNanos();
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
        final boolean transientFocus = pkg.isEmpty() || isTransientPackage(pkg);
        final FocusSnapshot activityFocus = new FocusSnapshot(
                pkg, uid, userId, displayId, transientFocus);
        mLatestActivityFocus = activityFocus;
        final boolean windowAuthority = mLatestWindowFocusSeen;
        if (!windowAuthority) {
            mLatestNonImeFocus = activityFocus;
            if (!mLatestImeVisible) {
                mLatestFocus = activityFocus;
            }
        } else {
            FocusSnapshot nonImeFocus = mLatestNonImeFocus;
            if (pkg.equals(nonImeFocus.packageName)) {
                FocusSnapshot enrichedFocus = new FocusSnapshot(pkg, uid, userId,
                        nonImeFocus.displayId, nonImeFocus.transientFocus);
                mLatestNonImeFocus = enrichedFocus;
                if (!mLatestImeVisible) {
                    mLatestFocus = enrichedFocus;
                }
            }
        }
        mWorker.post(new Runnable() {
            @Override
            public void run() {
                handleFocusedActivity(activityFocus, windowAuthority, eventNanos);
            }
        });
    }

    public void onTopResumedActivityChanged(
            ActivityTaskSupervisor authority, ActivityRecord record) {
        final long generation = mTopResumedCallbackGeneration.incrementAndGet();
        final long eventNanos = SystemClock.elapsedRealtimeNanos();
        final String pkg = record == null ? "" : safe(record.packageName);
        final int userId = record == null ? 0 : record.mUserId;
        mWorker.post(new Runnable() {
            @Override
            public void run() {
                handleTopResumedActivityChanged(
                        authority, generation, pkg, userId, eventNanos);
            }
        });
    }

    private synchronized void handleTopResumedActivityChanged(
            ActivityTaskSupervisor authority, long generation, String pkg,
            int userId, long eventNanos) {
        mTopResumedAuthority = authority;
        if (!pkg.isEmpty()) {
            mWorker.removeCallbacks(mTopResumedNullRevalidation);
            if (!mTopResumedState.acceptValid(generation, pkg, userId)) {
                return;
            }
            Log.i(TAG, "uperf_top_resumed event=topResumedValid generation="
                    + generation + " package=" + pkg + " userId=" + userId);
            mUperfScenePolicy.onTopResumedChanged(
                    pkg, userId, "topResumedValid", eventNanos);
            publishState();
            return;
        }
        if (!mTopResumedState.deferNull(generation)) {
            return;
        }
        mWorker.removeCallbacks(mTopResumedNullRevalidation);
        mWorker.postDelayed(mTopResumedNullRevalidation,
                TOP_RESUMED_NULL_REVALIDATE_DELAY_MS);
        Log.i(TAG, "uperf_top_resumed event=topResumedNullDeferred generation="
                + generation + " delayMs=" + TOP_RESUMED_NULL_REVALIDATE_DELAY_MS
                + " stablePackage=" + mTopResumedState.stablePackage());
    }

    private synchronized void revalidateTopResumed(String trigger) {
        final long generation = mTopResumedState.pendingGeneration();
        if (!mTopResumedState.isPending(generation)
                || generation != mTopResumedCallbackGeneration.get()) {
            Log.i(TAG, "uperf_top_resumed event=topResumedRevalidateStale generation="
                    + generation + " trigger=" + trigger);
            return;
        }
        final ActivityRecord current;
        try {
            if (mTopResumedAuthority == null) {
                throw new IllegalStateException("missing authority");
            }
            current = mTopResumedAuthority.getZuiControlTopResumedActivity();
        } catch (Throwable t) {
            mTopResumedState.authorityError(generation);
            Log.w(TAG, "uperf_top_resumed event=topResumedRevalidateAuthorityError"
                    + " generation=" + generation + " trigger=" + trigger, t);
            return;
        }
        if (generation != mTopResumedCallbackGeneration.get()
                || !mTopResumedState.isPending(generation)) {
            Log.i(TAG, "uperf_top_resumed event=topResumedRevalidateStale generation="
                    + generation + " trigger=" + trigger);
            return;
        }
        final String pkg = current == null ? "" : safe(current.packageName);
        final int userId = current == null ? 0 : current.mUserId;
        final int result = mTopResumedState.revalidate(generation, pkg, userId);
        if (result == TopResumedNullState.REVALIDATE_STALE) {
            return;
        }
        final boolean nullConfirmed =
                result == TopResumedNullState.REVALIDATE_NULL_CONFIRMED;
        final String event = nullConfirmed
                ? "topResumedNullConfirmed" : "topResumedRevalidated";
        Log.i(TAG, "uperf_top_resumed event=" + event + " generation=" + generation
                + " package=" + pkg + " userId=" + userId + " trigger=" + trigger);
        if (result == TopResumedNullState.REVALIDATE_SAME) {
            return;
        }
        mUperfScenePolicy.onTopResumedChanged(pkg, userId, event,
                SystemClock.elapsedRealtimeNanos());
        publishState();
    }

    public void onFocusedWindowChanged(String packageName, int displayId) {
        if (displayId != Display.DEFAULT_DISPLAY) {
            return;
        }
        final String pkg = safe(packageName);
        if (pkg.isEmpty()) {
            if (mLatestWindowFocusEmpty) {
                return;
            }
            mLatestWindowFocusEmpty = true;
            final FocusSnapshot activityFocus = mLatestActivityFocus;
            mWorker.post(new Runnable() {
                @Override
                public void run() {
                    handleEmptyFocusTransition(activityFocus);
                }
            });
            return;
        }
        boolean wasEmpty = mLatestWindowFocusEmpty;
        final boolean transientFocus = isTransientPackage(pkg);
        boolean firstWindowFocus = !mLatestWindowFocusSeen;
        mLatestWindowFocusSeen = true;
        FocusSnapshot previousFocus = mLatestNonImeFocus;
        if (!firstWindowFocus && !wasEmpty && pkg.equals(previousFocus.packageName)
                && displayId == previousFocus.displayId
                && transientFocus == previousFocus.transientFocus) {
            return;
        }
        FocusSnapshot activityFocus = mLatestActivityFocus;
        final FocusSnapshot windowFocus = new FocusSnapshot(pkg, activityFocus.uid,
                activityFocus.userId, displayId, transientFocus);
        final long eventNanos = SystemClock.elapsedRealtimeNanos();
        mLatestNonImeFocus = windowFocus;
        if (!mLatestImeVisible) {
            mLatestFocus = windowFocus;
        }
        mLatestWindowFocusEmpty = false;
        mWorker.post(new Runnable() {
            @Override
            public void run() {
                handleFocusedWindow(windowFocus, eventNanos);
            }
        });
    }

    public void onImeVisibilityChanged(String packageName, boolean visible, int displayId) {
        if (displayId != Display.DEFAULT_DISPLAY) {
            return;
        }
        final String pkg = visible && safe(packageName).isEmpty()
                ? IME_SCENE : safe(packageName);
        FocusSnapshot latestFocus = mLatestFocus;
        if (mLatestImeVisible == visible
                && (!visible || (pkg.equals(latestFocus.packageName)
                && displayId == latestFocus.displayId))) {
            return;
        }
        final long eventNanos = SystemClock.elapsedRealtimeNanos();
        mLatestImeVisible = visible;
        if (visible) {
            FocusSnapshot activityFocus = mLatestActivityFocus;
            mLatestFocus = new FocusSnapshot(
                    pkg, -1, activityFocus.userId, displayId, true);
        } else {
            mLatestFocus = mLatestNonImeFocus;
        }
        mWorker.post(new Runnable() {
            @Override
            public void run() {
                handleImeVisibility(pkg, visible, displayId, eventNanos);
            }
        });
    }

    private synchronized void handleFocusedActivity(
            FocusSnapshot activityFocus, boolean windowAuthority, long eventNanos) {
        mActivityFocusedPackage = activityFocus.packageName;
        mActivityFocusedUid = activityFocus.uid;
        mActivityFocusedUserId = activityFocus.userId;
        mActivityFocusedDisplayId = resolveDisplayId(activityFocus.displayId);
        if (windowAuthority) {
            if (activityFocus.packageName.equals(mNonImeFocusedPackage)) {
                setNonImeFocus(activityFocus.packageName, activityFocus.uid,
                        activityFocus.userId, mNonImeFocusedDisplayId,
                        mNonImeFocusTransient);
                if (!mImeVisible
                        && activityFocus.packageName.equals(mRawFocusedPackage)) {
                    mRawFocusedUserId = activityFocus.userId;
                    if (!mRawFocusTransient) {
                        mCurrentUid = activityFocus.uid;
                        mCurrentUserId = activityFocus.userId;
                    }
                }
            }
            publishState();
            return;
        }
        setNonImeFocus(activityFocus.packageName, activityFocus.uid,
                activityFocus.userId, activityFocus.displayId,
                activityFocus.transientFocus);
        if (!activityFocus.transientFocus) {
            updateBusinessScene(activityFocus.packageName, activityFocus.uid,
                    activityFocus.userId, activityFocus.displayId);
        }
        if (mImeVisible) {
            publishState();
            return;
        }
        handleEffectiveFocus(activityFocus.packageName, activityFocus.uid,
                activityFocus.userId, activityFocus.displayId,
                activityFocus.transientFocus, "focus", eventNanos);
    }

    private synchronized void handleFocusedWindow(
            FocusSnapshot windowFocus, long eventNanos) {
        mEmptyFocusTransitionPending = false;
        mWindowFocusSeen = true;
        setNonImeFocus(windowFocus.packageName, windowFocus.uid,
                windowFocus.userId, windowFocus.displayId,
                windowFocus.transientFocus);
        if (mImeVisible) {
            publishState();
            return;
        }
        handleEffectiveFocus(windowFocus.packageName, windowFocus.uid,
                windowFocus.userId, windowFocus.displayId,
                windowFocus.transientFocus, "windowFocus", eventNanos);
    }

    private synchronized void handleEmptyFocusTransition(FocusSnapshot activityFocus) {
        mEmptyFocusTransitionPending = true;
        mEmptyFocusTransitionCount++;
        mLastEmptyFocusActivityPackage = activityFocus.packageName;
        mLastEmptyFocusRetainedPackage = mRawFocusedPackage;
    }

    private synchronized void handleImeVisibility(
            String pkg, boolean visible, int displayId, long eventNanos) {
        mImeVisible = visible;
        if (visible) {
            handleEffectiveFocus(pkg, -1, mActivityFocusedUserId,
                    displayId, true, "imeVisible", eventNanos);
        } else {
            handleEffectiveFocus(mNonImeFocusedPackage, mNonImeFocusedUid,
                    mNonImeFocusedUserId, mNonImeFocusedDisplayId,
                    mNonImeFocusTransient, "imeHidden", eventNanos);
        }
    }

    private void setNonImeFocus(String pkg, int uid, int userId, int displayId,
            boolean transientFocus) {
        mNonImeFocusedPackage = safe(pkg);
        mNonImeFocusedUid = uid;
        mNonImeFocusedUserId = userId;
        mNonImeFocusedDisplayId = resolveDisplayId(displayId);
        mNonImeFocusTransient = transientFocus;
    }

    private void handleEffectiveFocus(
            String pkg, int uid, int userId, int displayId, boolean forceTransient,
            String source, long eventNanos) {
        mRawFocusedPackage = safe(pkg);
        mRawFocusedUserId = userId;
        mRawFocusedDisplayId = resolveDisplayId(displayId);
        Profile refreshProfile;
        boolean transientFocus = forceTransient || mImeVisible || mRawFocusedPackage.isEmpty()
                || isTransientPackage(mRawFocusedPackage);
        mRawFocusTransient = transientFocus;
        if (transientFocus) {
            refreshProfile = neutralProfile(userId);
        } else {
            updateBusinessScene(mRawFocusedPackage, uid, userId, mRawFocusedDisplayId);
            refreshProfile = profileFor(mCurrentScenePackage, mCurrentUserId);
        }
        String reason = transientFocus
                ? source + "Transient" : source;
        applyProfile(refreshProfile, reason, false);
        publishState();
    }

    private void updateBusinessScene(
            String pkg, int uid, int userId, int displayId) {
        if (safe(pkg).isEmpty() || isTransientPackage(pkg)) {
            return;
        }
        mCurrentScenePackage = pkg;
        mLastNonTransientScenePackage = pkg;
        mCurrentUid = uid;
        mCurrentUserId = userId;
    }

    @Override
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        try {
            if (code >= 1 && code <= TX_NOTIFY_CONTROL_REQUEST) {
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
                    result = currentSceneState();
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
                    enforceCommandCallerAllowed();
                    result = setModuleEnabled(data.readString(), data.readInt() != 0);
                    break;
                case TX_EXPORT_LOG:
                    enforceCallerAllowed();
                    result = state();
                    break;
                case TX_NOTIFY_CONTROL_REQUEST:
                    enforceCommandCallerAllowed();
                    result = notifyControlRequest(data.readString(), data.readString());
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
                isForegroundBusinessPackage(pkg, mCurrentUserId));
    }

    private synchronized String setProfile(String pkg, int userId, int displayHz, int fpsCap, String mode) {
        if (!validPackage(pkg)) {
            return "ok=0\nerror=invalid_package";
        }
        if (isTransientPackage(pkg)) {
            return "ok=0\nerror=transient_package_not_configurable";
        }
        if (!packageExists(pkg)) {
            return "ok=0\nerror=package_not_found";
        }
        return setProfileLocked(pkg, userId, displayHz, fpsCap, mode,
                isForegroundBusinessPackage(pkg, userId));
    }

    private synchronized String setModuleEnabled(String module, boolean enabled) {
        if (!"refresh".equals(module)) {
            return "ok=0\nerror=unsupported_module";
        }
        String persistentValue = enabled ? "0" : "1";
        try {
            if (!persistentValue.equals(SystemProperties.get(PROP_REFRESH_DISABLE, "0"))) {
                SystemProperties.set(PROP_REFRESH_DISABLE, persistentValue);
            }
            onRefreshPropertiesChanged(readRefreshDisableMask());
            return "ok=1\nmodule=refresh"
                    + "\nrequestedEnabled=" + enabled
                    + "\nrefreshDisabled=" + mRefreshDisabled
                    + "\nrefreshDisableMask=" + mRefreshDisableMask
                    + "\npersistentValue=" + persistentValue;
        } catch (Throwable t) {
            mLastError = "refresh_property_set:" + throwableText(t);
            return "ok=0\nerror=" + mLastError;
        }
    }

    private String setProfileLocked(String pkg, int userId, int displayHz, int fpsCap,
            String mode, boolean applyNow) {
        Profile profile = makeProfile(pkg, userId, displayHz, fpsCap, mode);
        if (profile == null) {
            return "ok=0\nerror=" + mLastError;
        }
        if (!"default".equals(pkg) && isDefaultEquivalent(profile)) {
            String profileKey = key(userId, pkg);
            Profile previous = mProfiles.remove(profileKey);
            if (!saveProfiles()) {
                if (previous != null) {
                    mProfiles.put(profileKey, previous);
                }
                return "ok=0\nerror=" + mLastError;
            }
            Profile fallback = profileFor(pkg, userId);
            String applyStatus = "savedOnly";
            if (applyNow) {
                applyStatus = applyProfile(fallback, "restoreDefault", false);
            }
            publishState();
            if ("failed".equals(applyStatus)) {
                return "ok=0\nerror=refresh_apply_failed\nprofileSaved=1"
                        + "\npackage=" + pkg + "\nremovedProfile=1"
                        + "\napplyError=" + mLastApplyError;
            }
            return "ok=1\npackage=" + pkg + "\nremovedProfile=1"
                    + "\ndisplayHz=" + fallback.displayHz
                    + "\nfpsCap=" + fallback.fpsCap + "\nmode=" + fallback.mode
                    + "\napplyStatus=" + applyStatus
                    + "\napplyError="
                    + ("savedOnly".equals(applyStatus) ? "" : mLastApplyError);
        }
        String profileKey = key(userId, pkg);
        Profile previous = mProfiles.put(profileKey, profile);
        if (!saveProfiles()) {
            if (previous == null) {
                mProfiles.remove(profileKey);
            } else {
                mProfiles.put(profileKey, previous);
            }
            return "ok=0\nerror=" + mLastError;
        }
        String applyStatus = "savedOnly";
        if (applyNow) {
            applyStatus = applyProfile(profile, "binder", false);
        }
        publishState();
        if ("failed".equals(applyStatus)) {
            return "ok=0\nerror=refresh_apply_failed\nprofileSaved=1"
                    + "\npackage=" + pkg + "\napplyError=" + mLastApplyError;
        }
        return "ok=1\npackage=" + pkg + "\ndisplayHz=" + profile.displayHz
                + "\nfpsCap=" + profile.fpsCap + "\nmode=" + profile.mode
                + "\napplyStatus=" + applyStatus
                + "\napplyError="
                + ("savedOnly".equals(applyStatus) ? "" : mLastApplyError);
    }

    private synchronized String removeProfile(String pkg, int userId) {
        if (!validPackage(pkg)) {
            return "ok=0\nerror=invalid_package";
        }
        String profileKey = key(userId, pkg);
        Profile previous = mProfiles.remove(profileKey);
        if (!saveProfiles()) {
            if (previous != null) {
                mProfiles.put(profileKey, previous);
            }
            return "ok=0\nerror=" + mLastError;
        }
        String applyStatus = "savedOnly";
        if (isForegroundBusinessPackage(pkg, userId)) {
            applyStatus = applyProfile(profileFor(pkg, userId), "remove", false);
        }
        publishState();
        if ("failed".equals(applyStatus)) {
            return "ok=0\nerror=refresh_apply_failed\nprofileSaved=1"
                    + "\nremoved=" + pkg + "\napplyError=" + mLastApplyError;
        }
        return "ok=1\nremoved=" + pkg
                + "\napplyStatus=" + applyStatus
                + "\napplyError="
                + ("savedOnly".equals(applyStatus) ? "" : mLastApplyError);
    }

    private synchronized String refreshNow() {
        if (mLatestWindowFocusEmpty) {
            return "ok=0\nerror=empty_focus_transition";
        }
        FocusSnapshot latestFocus = mLatestFocus;
        if (!latestFocus.packageName.equals(mRawFocusedPackage)
                || latestFocus.userId != mRawFocusedUserId
                || resolveDisplayId(latestFocus.displayId) != mRawFocusedDisplayId
                || latestFocus.transientFocus != mRawFocusTransient) {
            return "ok=0\nerror=focus_update_pending";
        }
        String applyStatus = reconcileFocusedProfile("refreshNow", true);
        publishState();
        if ("failed".equals(applyStatus)) {
            return "ok=0\nerror=" + mLastApplyError + "\n" + state();
        }
        return "ok=1\napplyStatus=" + applyStatus + "\n" + state();
    }

    private synchronized String currentSceneState() {
        return "ok=1\ncurrentScenePackage=" + mCurrentScenePackage
                + "\nlastNonTransientScenePackage=" + mLastNonTransientScenePackage
                + "\neditableScenePackage=" + editableScenePackage()
                + "\neditableDisplayHz=" + editableDisplayHz();
    }

    private synchronized String notifyControlRequest(String requestId, String requestSha256) {
        long binderEnterNanos = SystemClock.elapsedRealtimeNanos();
        String id = safe(requestId).trim();
        String sha256 = safe(requestSha256).trim();
        Log.i(TIMING_TAG, "id=" + id + " phase=T1 ns=" + binderEnterNanos);
        if (!validRequestId(id)) {
            return "ok=0\nerror=invalid_request_id";
        }
        if (!validSha256(sha256)) {
            return "ok=0\nerror=invalid_request_sha256";
        }
        String token = Long.toHexString(SystemClock.elapsedRealtimeNanos())
                + "_" + Long.toHexString(++mCommandSequence);
        long identity = Binder.clearCallingIdentity();
        try {
            String requestText = Settings.System.getString(
                    mContext.getContentResolver(), SETTING_REQUEST_TEXT);
            String[] fields = safe(requestText).split("\\|", -1);
            if (fields.length != 5 || !id.equals(fields[0]) || fields[1].isEmpty()
                    || !fields[2].isEmpty() || !sha256(safe(requestText)).equals(sha256)) {
                return "ok=0\nerror=request_payload_mismatch";
            }
            if (!id.equals(SystemProperties.get(PROP_COMMAND_ID, ""))) {
                SystemProperties.set(PROP_COMMAND_ID, id);
            }
            if (!sha256.equals(SystemProperties.get(PROP_COMMAND_SHA256, ""))) {
                SystemProperties.set(PROP_COMMAND_SHA256, sha256);
            }
            SystemProperties.set(PROP_COMMAND_SEQ, token);
            long sequenceSetNanos = SystemClock.elapsedRealtimeNanos();
            Log.i(TIMING_TAG, "id=" + id + " phase=T2 ns=" + sequenceSetNanos);
            Log.i(TAG, "control_request_kick id=" + id + " sha256="
                    + sha256.substring(0, 12) + " token=" + token);
            return "ok=1\nrequestId=" + id + "\ncommandSeq=" + token;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
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

    private String reconcileFocusedProfile(String reason, boolean force) {
        Profile profile = mRawFocusTransient || mImeVisible || mRawFocusedPackage.isEmpty()
                || isTransientPackage(mRawFocusedPackage)
                ? neutralProfile(mRawFocusedUserId)
                : profileFor(mRawFocusedPackage, mRawFocusedUserId);
        return applyProfile(profile, reason, force);
    }

    private String applyProfile(Profile profile, String reason, boolean force) {
        mDesiredScenePackage = profile.packageName;
        mTargetDisplayHz = profile.displayHz;
        mTargetFpsCap = profile.fpsCap;
        mTargetMode = profile.mode;
        if (mRefreshDisabled || readRefreshDisableMask() != 0) {
            if (mRefreshDisabled && hasResidualRefreshOwnership()) {
                scheduleDisableReleaseRetry();
            }
            if (mLastApplyError.isEmpty()) {
                mLastApplyReason = reason + ":disabled";
            }
            return "disabled";
        }
        ModeMatch match = findMode(profile.displayHz, mRawFocusedDisplayId);
        if (match == null) {
            return failApplyBeforeMutation(reason, "no_display_mode_" + profile.displayHz);
        }
        DisplayManagerInternal dmi = LocalServices.getService(DisplayManagerInternal.class);
        if (dmi == null) {
            return failApplyBeforeMutation(reason, "DisplayManagerInternal_unavailable");
        }
        if (!force && mAppRequestOwned && mRenderVoteOwned
                && mRenderVoteHz == profile.displayHz
                && mLastAppliedDisplayId == match.displayId
                && mLastAppliedModeId == match.modeId
                && mLastAppliedDisplayHz == profile.displayHz) {
            mAppliedScenePackage = profile.packageName;
            mAppliedDisplayHz = profile.displayHz;
            mSkipSameCount++;
            mLastApplyError = "";
            mLastError = "";
            mLastApplyReason = reason + ":display=" + match.displayId
                    + ":mode=" + match.modeId + ":skipSame";
            return "skipSame";
        }

        mAttemptedScenePackage = profile.packageName;
        mAttemptedDisplayHz = profile.displayHz;
        int peakHz;
        try {
            peakHz = syncPeakRefreshRate(profile.displayHz);
        } catch (Throwable t) {
            return failApplyAfterMutation(reason, "peak:" + throwableText(t), false);
        }
        try {
            applyGlobalRenderVote(dmi, profile.displayHz);
        } catch (Throwable t) {
            return failApplyAfterMutation(reason, "renderVote:" + throwableText(t), false);
        }
        try {
            dmi.setDisplayProperties(match.displayId, true, profile.displayHz,
                    match.modeId, 0.0f, profile.displayHz, false, false, false);
            mAppRequestOwned = true;
            mAppRequestHandoff = "active";
            mAppRequestHandoffPending = false;
            mLastAppliedDisplayId = match.displayId;
            mLastAppliedModeId = match.modeId;
            mLastAppliedDisplayHz = profile.displayHz;
            mAppliedScenePackage = profile.packageName;
            mAppliedDisplayHz = profile.displayHz;
            mRefreshApplyCount++;
            mLastApplyError = "";
            mLastError = "";
            mLastApplyReason = reason + ":display=" + match.displayId
                    + ":mode=" + match.modeId + ":peakBridge=" + peakHz + ":applied";
            return "applied";
        } catch (Throwable t) {
            Log.w(TAG, "apply display failed", t);
            return failApplyAfterMutation(reason, "appRequest:" + throwableText(t), true);
        }
    }

    private String failApplyBeforeMutation(String reason, String error) {
        mLastApplyError = error;
        mLastError = error;
        mLastApplyReason = reason + ":failedBeforeMutation";
        return "failed";
    }

    private String failApplyAfterMutation(String reason, String error,
            boolean appRequestMayHaveChanged) {
        StringBuilder cleanupError = new StringBuilder();
        if (!clearGlobalRenderVote()) {
            cleanupError.append(";renderVoteReleaseFailed");
        }
        if (!releasePeakBridge()) {
            cleanupError.append(";peakRestoreFailed");
        }
        if ((appRequestMayHaveChanged || mAppRequestOwned)
                && !requestWindowManagerHandoff("applyFailure")) {
            cleanupError.append(";appRequestHandoffFailed");
        }
        clearAppliedState();
        mLastApplyError = error + cleanupError;
        mLastError = mLastApplyError;
        mLastApplyReason = reason + ":failedAfterMutation";
        return "failed";
    }

    private int syncPeakRefreshRate(int targetHz) {
        int peakHz = targetHz > 120 ? targetHz : 120;
        String desired = peakHz + ".0";
        String current = readPeakSetting();
        if (desired.equals(current)) {
            if (mPeakBridgeOwned && !desired.equals(mPeakLastWritten)) {
                clearPeakOwnership("externalAlreadyDesired");
            }
            mLastSyncedPeakHz = peakHz;
            return peakHz;
        }
        boolean acquireOwnership = !mPeakBridgeOwned;
        String restoreValue = current;
        String previousLastWritten = mPeakLastWritten;
        if (!acquireOwnership && !safe(mPeakLastWritten).equals(current)) {
            mPeakRestoreValue = current;
        }
        if (acquireOwnership) {
            mPeakBridgeOwned = true;
            mPeakRestoreValue = restoreValue;
        }
        mPeakLastWritten = desired;
        mPeakReleaseStatus = "applyPending";
        try {
            if (!writePeakSetting(desired)) {
                throw new IllegalStateException("peak_write_rejected");
            }
        } catch (RuntimeException e) {
            try {
                if (!desired.equals(readPeakSetting())) {
                    if (acquireOwnership) {
                        clearPeakOwnership("writeNotObserved");
                    } else {
                        mPeakLastWritten = previousLastWritten;
                        mPeakReleaseStatus = "writeNotObserved";
                    }
                }
            } catch (Throwable ignored) {
            }
            throw e;
        }
        mPeakReleaseStatus = "active";
        mLastSyncedPeakHz = peakHz;
        return peakHz;
    }

    private String readPeakSetting() {
        long token = Binder.clearCallingIdentity();
        try {
            return Settings.System.getString(mContext.getContentResolver(),
                    SETTING_PEAK_REFRESH_RATE);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private boolean writePeakSetting(String value) {
        long token = Binder.clearCallingIdentity();
        try {
            return Settings.System.putString(mContext.getContentResolver(),
                    SETTING_PEAK_REFRESH_RATE, value);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private boolean releasePeakBridge() {
        if (!mPeakBridgeOwned) {
            mLastSyncedPeakHz = -1;
            mPeakReleaseStatus = "notOwned";
            return true;
        }
        try {
            String current = readPeakSetting();
            if (safe(mPeakLastWritten).equals(current)) {
                if (!writePeakSetting(mPeakRestoreValue)) {
                    mPeakReleaseStatus = "restoreRejected";
                    return false;
                }
                mPeakReleaseStatus = "restored";
            } else {
                mPeakReleaseStatus = "externalPreserved";
            }
            clearPeakOwnership(mPeakReleaseStatus);
            return true;
        } catch (Throwable t) {
            mPeakReleaseStatus = "restoreFailed:" + throwableText(t);
            Log.w(TAG, "peak bridge release failed", t);
            return false;
        }
    }

    private void clearPeakOwnership(String releaseStatus) {
        mPeakBridgeOwned = false;
        mPeakRestoreValue = null;
        mPeakLastWritten = "";
        mLastSyncedPeakHz = -1;
        mPeakReleaseStatus = releaseStatus;
    }

    private void registerPeakObserver() {
        try {
            mContext.getContentResolver().registerContentObserver(
                    Settings.System.getUriFor(SETTING_PEAK_REFRESH_RATE),
                    false,
                    new ContentObserver(mWorker) {
                        @Override
                        public void onChange(boolean selfChange) {
                            onPeakRefreshRateChanged();
                        }
                    });
        } catch (Throwable t) {
            Log.w(TAG, "peak observer unavailable", t);
        }
    }

    private void registerRefreshPropertyObserver() {
        try {
            SystemProperties.addChangeCallback(new Runnable() {
                @Override
                public void run() {
                    enqueueRefreshDisableMask(readRefreshDisableMask());
                }
            });
            enqueueRefreshDisableMask(readRefreshDisableMask());
        } catch (Throwable t) {
            mLastApplyError = "propertyObserver:" + throwableText(t);
            Log.w(TAG, "refresh property observer unavailable", t);
        }
    }

    private void enqueueRefreshDisableMask(final int disableMask) {
        synchronized (mRefreshPropertyLock) {
            if (disableMask == mObservedRefreshDisableMask) {
                return;
            }
            mObservedRefreshDisableMask = disableMask;
            mWorker.post(new Runnable() {
                @Override
                public void run() {
                    onRefreshPropertiesChanged(disableMask);
                }
            });
        }
    }

    private synchronized void onRefreshPropertiesChanged(int observedMask) {
        int disableMask = readRefreshDisableMask();
        synchronized (mRefreshPropertyLock) {
            mObservedRefreshDisableMask = disableMask;
        }
        if (disableMask == mRefreshDisableMask) {
            return;
        }
        boolean wasDisabled = mRefreshDisabled;
        mRefreshDisableMask = disableMask;
        boolean disabled = disableMask != 0;
        mRefreshDisabled = disabled;
        if (!wasDisabled && disabled) {
            if (!releaseRefreshOwnership("propertyDisable")) {
                scheduleDisableReleaseRetry();
            }
        } else if (wasDisabled && !disabled) {
            if (latestFocusMatchesRaw()) {
                reconcileFocusedProfile("propertyEnable", true);
            } else {
                mLastApplyReason = "propertyEnable:focusPending";
                mWorker.post(new Runnable() {
                    @Override
                    public void run() {
                        reconcileAfterPropertyEnable();
                    }
                });
            }
        } else {
            if (disabled && hasResidualRefreshOwnership()) {
                if (!releaseRefreshOwnership("propertyDisableMaskChanged:" + disableMask)) {
                    scheduleDisableReleaseRetry();
                }
            } else {
                mLastApplyReason = "propertyDisableMaskChanged:" + disableMask;
            }
        }
        publishState();
    }

    private synchronized void reconcileAfterPropertyEnable() {
        if (mRefreshDisabled) {
            return;
        }
        if (latestFocusMatchesRaw()) {
            reconcileFocusedProfile("propertyEnableAfterFocus", true);
            publishState();
        }
    }

    private boolean latestFocusMatchesRaw() {
        FocusSnapshot latestFocus = mLatestFocus;
        return !mLatestWindowFocusEmpty
                && latestFocus.packageName.equals(mRawFocusedPackage)
                && latestFocus.userId == mRawFocusedUserId
                && resolveDisplayId(latestFocus.displayId) == mRawFocusedDisplayId
                && latestFocus.transientFocus == mRawFocusTransient;
    }

    private boolean hasResidualRefreshOwnership() {
        return mRenderVoteOwned || mPeakBridgeOwned || mAppRequestOwned;
    }

    private synchronized void scheduleDisableReleaseRetry() {
        if (mDisableReleaseRetryPosted) {
            return;
        }
        mDisableReleaseRetryPosted = true;
        mWorker.post(new Runnable() {
            @Override
            public void run() {
                retryDisabledRelease();
            }
        });
    }

    private synchronized void retryDisabledRelease() {
        mDisableReleaseRetryPosted = false;
        if (mRefreshDisabled && hasResidualRefreshOwnership()) {
            releaseRefreshOwnership("propertyDisableRetry");
            publishState();
        }
    }

    private static int readRefreshDisableMask() {
        int mask = SystemProperties.getBoolean(PROP_GLOBAL_DISABLE, false) ? 1 : 0;
        return SystemProperties.getBoolean(PROP_REFRESH_DISABLE, false) ? mask | 2 : mask;
    }

    private void registerScreenObserver() {
        try {
            PowerManager power = (PowerManager) mContext.getSystemService(PowerManager.class);
            mScreenInteractive = power == null || power.isInteractive();
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            mContext.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    String action = intent == null ? "" : intent.getAction();
                    if (Intent.ACTION_SCREEN_ON.equals(action)) {
                        onScreenInteractiveChanged(true);
                    } else if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                        onScreenInteractiveChanged(false);
                    }
                }
            }, filter, null, mWorker);
        } catch (Throwable t) {
            Log.w(TAG, "screen observer unavailable", t);
        }
    }

    private synchronized void onScreenInteractiveChanged(boolean interactive) {
        if (mScreenInteractive == interactive) {
            return;
        }
        mScreenInteractive = interactive;
        mUperfScenePolicy.onInteractiveChanged(interactive,
                interactive ? "screenOn" : "screenOff", SystemClock.elapsedRealtimeNanos());
        publishState();
    }

    private synchronized void onPeakRefreshRateChanged() {
        if (mRefreshDisabled || !mAppRequestOwned || mAppliedDisplayHz <= 0) {
            return;
        }
        if (!isPeakRefreshRateSynced(mAppliedDisplayHz)) {
            try {
                int peakHz = syncPeakRefreshRate(mAppliedDisplayHz);
                mLastApplyError = "";
                mLastError = "";
                mLastApplyReason = "peakObserver:target=" + mAppliedDisplayHz
                        + ":peakBridge=" + peakHz;
                publishState();
            } catch (Throwable t) {
                mLastApplyError = "peakObserver:" + throwableText(t);
                mLastError = mLastApplyError;
                mLastApplyReason = "peakObserver:failed";
                publishState();
            }
        }
    }

    private boolean isPeakRefreshRateSynced(int targetHz) {
        int peakHz = targetHz > 120 ? targetHz : 120;
        String desired = peakHz + ".0";
        return desired.equals(readPeakSetting());
    }

    private boolean releaseRefreshOwnership(String reason) {
        boolean renderVoteReleased = clearGlobalRenderVote();
        boolean peakReleased = releasePeakBridge();
        boolean hadAppRequest = mAppRequestOwned;
        boolean appRequestReleased = !hadAppRequest
                || requestWindowManagerHandoff(reason);
        if (!mAppRequestOwned && appRequestReleased
                && !mAppRequestHandoff.startsWith("requested:")) {
            mAppRequestHandoff = "notOwned";
        }
        clearAppliedState();
        if (appRequestReleased && renderVoteReleased && peakReleased) {
            String priorApplyError = mLastApplyError;
            mLastApplyError = "";
            if (mLastError.equals(priorApplyError)) {
                mLastError = "";
            }
            mLastApplyReason = reason + (hadAppRequest
                    ? ":releaseRequested" : ":released");
            return true;
        }
        mLastApplyError = (!appRequestReleased ? "appRequest_handoff_failed" : "")
                + (!renderVoteReleased ? (appRequestReleased ? "" : ";")
                + "render_vote_release_failed" : "")
                + (!peakReleased ? (appRequestReleased && renderVoteReleased ? "" : ";")
                + "peak_restore_failed" : "");
        mLastError = mLastApplyError;
        mLastApplyReason = reason + ":releasePartial";
        return false;
    }

    private boolean requestWindowManagerHandoff(String reason) {
        try {
            WindowManagerInternal wmi = LocalServices.getService(WindowManagerInternal.class);
            if (wmi == null) {
                mAppRequestHandoff = "failed:WindowManagerInternal_unavailable";
                return false;
            }
            wmi.requestTraversalFromDisplayManager();
            mAppRequestOwned = false;
            mAppRequestHandoff = "requested:" + reason;
            mAppRequestHandoffPending = true;
            return true;
        } catch (Throwable t) {
            mAppRequestHandoff = "failed:" + throwableText(t);
            Log.w(TAG, "WindowManager AppRequest handoff failed", t);
            return false;
        }
    }

    private void clearAppliedState() {
        mAppliedScenePackage = "";
        mAppliedDisplayHz = 0;
        mLastAppliedDisplayId = -1;
        mLastAppliedModeId = -1;
        mLastAppliedDisplayHz = -1;
    }

    private static String throwableText(Throwable t) {
        return t.getClass().getSimpleName() + ":" + safe(t.getMessage());
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
        return neutralProfile(userId);
    }

    private Profile neutralProfile(int userId) {
        return new Profile(DEFAULT_SCENE, userId, 120, 0, "DISPLAY_ONLY");
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
                    p = neutralProfile(parseInt(parts[1], 0));
                } else if (parts.length == 6 && "pkg".equals(parts[0])) {
                    if (!isTransientPackage(parts[2])) {
                        p = makeProfile(parts[2], parseInt(parts[1], 0),
                                parseInt(parts[3], 120), parseInt(parts[4], 0), parts[5]);
                    }
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

    private boolean saveProfiles() {
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
            return true;
        } catch (Throwable t) {
            mLastError = "profile_save:" + t.getClass().getSimpleName();
            if (out != null) {
                try {
                    mProfileFile.failWrite(out);
                } catch (Throwable rollbackError) {
                    Log.w(TAG, "profile rollback failed", rollbackError);
                }
            }
            return false;
        }
    }

    private void applyGlobalRenderVote(DisplayManagerInternal dmi, int hz) throws Exception {
        mRenderVoteOwned = true;
        mRenderVoteHz = hz;
        mRenderVoteReleaseStatus = "applyPending";
        updateGlobalRenderVote(dmi, Integer.valueOf(hz));
        mRenderVoteReleaseStatus = "active";
    }

    private boolean clearGlobalRenderVote() {
        if (!mRenderVoteOwned) {
            mRenderVoteReleaseStatus = "notOwned";
            mRenderVoteHz = 0;
            return true;
        }
        try {
            DisplayManagerInternal dmi = LocalServices.getService(DisplayManagerInternal.class);
            if (dmi == null) {
                mRenderVoteReleaseStatus = "failed:DisplayManagerInternal_unavailable";
                return false;
            }
            updateGlobalRenderVote(dmi, null);
            mRenderVoteOwned = false;
            mRenderVoteHz = 0;
            mRenderVoteReleaseStatus = "released";
            return true;
        } catch (Throwable t) {
            mRenderVoteReleaseStatus = "failed:" + throwableText(t);
            Log.w(TAG, "global render vote release failed", t);
            return false;
        }
    }

    private void updateGlobalRenderVote(DisplayManagerInternal dmi, Integer hz)
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
        Method updateVote = votesStorage.getClass().getDeclaredMethod(
                "updateGlobalVote", int.class, voteClass);
        updateVote.setAccessible(true);
        updateVote.invoke(votesStorage, PRIORITY_ZUI_CONTROL_RENDER, vote);
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
        return state(true);
    }

    private synchronized String state(boolean observeSchedulerHealth) {
        int physicalDisplayHz = actualHz();
        return "ok=1"
                + "\nrawFocusedPackage=" + mRawFocusedPackage
                + "\nrawFocusTransient=" + mRawFocusTransient
                + "\nactivityFocusedPackage=" + mActivityFocusedPackage
                + "\nnonImeFocusedPackage=" + mNonImeFocusedPackage
                + "\nwindowFocusSeen=" + mWindowFocusSeen
                + "\nlatestWindowFocusEmpty=" + mLatestWindowFocusEmpty
                + "\nemptyFocusPolicy=retainLastNonEmptyWindow"
                + "\nemptyFocusTransitionPending=" + mEmptyFocusTransitionPending
                + "\nemptyFocusTransitionCount=" + mEmptyFocusTransitionCount
                + "\nlastEmptyFocusActivityPackage=" + mLastEmptyFocusActivityPackage
                + "\nlastEmptyFocusRetainedPackage=" + mLastEmptyFocusRetainedPackage
                + "\nimeVisible=" + mImeVisible
                + "\ncurrentScenePackage=" + mCurrentScenePackage
                + "\nlastNonTransientScenePackage=" + mLastNonTransientScenePackage
                + "\neditableScenePackage=" + editableScenePackage()
                + "\neditableDisplayHz=" + editableDisplayHz()
                + "\ndesiredScenePackage=" + mDesiredScenePackage
                + "\nattemptedScenePackage=" + mAttemptedScenePackage
                + "\nappliedScenePackage=" + mAppliedScenePackage
                + "\ntargetDisplayHz=" + mTargetDisplayHz
                + "\nattemptedDisplayHz=" + mAttemptedDisplayHz
                + "\nappliedDisplayHz=" + mAppliedDisplayHz
                + "\nphysicalDisplayHz=" + physicalDisplayHz
                + "\nactualDisplayHz=" + physicalDisplayHz
                + "\ntargetFpsCap=" + mTargetFpsCap
                + "\nmode=" + mTargetMode
                + "\nscreenInteractive=" + mScreenInteractive
                + mUperfScenePolicy.stateLines()
                + (observeSchedulerHealth ? schedulerHealthStateLines() : "")
                + "\nrefreshOwner=system"
                + "\nsystemServiceAlive=true"
                + "\ndaemonRefreshDisabled=true"
                + "\ndaemonRetired=true"
                + "\nrefreshDisabled=" + mRefreshDisabled
                + "\nrefreshDisableMask=" + mRefreshDisableMask
                + "\nsupportedDisplayHz=" + supportedDisplayHz()
                + "\npeakBridgeHz=" + mLastSyncedPeakHz
                + "\npeakBridgeOwned=" + mPeakBridgeOwned
                + "\npeakReleaseStatus=" + mPeakReleaseStatus
                + "\ndisplayVote=adaptiveRender"
                + "\nrenderVoteScope=globalPriority8"
                + "\nrenderVoteOwned=" + mRenderVoteOwned
                + "\nrenderVoteHz=" + mRenderVoteHz
                + "\nrenderVoteReleaseStatus=" + mRenderVoteReleaseStatus
                + "\nappRequestOwned=" + mAppRequestOwned
                + "\nappRequestOwnership=sharedNoToken"
                + "\nappRequestHandoff=" + mAppRequestHandoff
                + "\nappRequestHandoffPending=" + mAppRequestHandoffPending
                + "\nrefreshDisplayScope=defaultDisplayOnly"
                + "\nrefreshApplyCount=" + mRefreshApplyCount
                + "\nskipSameCount=" + mSkipSameCount
                + "\nprofileCount=" + mProfiles.size()
                + profileStateLines()
                + "\nlastApplyReason=" + mLastApplyReason
                + "\nlastApply=" + mLastApplyReason
                + "\nlastApplyError=" + mLastApplyError
                + "\nlastError=" + mLastError;
    }

    private String schedulerHealthStateLines() {
        String active = SystemProperties.get(PROP_SCHEDULER_ACTIVE, "unknown");
        String uperfState = SystemProperties.get(PROP_UPERF_SERVICE, "unknown");
        String uperfMode = SystemProperties.get(PROP_UPERF_MODE, "unknown");
        String uperfFailSafe = SystemProperties.get(PROP_UPERF_FAIL_SAFE, "0");
        String asoulState = SystemProperties.get(PROP_ASOUL_SERVICE, "unknown");
        String currentError = "";
        if ("1".equals(SystemProperties.get("sys.boot_completed", "0"))) {
            if (!"0".equals(active) && !"1".equals(active)) {
                currentError = "invalid_scheduler_active";
            } else if ("1".equals(active) && "1".equals(uperfFailSafe)) {
                currentError = "uperf_fail_safe";
            } else if ("1".equals(active)) {
                if ("stopped".equals(uperfState)) {
                    currentError = "uperf_stopped_while_active";
                } else if (!isUperfMode(uperfMode)) {
                    currentError = "invalid_uperf_mode";
                }
            } else if ("running".equals(uperfState) || "running".equals(asoulState)) {
                currentError = "zui_scheduler_running_while_inactive";
            }
        }
        if (!currentError.isEmpty()) {
            mLastSchedulerError = currentError;
        }
        return "\nschedulerActive=" + active
                + "\nuperfServiceState=" + uperfState
                + "\nuperfMode=" + uperfMode
                + "\nuperfFailSafe=" + uperfFailSafe
                + "\nasoulServiceState=" + asoulState
                + "\nschedulerHealth=" + (currentError.isEmpty() ? "ok" : currentError)
                + "\nlastSchedulerError="
                + (mLastSchedulerError.isEmpty() ? "none" : mLastSchedulerError);
    }

    private static boolean isUperfMode(String value) {
        return "powersave".equals(value) || "balance".equals(value)
                || "performance".equals(value) || "fast".equals(value);
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
                + "\nfpsCapPhase=not_delivered";
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
                    "zui_control_screen_on", mScreenInteractive ? "1" : "0");
            Settings.System.putString(mContext.getContentResolver(),
                    "zui_control_status_text", state(false));
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
        enforceZuiControlCaller(uid);
    }

    private void enforceCommandCallerAllowed() {
        enforceZuiControlCaller(Binder.getCallingUid());
    }

    private void enforceZuiControlCaller(int uid) {
        String[] packages = mPm.getPackagesForUid(uid);
        if (packages == null || !Arrays.asList(packages).contains(APP_PACKAGE)) {
            throw new SecurityException("caller package is not ZuiControl");
        }
        boolean releaseCert = mPm.hasSigningCertificate(uid, hex(RELEASE_CERT),
                PackageManager.CERT_INPUT_SHA256);
        boolean debugCert = false;
        try {
            ApplicationInfo app = mPm.getApplicationInfo(APP_PACKAGE, 0);
            debugCert = (app.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0
                    && mPm.hasSigningCertificate(uid, hex(DEBUG_CERT),
                    PackageManager.CERT_INPUT_SHA256);
        } catch (Throwable ignored) {
        }
        boolean certOk = releaseCert || debugCert;
        if (!certOk) {
            throw new SecurityException("ZuiControl cert mismatch");
        }
    }

    private String editableScenePackage() {
        return !mLastNonTransientScenePackage.isEmpty()
                ? mLastNonTransientScenePackage : mCurrentScenePackage;
    }

    private int editableDisplayHz() {
        String pkg = editableScenePackage();
        return pkg.isEmpty() ? 0 : profileFor(pkg, mCurrentUserId).displayHz;
    }

    private boolean isForegroundBusinessPackage(String pkg, int userId) {
        String cleanPackage = safe(pkg);
        FocusSnapshot latestFocus = mLatestFocus;
        return !cleanPackage.isEmpty()
                && !mLatestWindowFocusEmpty
                && !mImeVisible
                && !mLatestImeVisible
                && !mRawFocusTransient
                && !latestFocus.transientFocus
                && !isTransientPackage(cleanPackage)
                && cleanPackage.equals(mRawFocusedPackage)
                && cleanPackage.equals(latestFocus.packageName)
                && userId == mRawFocusedUserId
                && userId == latestFocus.userId
                && resolveDisplayId(latestFocus.displayId) == mRawFocusedDisplayId;
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
        return findMode(hz, resolveDisplayId(mRawFocusedDisplayId)) != null;
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
        return findModeOnDisplay(preferred, hz);
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
        Display display = mDisplayManager == null
                ? null : mDisplayManager.getDisplay(mRawFocusedDisplayId);
        if (display == null || display.getMode() == null) {
            return 0;
        }
        return Math.round(display.getMode().getRefreshRate());
    }

    private static boolean isTransientPackage(String pkg) {
        String p = safe(pkg).toLowerCase(Locale.US);
        return p.equals("com.android.systemui")
                || p.equals(GAME_HELPER_PACKAGE)
                || p.equals(APP_PACKAGE)
                || p.equals(SCREEN_SPLIT_CONTROL_PACKAGE)
                || p.equals(FREEFORM_SIDEBAR_PACKAGE)
                || p.equals(IME_SCENE)
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

    private static boolean validRequestId(String requestId) {
        if (requestId == null || requestId.isEmpty() || requestId.length() > 64) {
            return false;
        }
        for (int i = 0; i < requestId.length(); i++) {
            char c = requestId.charAt(i);
            if (!(c == '.' || c == '_' || c == '-' || (c >= '0' && c <= '9')
                    || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z'))) {
                return false;
            }
        }
        return true;
    }

    private static boolean validSha256(String value) {
        if (value == null || value.length() != 64) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
                return false;
            }
        }
        return true;
    }

    private static String sha256(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            char[] alphabet = "0123456789abcdef".toCharArray();
            char[] result = new char[bytes.length * 2];
            for (int i = 0; i < bytes.length; i++) {
                int b = bytes[i] & 0xff;
                result[i * 2] = alphabet[b >>> 4];
                result[i * 2 + 1] = alphabet[b & 0x0f];
            }
            return new String(result);
        } catch (Throwable t) {
            throw new IllegalStateException("SHA-256 unavailable", t);
        }
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

    private final class UperfScenePolicy {
        private final Map<String, String> mRules = new HashMap<>();
        private final Runnable mReloadRunnable = new Runnable() {
            @Override
            public void run() {
                reloadSettings("settings");
            }
        };
        private final ContentObserver mSettingsObserver = new ContentObserver(mWorker) {
            @Override
            public void onChange(boolean selfChange) {
                mWorker.removeCallbacks(mReloadRunnable);
                mWorker.post(mReloadRunnable);
            }
        };

        private String mScenePackage = "";
        private int mSceneUserId;
        private boolean mInteractive = true;
        private boolean mTopResumedSeen;
        private boolean mStartScheduled;
        private boolean mStarted;
        private String mGlobalMode = "balance";
        private String mSceneMode = "balance";
        private String mDesiredMode = "balance";
        private String mLastRequestedMode = "";
        private String mLastAppliedMode = "none";
        private int mApplyCount;
        private String mLastReason = "initPending";

        synchronized void start(boolean interactive) {
            mInteractive = interactive;
            if (mStartScheduled) {
                return;
            }
            mStartScheduled = true;
            mWorker.post(new Runnable() {
                @Override
                public void run() {
                    startOnWorker();
                }
            });
        }

        private synchronized void startOnWorker() {
            if (mStarted) {
                return;
            }
            mStarted = true;
            long token = Binder.clearCallingIdentity();
            try {
                mContext.getContentResolver().registerContentObserver(
                        Settings.System.getUriFor(SETTING_UPERF_MODE), false, mSettingsObserver);
                mContext.getContentResolver().registerContentObserver(
                        Settings.System.getUriFor(SETTING_UPERF_RULES), false, mSettingsObserver);
            } catch (Throwable t) {
                Log.w(TAG, "Uperf settings observer unavailable", t);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
            reloadSettings("startup");
        }

        synchronized void onTopResumedChanged(
                String scenePackage, int userId, String reason, long eventNanos) {
            mTopResumedSeen = true;
            mScenePackage = safe(scenePackage);
            mSceneUserId = userId;
            if (mStarted) {
                reconcile(reason, eventNanos);
            }
        }

        synchronized void onInteractiveChanged(
                boolean interactive, String reason, long eventNanos) {
            mInteractive = interactive;
            if (mStarted) {
                reconcile(reason, eventNanos);
            }
        }

        private synchronized void reloadSettings(String reason) {
            String global;
            String rulesText;
            long token = Binder.clearCallingIdentity();
            try {
                global = Settings.System.getString(
                        mContext.getContentResolver(), SETTING_UPERF_MODE);
                rulesText = Settings.System.getString(
                        mContext.getContentResolver(), SETTING_UPERF_RULES);
            } catch (Throwable t) {
                Log.w(TAG, "Uperf settings read failed", t);
                reconcile(reason + ":settingsReadFailed", SystemClock.elapsedRealtimeNanos());
                return;
            } finally {
                Binder.restoreCallingIdentity(token);
            }

            String checkedGlobal = validUperfMode(global) ? global.trim() : "balance";
            Map<String, String> parsedRules = parseUperfRules(rulesText);
            mGlobalMode = checkedGlobal;
            mRules.clear();
            mRules.putAll(parsedRules);
            reconcile(reason, SystemClock.elapsedRealtimeNanos());
        }

        private Map<String, String> parseUperfRules(String rulesText) {
            Map<String, String> parsed = new HashMap<>();
            for (String line : safe(rulesText).split("\\r?\\n")) {
                String text = line.trim();
                int separator = text.indexOf('|');
                if (separator <= 0 || separator != text.lastIndexOf('|')) {
                    continue;
                }
                String packageName = text.substring(0, separator).trim();
                String mode = text.substring(separator + 1).trim();
                if (validPackage(packageName) && validUperfMode(mode)
                        && !parsed.containsKey(packageName)) {
                    parsed.put(packageName, mode);
                }
            }
            return parsed;
        }

        private void reconcile(String reason, long eventNanos) {
            String exact = mRules.get(mScenePackage);
            boolean hasExact = validUperfMode(exact);
            mSceneMode = hasExact ? exact : mGlobalMode;
            String source;
            if (!mInteractive) {
                mDesiredMode = "powersave";
                source = "screenOff";
            } else if (hasExact) {
                mDesiredMode = exact;
                source = "exact:" + mScenePackage;
            } else {
                mDesiredMode = mGlobalMode;
                source = "global";
            }
            if (mDesiredMode.equals(mLastRequestedMode)) {
                mLastReason = reason + ":" + source + ":sameTarget";
                return;
            }
            try {
                long propertySetNanos = SystemClock.elapsedRealtimeNanos();
                SystemProperties.set(PROP_UPERF_MODE, mDesiredMode);
                long propertyAckNanos = SystemClock.elapsedRealtimeNanos();
                mLastRequestedMode = mDesiredMode;
                mLastAppliedMode = mDesiredMode;
                mApplyCount++;
                mLastReason = reason + ":" + source;
                Log.i(TAG, "uperf_transition eventNs=" + eventNanos
                        + " propertySetNs=" + propertySetNanos
                        + " propertyAckNs=" + propertyAckNanos
                        + " desired=" + mDesiredMode
                        + " scene=" + mScenePackage
                        + " reason=" + mLastReason
                        + " applyCount=" + mApplyCount);
            } catch (Throwable t) {
                mLastReason = reason + ":propertySetFailed";
                Log.w(TAG, "Uperf mode property set failed", t);
            }
        }

        synchronized String stateLines() {
            return "\nuperfGlobalMode=" + mGlobalMode
                    + "\nuperfSceneAuthority=topResumedActivity"
                    + "\nuperfTopResumedRawPackage=" + mTopResumedState.rawPackage()
                    + "\nuperfTopResumedStablePackage=" + mTopResumedState.stablePackage()
                    + "\nuperfTopResumedPendingNull=" + mTopResumedState.pendingNull()
                    + "\nuperfTopResumedGeneration=" + mTopResumedState.generation()
                    + "\nuperfTopResumedRevalidateCount="
                    + mTopResumedState.revalidateCount()
                    + "\nuperfTopResumedLastRevalidateResult="
                    + mTopResumedState.lastRevalidateResult()
                    + "\nuperfScenePackage=" + mScenePackage
                    + "\nuperfSceneUserId=" + mSceneUserId
                    + "\nuperfTopResumedSeen=" + mTopResumedSeen
                    + "\nuperfSceneMode=" + mSceneMode
                    + "\nuperfDesiredMode=" + mDesiredMode
                    + "\nuperfLastAppliedMode=" + mLastAppliedMode
                    + "\nuperfApplyCount=" + mApplyCount
                    + "\nuperfLastReason=" + mLastReason;
        }

        private boolean validUperfMode(String mode) {
            String value = safe(mode).trim();
            return "powersave".equals(value)
                    || "balance".equals(value)
                    || "performance".equals(value)
                    || "fast".equals(value);
        }
    }

    private static final class FocusSnapshot {
        final String packageName;
        final int uid;
        final int userId;
        final int displayId;
        final boolean transientFocus;

        FocusSnapshot(String packageName, int uid, int userId, int displayId,
                boolean transientFocus) {
            this.packageName = packageName;
            this.uid = uid;
            this.userId = userId;
            this.displayId = displayId;
            this.transientFocus = transientFocus;
        }
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
