package com.example.fpv.blackbox;

import java.util.Map;

public final class Reader {
    public static final int SIGNED_VB = 0;
    public static final int UNSIGNED_VB = 1;
    public static final int NEG_14BIT = 3;
    public static final int TAG8_8SVB = 6;
    public static final int TAG2_3S32 = 7;
    public static final int TAG8_4S16 = 8;
    public static final int NULL = 9;
    public static final int TAG2_3SVARIABLE = 10;

    public static final Map<Integer, Integer> GROUP_SIZE = Map.of(
            TAG8_8SVB, 8,
            TAG2_3S32, 3,
            TAG2_3SVARIABLE, 3,
            TAG8_4S16, 4);

    private final byte[] data;
    private int pos;

    public Reader(byte[] data, int pos) {
        this.data = data;
        this.pos = pos;
    }

    public int position() {
        return pos;
    }

    public void position(int p) {
        this.pos = p;
    }

    public byte[] data() {
        return data;
    }

    public boolean eof() {
        return pos >= data.length;
    }

    public int readByte() {
        if (pos >= data.length) throw new EndOfStreamException("read past end of log at offset " + pos);
        return data[pos++] & 0xFF;
    }

    public int peekByte() {
        if (pos >= data.length) throw new EndOfStreamException("peek past end of log at offset " + pos);
        return data[pos] & 0xFF;
    }

    public long readUnsignedVb() {
        long result = 0;
        for (int shift = 0; shift <= 28; shift += 7) {
            int b = readByte();
            result |= (long) (b & 0x7F) << shift;
            if (b < 0x80) return result & 0xFFFFFFFFL;
        }
        throw new CorruptLogException("varint longer than 5 bytes at offset " + pos);
    }

    public int readSignedVb() {
        return (int) zigzagDecode(readUnsignedVb());
    }

    public int readNeg14Bit() {
        return -signExtend(readUnsignedVb(), 14);
    }

    public int[] readTag8_8svb(int count) {
        if (count == 1) {
            return new int[]{readSignedVb()};
        }
        int header = readByte();
        int[] out = new int[count];
        for (int i = 0; i < count; i++) {
            out[i] = (header & (1 << i)) != 0 ? readSignedVb() : 0;
        }
        return out;
    }

    public int[] readTag2_3s32() {
        int lead = readByte();
        int selector = lead >> 6;
        if (selector == 0) {
            return new int[]{
                    signExtend((lead >> 4) & 0x03, 2),
                    signExtend((lead >> 2) & 0x03, 2),
                    signExtend(lead & 0x03, 2)};
        }
        if (selector == 1) {
            int first = signExtend(lead & 0x0F, 4);
            int packed = readByte();
            return new int[]{first, signExtend(packed >> 4, 4), signExtend(packed & 0x0F, 4)};
        }
        if (selector == 2) {
            return new int[]{
                    signExtend(lead & 0x3F, 6),
                    signExtend(readByte() & 0x3F, 6),
                    signExtend(readByte() & 0x3F, 6)};
        }
        int[] values = new int[3];
        for (int i = 0; i < 3; i++) {
            int width = lead & 0x03;
            if (width == 0) values[i] = signExtend(readByte(), 8);
            else if (width == 1) values[i] = signExtend(readLe(2), 16);
            else if (width == 2) values[i] = signExtend(readLe(3), 24);
            else values[i] = signExtend(readLe(4), 32);
            lead >>= 2;
        }
        return values;
    }

    public int[] readTag8_4s16_v1() {
        int selector = readByte();
        int[] values = new int[4];
        int i = 0;
        while (i < 4) {
            int width = selector & 0x03;
            if (width == 0) values[i] = 0;
            else if (width == 1) {
                int combined = readByte();
                values[i] = signExtend(combined & 0x0F, 4);
                if (i + 1 < 4) {
                    i += 1;
                    selector >>= 2;
                    values[i] = signExtend(combined >> 4, 4);
                }
            } else if (width == 2) {
                values[i] = signExtend(readByte(), 8);
            } else {
                values[i] = signExtend(readLe(2), 16);
            }
            selector >>= 2;
            i += 1;
        }
        return values;
    }

    public int[] readTag8_4s16() {
        int selector = readByte();
        int[] values = new int[4];
        long buffer = 0;
        boolean half = false;
        for (int i = 0; i < 4; i++) {
            int width = selector & 0x03;
            if (width == 0) {
                values[i] = 0;
            } else if (width == 1) {
                if (!half) {
                    buffer = readByte();
                    values[i] = signExtend(buffer >> 4, 4);
                    half = true;
                } else {
                    values[i] = signExtend(buffer & 0x0F, 4);
                    half = false;
                }
            } else if (width == 2) {
                if (!half) {
                    values[i] = signExtend(readByte(), 8);
                } else {
                    int lowNibble = (int) (buffer & 0x0F);
                    buffer = readByte();
                    values[i] = signExtend((lowNibble << 4) | ((int) buffer >> 4), 8);
                }
            } else {
                if (!half) {
                    values[i] = signExtend(readBe(2), 16);
                } else {
                    int lowNibble = (int) (buffer & 0x0F);
                    buffer = readByte();
                    int high = (lowNibble << 4) | ((int) buffer >> 4);
                    lowNibble = (int) (buffer & 0x0F);
                    buffer = readByte();
                    int low = (lowNibble << 4) | ((int) buffer >> 4);
                    values[i] = signExtend((high << 8) | low, 16);
                }
            }
            selector >>= 2;
        }
        return values;
    }

    private int readLe(int size) {
        int value = 0;
        for (int shift = 0; shift < size * 8; shift += 8) {
            value |= readByte() << shift;
        }
        return value;
    }

    private int readBe(int size) {
        int value = 0;
        for (int i = 0; i < size; i++) {
            value = (value << 8) | readByte();
        }
        return value;
    }

    public static long zigzagDecode(long value) {
        return (value >> 1) ^ -(value & 1);
    }

    public static int signExtend(long value, int bits) {
        long signBit = 1L << (bits - 1);
        long mask = (1L << bits) - 1;
        long v = value & mask;
        return (int) ((v & (signBit - 1)) - (v & signBit));
    }
}