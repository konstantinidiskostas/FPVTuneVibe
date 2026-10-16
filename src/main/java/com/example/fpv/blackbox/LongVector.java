package com.example.fpv.blackbox;

import java.util.Arrays;

public final class LongVector {
    private long[] a = new long[128];
    private int size;

    public void add(long v) {
        if (size == a.length) a = Arrays.copyOf(a, a.length * 2);
        a[size++] = v;
    }

    public long get(int i) {
        return a[i];
    }

    public int size() {
        return size;
    }

    public long[] toArray() {
        return Arrays.copyOf(a, size);
    }
}