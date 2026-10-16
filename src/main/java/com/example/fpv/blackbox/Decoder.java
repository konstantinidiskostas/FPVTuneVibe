package com.example.fpv.blackbox;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

public final class Decoder {
    private static final long MAX_TIME_JUMP_US = 10_000_000L;
    private static final int MAX_ITERATION_JUMP = 5000;
    private static final byte[] LOG_END_MARKER = "End of log".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    private static final String FRAME_KINDS = "IPSGHE";

    private static final int EVENT_SYNC_BEEP = 0;
    private static final int EVENT_INFLIGHT_ADJUSTMENT = 13;
    private static final int EVENT_LOGGING_RESUME = 14;
    private static final int EVENT_DISARM = 15;
    private static final int EVENT_FLIGHT_MODE = 30;
    private static final int EVENT_LOG_END = 255;

    private static final Map<Integer, String> EVENT_NAMES = Map.of(
            EVENT_SYNC_BEEP, "syncBeep",
            EVENT_INFLIGHT_ADJUSTMENT, "inflightAdjustment",
            EVENT_LOGGING_RESUME, "loggingResume",
            EVENT_DISARM, "disarm",
            EVENT_FLIGHT_MODE, "flightMode",
            EVENT_LOG_END, "logEnd");

    private static final Map<Integer, String> DISARM_REASONS = Map.of(
            0, "ARMING_DISABLED", 1, "FAILSAFE", 2, "THROTTLE_TIMEOUT", 3, "STICKS",
            4, "SWITCH", 5, "CRASH_PROTECTION", 6, "RUNAWAY_TAKEOFF", 7, "GPS_RESCUE", 8, "SERIAL_IO");

    private final FlightData out;
    private final Reader reader;
    private final Header header;
    private final DecodeStats stats;

    private int prevOffset = -1;
    private int prev2Offset = -1;
    private long lastMainTime = 0;
    private int lastMainIteration = -1;
    private long timeRollover = 0;
    private int skippedFrames = 0;
    private int pNum, pDen;
    private int motor0Offset = -1;
    private int timeField = -1;
    private int iterationField = -1;
    private int[] gpsHome = new int[]{0, 0};
    private final boolean readTag8_4s16_v2;

    public Decoder(FlightData out, byte[] data) {
        this.out = out;
        this.header = out.header;
        this.stats = out.stats;
        this.reader = new Reader(data, header.endOffset);
        Header.Ratio ratio = header.pInterval();
        this.pNum = ratio.num;
        this.pDen = ratio.den;
        if (!(1 <= pNum && pNum <= pDen)) {
            pNum = 1;
            pDen = 1;
        }
        if (header.hasMainField("motor[0]")) motor0Offset = header.frameDef('I').index.get("motor[0]");
        if (header.hasMainField("time")) timeField = header.frameDef('I').index.get("time");
        if (header.hasMainField("loopIteration")) iterationField = header.frameDef('I').index.get("loopIteration");
        this.readTag8_4s16_v2 = header.dataVersion() >= 2;
    }

    public void run() {
        while (!reader.eof()) {
            int start = reader.position();
            try {
                int kindByte = reader.readByte();
                char kind = (char) kindByte;
                if (FRAME_KINDS.indexOf(kind) < 0) {
                    stats.corruptFrames++;
                    stats.unknown(kind);
                    resync(start);
                    continue;
                }
                if (kind == 'E') {
                    Event event = decodeEvent();
                    if (event == null) {
                        stats.count('E');
                        stats.reachedLogEnd = true;
                        out.reachedLogEnd = true;
                        return;
                    }
                    stats.count('E');
                    out.events.add(event);
                    continue;
                }
                int[] row = decodeFrame(kind);
                if (kind == 'I' || kind == 'P') {
                    if (!accept(row)) {
                        stats.corruptFrames++;
                        resync(start);
                        continue;
                    }
                    stats.count(kind);
                    out.appendMain(kind, row);
                    out.setMainTime(out.mainCount - 1, lastMainTime);
                    if (kind == 'I') {
                        prevOffset = prev2Offset = (out.mainCount - 1) * out.stride;
                    } else {
                        prev2Offset = prevOffset;
                        prevOffset = (out.mainCount - 1) * out.stride;
                    }
                } else {
                    stats.count(kind);
                    if (kind == 'S') {
                        out.appendSlow(row);
                    } else {
                        out.appendGps(row);
                    }
                }
            } catch (BlackboxException e) {
                stats.corruptFrames++;
                resync(start);
            }
        }
    }

    private int[] decodeFrame(char kind) {
        FrameDef def = header.frameDef(kind);
        if (def == null) throw new CorruptLogException("frame type " + kind + " is not defined by the header");
        if (kind == 'P') {
            if (prevOffset < 0) throw new CorruptLogException("inter frame before any intra frame");
            skippedFrames = countSkippedFrames();
        }
        int[] row = decodeFields(def, kind == 'P');
        if (kind == 'H') {
            Integer iLat = def.index.get("GPS_home[0]");
            Integer iLon = def.index.get("GPS_home[1]");
            int[] home = new int[2];
            home[0] = iLat != null ? row[iLat] : gpsHome[0];
            home[1] = iLon != null ? row[iLon] : gpsHome[1];
            gpsHome = home;
            out.setHome(home);
        }
        return row;
    }

    private int[] decodeFields(FrameDef def, boolean inter) {
        int count = def.size();
        int[] row = new int[count];
        int i = 0;
        while (i < count) {
            int encoding = def.fields.get(i).encoding;
            int[] group;
            int expected;
            if (encoding == Reader.NULL) {
                group = new int[]{0};
                expected = 1;
            } else if (encoding == Reader.SIGNED_VB) {
                group = new int[]{reader.readSignedVb()};
                expected = 1;
            } else if (encoding == Reader.UNSIGNED_VB) {
                group = new int[]{(int) reader.readUnsignedVb()};
                expected = 1;
            } else if (encoding == Reader.NEG_14BIT) {
                group = new int[]{reader.readNeg14Bit()};
                expected = 1;
            } else if (encoding == Reader.TAG8_8SVB) {
                int span = 1;
                while (span < 8 && i + span < count && def.fields.get(i + span).encoding == Reader.TAG8_8SVB) {
                    span++;
                }
                group = reader.readTag8_8svb(span);
                expected = span;
            } else if (encoding == Reader.TAG2_3S32) {
                group = reader.readTag2_3s32();
                expected = 3;
            } else if (encoding == Reader.TAG8_4S16) {
                group = readTag8_4s16_v2 ? reader.readTag8_4s16() : reader.readTag8_4s16_v1();
                expected = 4;
            } else if (encoding == Reader.TAG2_3SVARIABLE) {
                throw new CorruptLogException("frame " + def.kind + " field " + def.fields.get(i).name
                        + " uses encoding 10 (TAG2_3SVARIABLE), which has no reference implementation");
            } else {
                throw new CorruptLogException("field " + def.fields.get(i).name + " uses unknown encoding " + encoding);
            }
            if (i + expected > count) {
                throw new CorruptLogException("encoding " + encoding + " at field " + i + " overruns the " + count + "-field frame");
            }
            for (int o = 0; o < group.length; o++) {
                row[i + o] = predict(def, i + o, group[o], row, inter);
            }
            i += expected;
        }
        return row;
    }

    private int predict(FrameDef def, int position, int value, int[] current, boolean inter) {
        FieldDef field = def.fields.get(position);
        int predictor = field.predictor;
        if (predictor == 1) {
            if (inter) value += out.mainData.get(prevOffset + position);
        } else if (predictor == 2) {
            if (inter) value += 2 * out.mainData.get(prevOffset + position) - out.mainData.get(prev2Offset + position);
        } else if (predictor == 3) {
            if (inter) {
                long total = (long) out.mainData.get(prevOffset + position) + (long) out.mainData.get(prev2Offset + position);
                value += total >= 0 ? (int) (total / 2) : (int) (-((-total) / 2));
            }
        } else if (predictor == 4) {
            value += header.minThrottle();
        } else if (predictor == 5) {
            if (motor0Offset >= 0) value += current[motor0Offset];
        } else if (predictor == 6) {
            value += skippedFrames + 1;
            if (inter) value += out.mainData.get(prevOffset + position);
        } else if (predictor == 7) {
            int axis = coordAxis(field.name);
            if (axis >= 0 && axis < 2) value += gpsHome[axis];
        } else if (predictor == 8) {
            value += 1500;
        } else if (predictor == 9) {
            value += header.vbatRef();
        } else if (predictor == 10) {
            value += (int) lastMainTime;
        } else if (predictor == 11) {
            int[] range = header.motorOutputRange();
            int low = range[0] != Integer.MIN_VALUE ? range[0] : header.minThrottle();
            value += low;
        }
        return value;
    }

    private static int coordAxis(String name) {
        if (name.endsWith("]")) {
            int open = name.lastIndexOf('[');
            if (open >= 0) {
                try {
                    return Integer.parseInt(name.substring(open + 1, name.length() - 1));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return -1;
    }

    private boolean accept(int[] row) {
        int iteration = iterationField >= 0 ? row[iterationField] : 0;
        long rawTime = timeField >= 0 ? Integer.toUnsignedLong(row[timeField]) : 0L;
        long rollover = timeRollover;
        long time = rawTime;
        if (lastMainIteration >= 0) {
            long previousRaw = lastMainTime & 0xFFFFFFFFL;
            boolean steppedBack = rawTime < previousRaw;
            boolean smallStep = ((rawTime - previousRaw) & 0xFFFFFFFFL) < MAX_TIME_JUMP_US;
            if (steppedBack && smallStep) rollover += 0x1_0000_0000L;
            time = rawTime + rollover;
            if (!(0 <= time - lastMainTime && time - lastMainTime < MAX_TIME_JUMP_US)) return false;
            if (!(0 <= (long) iteration - lastMainIteration && (long) iteration - lastMainIteration < MAX_ITERATION_JUMP)) {
                return false;
            }
        }
        timeRollover = rollover;
        lastMainTime = time;
        lastMainIteration = iteration;
        return true;
    }

    private int countSkippedFrames() {
        if (lastMainIteration < 0) return 0;
        int count = 0;
        long iteration = (long) lastMainIteration + 1;
        while (iteration % pDen >= pNum) {
            count++;
            iteration++;
            if (count > MAX_ITERATION_JUMP) break;
        }
        return count;
    }

    private void resync(int frameStart) {
        byte[] data = reader.data();
        int search = frameStart + 1;
        while (true) {
            int found = indexOf(data, (byte) 'I', search);
            if (found == -1) {
                stats.corruptBytes += data.length - frameStart;
                reader.position(data.length);
                return;
            }
            reader.position(found + 1);
            try {
                int[] row = decodeFrame('I');
                if (!accept(row)) {
                    search = found + 1;
                    continue;
                }
                stats.corruptBytes += found - frameStart;
                stats.resyncs++;
                stats.count('I');
                out.appendMain('I', row);
                out.setMainTime(out.mainCount - 1, lastMainTime);
                prevOffset = prev2Offset = (out.mainCount - 1) * out.stride;
                return;
            } catch (BlackboxException e) {
                search = found + 1;
            }
        }
    }

    private Event decodeEvent() {
        int code = reader.readByte();
        String name = EVENT_NAMES.getOrDefault(code, "unknown(" + code + ")");
        Map<String, Object> data = new LinkedHashMap<>();
        if (code == EVENT_SYNC_BEEP) {
            data.put("time", reader.readUnsignedVb());
            return new Event(code, name, lastMainTime, data);
        }
        if (code == EVENT_LOGGING_RESUME) {
            data.put("iteration", reader.readUnsignedVb());
            data.put("time", reader.readUnsignedVb());
            return new Event(code, name, lastMainTime, data);
        }
        if (code == EVENT_DISARM) {
            int reason = (int) reader.readUnsignedVb();
            data.put("reason", reason);
            data.put("reasonName", DISARM_REASONS.getOrDefault(reason, "UNKNOWN(" + reason + ")"));
            data.put("time", lastMainTime);
            return new Event(code, name, lastMainTime, data);
        }
        if (code == EVENT_FLIGHT_MODE) {
            long flags = reader.readUnsignedVb();
            long lastFlags = reader.readUnsignedVb();
            data.put("flags", flags);
            data.put("lastFlags", lastFlags);
            data.put("time", lastMainTime);
            return new Event(code, name, lastMainTime, data);
        }
        if (code == EVENT_INFLIGHT_ADJUSTMENT) {
            int function = reader.readByte();
            if ((function & 0x80) != 0) {
                int bits = 0;
                for (int i = 0; i < 4; i++) bits |= reader.readByte() << (8 * i);
                data.put("value", Float.intBitsToFloat(bits));
            } else {
                data.put("value", reader.readSignedVb());
            }
            data.put("function", function & 0x7F);
            data.put("time", lastMainTime);
            return new Event(code, name, lastMainTime, data);
        }
        if (code == EVENT_LOG_END) {
            byte[] marker = new byte[LOG_END_MARKER.length + 1];
            int end = Math.min(reader.position() + marker.length, reader.data().length);
            int n = end - reader.position();
            if (n < marker.length) throw new CorruptLogException("end-of-log marker truncated");
            System.arraycopy(reader.data(), reader.position(), marker, 0, marker.length);
            if (!Arrays.equals(marker, 0, marker.length, LOG_END_MARKER, 0, LOG_END_MARKER.length) || marker[marker.length - 1] != 0) {
                throw new CorruptLogException("end-of-log marker at offset is malformed");
            }
            reader.position(reader.position() + marker.length);
            return null;
        }
        throw new CorruptLogException("unknown event type " + code);
    }

    private static int indexOf(byte[] data, byte b, int from) {
        for (int i = from; i < data.length; i++) {
            if (data[i] == b) return i;
        }
        return -1;
    }
}