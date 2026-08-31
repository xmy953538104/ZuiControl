package com.zui.server.control;

import com.android.server.wm.ActivityRecord;

public final class ZuiControlHooks {
    private ZuiControlHooks() {
    }

    public static void onFocusedAppChanged(ActivityRecord record, int displayId) {
        ZuiControlService service = ZuiControlService.getInstance();
        if (service != null) {
            service.onFocusedAppChanged(record, displayId);
        }
    }

    public static void onFocusedWindowChanged(String packageName, int displayId) {
        ZuiControlService service = ZuiControlService.getInstance();
        if (service != null) {
            service.onFocusedWindowChanged(packageName, displayId);
        }
    }

    public static void onImeVisibilityChanged(
            String packageName, boolean visible, int displayId) {
        ZuiControlService service = ZuiControlService.getInstance();
        if (service != null) {
            service.onImeVisibilityChanged(packageName, visible, displayId);
        }
    }
}
