package com.example.fpv.blackbox;

import java.util.ArrayList;
import java.util.List;

public final class FlightData {
    public final Header header;
    public final String sourceName;
    public final int fileIndex;
    public final int sessionIndex;
    public final DecodeStats stats;

    public final FrameDef mainDef;
    public final int stride;
    final IntVector mainData = new IntVector();
    final IntVector mainKind = new IntVector();
    final LongVector mainTimes = new LongVector();
    public int mainCount = 0;

    final IntVector slowData = new IntVector();
    final IntVector slowKind = new IntVector();
    public int slowCount = 0;

    final IntVector gpsData = new IntVector();
    final IntVector gpsKind = new IntVector();
    public int gpsCount = 0;

    public final List<Event> events = new ArrayList<>();
    public boolean reachedLogEnd = false;
    public int[] gpsHome = new int[]{0, 0};
    public boolean hasHome = false;

    public FlightData(Header header, String sourceName, int fileIndex, int sessionIndex, DecodeStats stats) {
        this.header = header;
        this.sourceName = sourceName;
        this.fileIndex = fileIndex;
        this.sessionIndex = sessionIndex;
        this.stats = stats;
        this.mainDef = header.frameDef('I');
        this.stride = mainDef.size();
    }

    void appendMain(char kind, int[] row) {
        mainData.addAll(row);
        mainKind.add(kind == 'I' ? 0 : 1);
        mainCount++;
    }

    void setMainTime(int frame, long timeUs) {
        mainTimes.add(timeUs);
    }

    void appendSlow(int[] row) {
        slowData.addAll(row);
        slowCount++;
    }

    void appendGps(int[] row) {
        gpsData.addAll(row);
        gpsCount++;
    }

    public int mainValue(int frame, String name) {
        Integer idx = mainDef.index.get(name);
        if (idx == null) return 0;
        return mainData.get(frame * stride + idx);
    }

    public long mainValueUnsigned(int frame, String name) {
        return Integer.toUnsignedLong(mainValue(frame, name));
    }

    public boolean hasMainField(String name) {
        return mainDef.has(name);
    }

    public long timeAt(int frame) {
        return mainTimes.get(frame);
    }

    public double frameRateHz() {
        if (mainCount < 2) return 0;
        long dt = mainTimes.get(mainCount - 1) - mainTimes.get(0);
        if (dt <= 0) return 0;
        return (mainCount - 1) * 1_000_000.0 / dt;
    }

    public void setHome(int[] home) {
        if (home != null) {
            this.gpsHome = home;
            this.hasHome = true;
        }
    }

    public boolean hasSlowField(String name) {
        FrameDef def = header.frameDef('S');
        return def != null && def.has(name);
    }

    public int slowValue(int frame, String name) {
        FrameDef def = header.frameDef('S');
        Integer idx = def != null ? def.index.get(name) : null;
        if (idx == null) return 0;
        return slowData.get(frame * def.size() + idx);
    }

    public boolean hasGpsField(String name) {
        FrameDef def = header.frameDef('G');
        return def != null && def.has(name);
    }

    public long gpsValue(int frame, String name) {
        FrameDef def = header.frameDef('G');
        Integer idx = def != null ? def.index.get(name) : null;
        if (idx == null) return 0;
        return Integer.toUnsignedLong(gpsData.get(frame * def.size() + idx));
    }
}