package android.os;

public final class SystemProperties {
    private SystemProperties() {
    }

    public static boolean getBoolean(String key, boolean def) {
        return def;
    }

    public static String get(String key, String def) {
        return def;
    }

    public static void set(String key, String value) {
    }
}
