package com.example.fpv.blackbox;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class FrameDef {
    public final char kind;
    public final List<FieldDef> fields;
    public final Map<String, Integer> index;

    public FrameDef(char kind, List<FieldDef> fields) {
        this.kind = kind;
        this.fields = fields;
        this.index = new HashMap<>();
        for (int i = 0; i < fields.size(); i++) {
            index.put(fields.get(i).name, i);
        }
    }

    public int size() {
        return fields.size();
    }

    public boolean has(String name) {
        return index.containsKey(name);
    }
}