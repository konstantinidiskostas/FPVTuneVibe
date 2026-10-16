package com.example.fpv.analysis;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class Stats {
    private Stats() {
    }

    public static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    public static double mean(double[] values) {
        if (values.length == 0) return 0;
        double sum = 0;
        for (double v : values) sum += v;
        return sum / values.length;
    }

    public static double rms(double[] values) {
        if (values.length == 0) return 0;
        double sum = 0;
        for (double v : values) sum += v * v;
        return Math.sqrt(sum / values.length);
    }

    public static double percentile(double[] values, double p) {
        if (values.length == 0) return 0;
        double[] copy = values.clone();
        Arrays.sort(copy);
        if (p >= 100) return copy[copy.length - 1];
        int idx = (int) Math.ceil(p / 100.0 * copy.length) - 1;
        return copy[Math.max(0, Math.min(copy.length - 1, idx))];
    }

    public static double median(double[] values) {
        return percentile(values, 50);
    }

    public static double haversineMeters(double lat1, double lon1, double lat2, double lon2) {
        double r = 6371000.0;
        double phi1 = Math.toRadians(lat1);
        double phi2 = Math.toRadians(lat2);
        double dphi = Math.toRadians(lat2 - lat1);
        double dlambda = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dphi / 2) * Math.sin(dphi / 2)
                + Math.cos(phi1) * Math.cos(phi2) * Math.sin(dlambda / 2) * Math.sin(dlambda / 2);
        return 2 * r * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }

    public static final List<String> FLIGHT_MODES = List.of(
            "ARM", "ANGLE", "HORIZON", "MAG", "HEADFREE", "PASSTHRU", "FAILSAFE",
            "GPSRESCUE", "ANTIGRAVITY", "HEADADJ", "CAMSTAB", "BEEPERON", "LEDLOW",
            "CALIB", "OSD", "TELEMETRY", "SERVO1", "SERVO2", "SERVO3", "BLACKBOX",
            "AIRMODE", "3D", "FPVANGLEMIX", "BLACKBOXERASE", "CAMERA1", "CAMERA2",
            "CAMERA3", "FLIPOVERAFTERCRASH", "PREARM", "BEEPGPSCOUNT", "VTXPITMODE",
            "PARALYZE", "USER1", "USER2", "USER3", "USER4");

    public static List<String> decodeModeFlags(long flags) {
        List<String> modes = new ArrayList<>();
        for (int bit = 0; bit < FLIGHT_MODES.size(); bit++) {
            if ((flags & (1L << bit)) != 0) modes.add(FLIGHT_MODES.get(bit));
        }
        return modes;
    }
}