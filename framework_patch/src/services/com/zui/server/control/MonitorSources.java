package com.zui.server.control;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;

/** Qualified scalar sources only; no task enumeration and no GPU access. */
final class MonitorSources {
    long scalarReads, discoveryReads;
    String quietPath = "", quietError = "";
    private boolean discovered, quietUnavailable;

    static String line(String path) throws IOException {
        try (BufferedReader reader = new BufferedReader(new FileReader(path), 128)) {
            char[] text = new char[4096]; int count = reader.read(text);
            if (count <= 0) return "";
            String value = new String(text, 0, count);
            int end = value.indexOf('\n');
            return (end < 0 ? value : value.substring(0, end)).trim();
        }
    }
    static String discoverQuiet(File root) throws IOException {
        File[] zones = root.listFiles();
        if (zones == null) throw new IOException("thermal_directory_unreadable");
        String result = "";
        for (File zone : zones) {
            if (!zone.getName().matches("thermal_zone[0-9]+")) continue;
            if ("quiet-therm".equals(line(new File(zone, "type").getPath()))) {
                if (!result.isEmpty()) throw new IOException("quiet_sensor_ambiguous");
                result = new File(zone, "temp").getPath();
            }
        }
        if (result.isEmpty()) throw new IOException("quiet_sensor_missing");
        return result;
    }
    double quiet() {
        if (!discovered) {
            discovered = true; discoveryReads++;
            try { quietPath = discoverQuiet(new File("/sys/class/thermal")); }
            catch (IOException e) { quietUnavailable = true; quietError = e.getMessage(); }
        }
        if (quietUnavailable) return -1;
        try { scalarReads++; return Double.parseDouble(line(quietPath)) / 1000.0; }
        catch (IOException | NumberFormatException e) {
            quietUnavailable = true; quietError = "quiet_read_unavailable"; return -1;
        }
    }
    static final String FPS_PATH = "/sys/class/drm/sde-crtc-0/measured_fps";
    private final String fpsPath;
    private boolean fpsUnavailable;
    long fpsReads;
    String fpsError = "UNAVAILABLE_NOT_SAMPLED";
    MonitorSources() { this(FPS_PATH); }
    // Host fixtures exercise the same reader; production always uses FPS_PATH.
    MonitorSources(String fpsPath) { this.fpsPath = fpsPath; }
    void resetFps() { fpsUnavailable = false; fpsError = "UNAVAILABLE_NOT_SAMPLED"; }
    double fps() {
        if (fpsUnavailable) return -1;
        try {
            fpsReads++; scalarReads++;
            double value = MonitorSnapshot.displayFps(line(fpsPath));
            fpsError = value < 0 ? "UNAVAILABLE_MALFORMED_SOURCE" : "VALID";
            return value;
        } catch (IOException | SecurityException e) {
            // Do not repeatedly poll a denied/missing source. Retry on explicit rebind/enable.
            fpsUnavailable = true; fpsError = "UNAVAILABLE_SOURCE_READ"; return -1;
        }
    }
    static double batteryWatts(int plugged, int status, int milliVolts, long microAmps) {
        // TB321FU broadcast voltage is mV; CURRENT_NOW is uA, positive on proven discharge.
        // Status + absence of external power determine direction; vendor sign does not.
        // Broad single-cell bounds reject missing/sentinel inputs and mV/uV or A/uA confusion.
        double amps = Math.abs(microAmps / 1000000.0);
        if (plugged != 0 || status != 3 || milliVolts < 2000 || milliVolts > 6000
                || amps < 0.001 || amps > 30.0) return -1;
        return milliVolts / 1000.0 * amps;
    }
}
