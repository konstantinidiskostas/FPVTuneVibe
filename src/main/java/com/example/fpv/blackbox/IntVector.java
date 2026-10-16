package com.example.fpv.blackbox;

import java.util.Arrays;

public final class IntVector {
    private int[] a = new int[128];
    private int size;

    public void add(int v) {
        if (size == a.length) a = Arrays.copyOf(a, a.length * 2);
        a[size++] = v;
    }

    public void addAll(int[] values) {
        if (size + values.length > a.length) {
            int target = a.length * 2;
            while (target < size + values.length) target *= 2;
            a = Arrays.copyOf(a, target);
        }
        System.arraycopy(values, 0, a, size, values.length);
        size += values.length;
    }

    public int get(int i) {
        return a[i];
    }

    public int size() {
        return size;
    }

    public int[] toArray() {
        return Arrays.copyOf(a, size);
    }
}