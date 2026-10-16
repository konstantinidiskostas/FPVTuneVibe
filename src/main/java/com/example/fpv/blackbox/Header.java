package com.example.fpv.blackbox;

import java.util.LinkedHashMap;
import java.util.Map;

public final class Header {
    public final Map<String, String> raw;
    public final Map<Character, FrameDef> frames;
    public final int endOffset;

    public Header(Map<String, String> raw, Map<Character, FrameDef> frames, int endOffset) {
        this.raw = raw;
        this.frames = frames;
        this.endOffset = endOffset;
    }

    public FrameDef frameDef(char kind) {
        return frames.get(kind);
    }

    public boolean hasFrame(char kind) {
        return frames.containsKey(kind);
    }

    public boolean hasField(String name, char kind) {
        FrameDef def = frames.get(kind);
        return def != null && def.has(name);
    }

    public boolean hasMainField(String name) {
        return hasField(name, 'I');
    }

    public String craftName() {
        return raw.getOrDefault("Craft name", "");
    }

    public String firmware() {
        String revision = raw.getOrDefault("Firmware revision", "");
        return revision.isEmpty() ? raw.getOrDefault("Firmware type", "unknown") : revision;
    }

    public String board() {
        return raw.getOrDefault("Board information", "");
    }

    public String logStart() {
        return raw.getOrDefault("Log start datetime", "");
    }

    public int dataVersion() {
        return intHeader("Data version", 2);
    }

    public int minThrottle() {
        Integer value = intHeader("minthrottle", null);
        if (value != null) return value;
        int[] range = motorOutputRange();
        return range[0] != Integer.MIN_VALUE ? range[0] : 1000;
    }

    public int maxThrottle() {
        Integer value = intHeader("maxthrottle", null);
        if (value != null) return value;
        int[] range = motorOutputRange();
        return range[1] != Integer.MIN_VALUE ? range[1] : 2000;
    }

    public int[] motorOutputRange() {
        int[] values = intList("motorOutput");
        if (values.length == 2) return values;
        return new int[]{Integer.MIN_VALUE, Integer.MIN_VALUE};
    }

    public int vbatRef() {
        return intHeader("vbatref", 0);
    }

    public int vbatScale() {
        Integer v = intHeader("vbat_scale", null);
        if (v == null) v = intHeader("vbatscale", null);
        return v != null ? v : 110;
    }

    public int[] cellVoltageLimits() {
        int[] values = intList("vbatcellvoltage");
        return values.length == 3 ? values : null;
    }

    public int[] currentSensor() {
        int[] values = intList("currentSensor");
        return values.length == 2 ? values : new int[]{0, 0};
    }

    public int acc1G() {
        return intHeader("acc_1G", 2048);
    }

    public float gyroScale() {
        String value = raw.getOrDefault("gyro.scale", raw.getOrDefault("gyro_scale", ""));
        if (value.isEmpty()) return Float.NaN;
        try {
            int bits;
            if (value.toLowerCase().startsWith("0x")) bits = (int) Long.parseLong(value.substring(2), 16);
            else bits = Integer.parseInt(value);
            return Float.intBitsToFloat(bits);
        } catch (NumberFormatException e) {
            return Float.NaN;
        }
    }

    public int looptime() {
        return intHeader("looptime", 0);
    }

    public int frameIntervalI() {
        int v = intHeader("I interval", 32);
        return v != 0 ? v : 32;
    }

    public static final class Ratio {
        public final int num;
        public final int den;
        public Ratio(int num, int den) {
            this.num = num;
            this.den = den;
        }
    }

    public Ratio pInterval() {
        String rawValue = raw.get("P interval");
        if (rawValue != null && rawValue.contains("/")) {
            String[] parts = rawValue.split("/");
            try {
                return new Ratio(Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()));
            } catch (NumberFormatException | ArrayIndexOutOfBoundsException ignored) {
            }
        } else if (rawValue != null) {
            try {
                return new Ratio(1, Integer.parseInt(rawValue.trim()));
            } catch (NumberFormatException ignored) {
            }
        }
        Integer denom = intHeader("P denom", null);
        if (denom != null) return new Ratio(1, denom);
        return new Ratio(1, 1);
    }

    public Integer intHeader(String key, Integer def) {
        String v = raw.get(key);
        if (v == null) return def;
        try {
            return parseInt(v.split(",")[0]);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public int[] intList(String key) {
        String v = raw.get(key);
        if (v == null || v.isEmpty()) return new int[0];
        String[] parts = v.split(",");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                out[i] = parseInt(parts[i]);
            } catch (NumberFormatException e) {
                return new int[0];
            }
        }
        return out;
    }

    public static int parseInt(String text) {
        text = text.trim();
        try {
            return Integer.parseInt(text, 10);
        } catch (NumberFormatException e) {
            return (int) Long.parseLong(text.trim());
        }
    }

    public Map<String, String> dump() {
        return new LinkedHashMap<>(raw);
    }
}