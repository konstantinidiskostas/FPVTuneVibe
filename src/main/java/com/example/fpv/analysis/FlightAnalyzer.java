package com.example.fpv.analysis;

import com.example.fpv.blackbox.FlightData;

import java.util.List;

public interface FlightAnalyzer {
    String id();

    String title();

    String instructions();

    AnalysisResult analyze(FlightData flight);

    /** Relevant settings used by this flight (read from the log header), for sweep comparison. */
    default List<String> settings(FlightData flight) {
        return List.of();
    }
}
