package com.example.fpv.blackbox;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class HeaderParser {
    public static final byte[] LOG_MARKER = "H Product:Blackbox".getBytes(StandardCharsets.US_ASCII);
    private static final char[] FRAME_KINDS = {'I', 'P', 'S', 'G', 'H'};
    private static final Set<String> TEXT_HEADERS = Set.of(
            "Product", "Firmware type", "Firmware revision", "Firmware date",
            "Board information", "Craft name", "Log start datetime");

    public static Header parse(byte[] data) {
        Map<String, String> raw = new LinkedHashMap<>();
        int pos = 0;
        int size = data.length;

        while (pos + 1 < size && (data[pos] & 0xFF) == 0x48 && (data[pos + 1] & 0xFF) == 0x20) {
            int end = indexOf(data, (byte) '\n', pos);
            if (end == -1) throw new CorruptLogException("header line at offset " + pos + " is never terminated");
            String line = new String(data, pos + 2, end - (pos + 2), StandardCharsets.ISO_8859_1);
            if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
            pos = end + 1;
            int colon = line.indexOf(':');
            if (colon == -1) continue;
            String key = line.substring(0, colon).trim();
            String value = line.substring(colon + 1);
            raw.put(key, TEXT_HEADERS.contains(key) ? value : value.trim());
        }

        if (raw.isEmpty()) throw new CorruptLogException("no header lines found");
        Map<Character, FrameDef> frames = buildFrames(raw);
        if (!frames.containsKey('I')) throw new CorruptLogException("header defines no intra frame, log is undecodable");
        return new Header(raw, frames, pos);
    }

    private static Map<Character, FrameDef> buildFrames(Map<String, String> raw) {
        Map<Character, FrameDef> frames = new LinkedHashMap<>();
        List<String> intraNames = splitCsv(raw.get("Field I name"));
        int[] intraSigned = ints(raw.get("Field I signed"), "Field I signed");

        for (char kind : FRAME_KINDS) {
            String predictorsRaw = raw.get("Field " + kind + " predictor");
            String encodingsRaw = raw.get("Field " + kind + " encoding");
            if (predictorsRaw == null || encodingsRaw == null) continue;

            List<String> names;
            int[] signs;
            if (kind == 'P') {
                names = intraNames;
                signs = intraSigned;
            } else {
                names = splitCsv(raw.get("Field " + kind + " name"));
                signs = ints(raw.get("Field " + kind + " signed"), "Field " + kind + " signed");
            }
            int[] predictors = ints(predictorsRaw, "Field " + kind + " predictor");
            int[] encodings = ints(encodingsRaw, "Field " + kind + " encoding");

            if (names.isEmpty() || predictors.length != names.size() || encodings.length != names.size()) {
                throw new CorruptLogException("frame " + kind + " declares " + names.size()
                        + " names but " + predictors.length + " predictors and " + encodings.length + " encodings");
            }
            if (signs.length < names.size()) {
                int[] extended = new int[names.size()];
                System.arraycopy(signs, 0, extended, 0, signs.length);
                signs = extended;
            }
            List<FieldDef> fields = new ArrayList<>(names.size());
            for (int i = 0; i < names.size(); i++) {
                fields.add(new FieldDef(names.get(i), signs[i] != 0, predictors[i], encodings[i]));
            }
            frames.put(kind, new FrameDef(kind, fields));
        }
        return frames;
    }

    private static List<String> splitCsv(String value) {
        List<String> out = new ArrayList<>();
        if (value != null && !value.isEmpty()) {
            for (String part : value.split(",")) out.add(part.trim());
        }
        return out;
    }

    private static int[] ints(String value, String what) {
        List<String> parts = splitCsv(value);
        int[] out = new int[parts.size()];
        for (int i = 0; i < parts.size(); i++) {
            try {
                out[i] = Header.parseInt(parts.get(i));
            } catch (NumberFormatException e) {
                throw new CorruptLogException("header " + what + " is not a list of integers");
            }
        }
        return out;
    }

    private static int indexOf(byte[] data, byte b, int from) {
        for (int i = from; i < data.length; i++) {
            if (data[i] == b) return i;
        }
        return -1;
    }

    public static List<int[]> splitSessions(byte[] data) {
        List<Integer> starts = new ArrayList<>();
        int position = indexOf(data, LOG_MARKER, 0);
        while (position != -1) {
            starts.add(position);
            position = indexOf(data, LOG_MARKER, position + 1);
        }
        List<int[]> bounds = new ArrayList<>();
        for (int i = 0; i < starts.size(); i++) {
            int end = i + 1 < starts.size() ? starts.get(i + 1) : data.length;
            bounds.add(new int[]{starts.get(i), end});
        }
        return bounds;
    }

    private static int indexOf(byte[] data, byte[] needle, int from) {
        outer:
        for (int i = from; i <= data.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (data[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }
}