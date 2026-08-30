package android.zui;

public final class ZuiControlManager {
    private ZuiControlManager() {
    }

    public static ZuiControlManager get() {
        throw new UnsupportedOperationException("framework stub");
    }

    public String getVersion() {
        throw new UnsupportedOperationException("framework stub");
    }

    public String getCapabilities() {
        throw new UnsupportedOperationException("framework stub");
    }

    public String getState() {
        throw new UnsupportedOperationException("framework stub");
    }

    public String getCurrentScene() {
        throw new UnsupportedOperationException("framework stub");
    }

    public String cycleCurrentScene(int displayHz, int fpsCap, String mode) {
        throw new UnsupportedOperationException("framework stub");
    }

    public String setCurrentSceneProfile(int displayHz, int fpsCap, String mode) {
        throw new UnsupportedOperationException("framework stub");
    }

    public String setProfile(String packageName, int userId, int displayHz, int fpsCap, String mode) {
        throw new UnsupportedOperationException("framework stub");
    }

    public String removeProfile(String packageName, int userId) {
        throw new UnsupportedOperationException("framework stub");
    }

    public String refreshNow() {
        throw new UnsupportedOperationException("framework stub");
    }

    public String setModuleEnabled(String module, boolean enabled) {
        throw new UnsupportedOperationException("framework stub");
    }

    public String exportLog() {
        throw new UnsupportedOperationException("framework stub");
    }

    public String notifyControlRequest(String requestId, String requestSha256) {
        throw new UnsupportedOperationException("framework stub");
    }
}
