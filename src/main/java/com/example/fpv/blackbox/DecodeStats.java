package com.example.fpv.blackbox;

import java.util.HashMap;
import java.util.Map;

public final class DecodeStats {
    public final Map<Character, Integer> frameCounts = new HashMap<>();
    public int corruptFrames = 0;
    public int corruptBytes = 0;
    public int resyncs = 0;
    public final Map<Character, Integer> unknownFrameTypes = new HashMap<>();
    public boolean reachedLogEnd = false;

    public void count(char kind) {
        frameCounts.merge(kind, 1, Integer::sum);
    }

    public void unknown(char kind) {
        unknownFrameTypes.merge(kind, 1, Integer::sum);
    }

    public int totalFrames() {
        int total = 0;
        for (int c : frameCounts.values()) total += c;
        return total;
    }
}