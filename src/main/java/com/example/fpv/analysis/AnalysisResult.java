package com.example.fpv.analysis;

import java.util.List;

public final class AnalysisResult {
    public final double score;
    public final String verdict;
    public final List<String> findings;
    public final List<Chart> charts;
    public final String advice;

    public AnalysisResult(double score, String verdict, List<String> findings) {
        this(score, verdict, findings, List.of(), null);
    }

    public AnalysisResult(double score, String verdict, List<String> findings, List<Chart> charts) {
        this(score, verdict, findings, charts, null);
    }

    public AnalysisResult(double score, String verdict, List<String> findings, List<Chart> charts, String advice) {
        this.score = score;
        this.verdict = verdict;
        this.findings = findings;
        this.charts = charts != null ? charts : List.of();
        this.advice = advice;
    }
}
