package com.example.fpv.analysis;

public final class Chart {
    public final String key;
    public final String kind;
    public final String title;
    public final String xLabel;
    public final String yLabel;
    public final double x0;
    public final double dx;
    public final double refY;
    public final double[] y;
    public final double[] metrics;
    public final String[] metricLabels;
    public final String seriesName;

    public Chart(String key, String kind, String title, String xLabel, String yLabel,
                 double x0, double dx, double refY, double[] y,
                 double[] metrics, String[] metricLabels) {
        this(key, kind, title, xLabel, yLabel, x0, dx, refY, y, metrics, metricLabels, null);
    }

    public Chart(String key, String kind, String title, String xLabel, String yLabel,
                 double x0, double dx, double refY, double[] y,
                 double[] metrics, String[] metricLabels, String seriesName) {
        this.key = key;
        this.kind = kind;
        this.title = title;
        this.xLabel = xLabel;
        this.yLabel = yLabel;
        this.x0 = x0;
        this.dx = dx;
        this.refY = refY;
        this.y = y;
        this.metrics = metrics;
        this.metricLabels = metricLabels;
        this.seriesName = seriesName;
    }
}
