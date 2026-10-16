package com.example.fpv.blackbox;

import java.util.LinkedHashMap;
import java.util.Map;

public final class Event {
    public final int code;
    public final String name;
    public final long timeUs;
    public final Map<String, Object> data;

    public Event(int code, String name, long timeUs, Map<String, Object> data) {
        this.code = code;
        this.name = name;
        this.timeUs = timeUs;
        this.data = data;
    }

    public static Event of(int code, String name, long timeUs) {
        return new Event(code, name, timeUs, new LinkedHashMap<>());
    }

    public Event with(String key, Object value) {
        data.put(key, value);
        return this;
    }

    public Object get(String key) {
        return data.get(key);
    }
}