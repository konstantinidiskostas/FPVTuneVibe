package com.example.fpv.blackbox;

public final class FieldDef {
    public final String name;
    public final boolean signed;
    public final int predictor;
    public final int encoding;

    public FieldDef(String name, boolean signed, int predictor, int encoding) {
        this.name = name;
        this.signed = signed;
        this.predictor = predictor;
        this.encoding = encoding;
    }
}