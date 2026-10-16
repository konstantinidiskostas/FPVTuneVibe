package com.example.fpv;

import com.example.fpv.analysis.FlightAnalyzer;
import com.example.fpv.analysis.StepResponseAnalyzer;
import com.example.fpv.analysis.FilterAnalyzer;
import com.example.fpv.analysis.TrackingAnalyzer;
import com.example.fpv.analysis.AnomalyAnalyzer;
import com.example.fpv.analysis.SpectroAnalyzer;
import com.example.fpv.blackbox.FlightData;
import com.example.fpv.blackbox.LogLoader;
import com.example.fpv.ui.Wizard;
import com.example.fpv.web.WebServer;

import java.awt.Desktop;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class Main {

    public static void main(String[] args) throws Exception {
        String mode = "serve";
        int port = 8080;
        List<String> rest = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--batch" -> mode = "batch";
                case "--wizard" -> mode = "wizard";
                case "--serve" -> mode = "serve";
                case "--port" -> {
                    if (i + 1 < args.length) port = parseInt(args[++i], 8080);
                }
                default -> {
                    if (a.startsWith("--port=")) {
                        port = parseInt(a.substring("--port=".length()), 8080);
                    } else {
                        rest.add(a);
                    }
                }
            }
        }
        if (rest.size() > 1) {
            System.out.println("Αγνόησα τα επιπλέον ορίσματα: " + rest.subList(1, rest.size()));
        }
        Path base = rest.isEmpty()
                ? Paths.get(System.getProperty("user.home"), "Downloads")
                : Paths.get(rest.get(0));

        FlightAnalyzer filter = new FilterAnalyzer();
        FlightAnalyzer pid = new StepResponseAnalyzer();
        FlightAnalyzer tracking = new TrackingAnalyzer();
        FlightAnalyzer anomaly = new AnomalyAnalyzer();
        FlightAnalyzer spectro = new SpectroAnalyzer();
        Map<String, FlightAnalyzer> byId = new java.util.LinkedHashMap<>();
        byId.put("filter", filter);
        byId.put("pid", pid);
        byId.put("tracking", tracking);
        byId.put("anomaly", anomaly);
        byId.put("spectro", spectro);

        if (mode.equals("serve")) {
            Path path = Files.exists(base) ? base : Paths.get(System.getProperty("user.home"));
            Path startDir = Files.isDirectory(path)
                    ? path
                    : (path.getParent() != null ? path.getParent()
                    : Paths.get(System.getProperty("user.home")));
            List<FlightData> initial = new ArrayList<>();
            if (Files.isRegularFile(path)) {
                System.out.println("Φόρτωση log: " + path);
                initial.addAll(LogLoader.loadFile(path, 0));
                System.out.printf("Φορτώθηκαν %d πτήσεις (sessions)%n", initial.size());
            } else {
                System.out.println("Ξεκινά χωρίς φορτωμένο log — διάλεξε log από τη σελίδα.");
                System.out.println("Αρχικός φάκελος περιήγησης: " + startDir);
            }
            serve(initial, byId, startDir, port);
            return;
        }

        Path path = base;
        if (!Files.exists(path)) {
            System.out.println("Δεν υπάρχει αυτό το path: " + path);
            System.exit(1);
        }

        System.out.println("Φόρτωση blackbox logs από: " + path + "  ...");
        long t0 = System.currentTimeMillis();
        List<FlightData> flights = new ArrayList<>();
        if (Files.isDirectory(path)) {
            flights.addAll(LogLoader.loadDirectory(path));
        } else {
            flights.addAll(LogLoader.loadFile(path, 0));
        }
        if (flights.isEmpty()) {
            System.out.println("Δεν βρέθηκαν έγκυρα blackbox sessions στο " + path);
            System.out.println("Ψάξε για αρχεία .bbl / .bfl");
            System.exit(1);
        }
        System.out.printf("Φορτώθηκαν %d πτήσεις σε %d ms%n", flights.size(), System.currentTimeMillis() - t0);

        List<FlightAnalyzer> analyzers = List.of(filter, pid, tracking, anomaly, spectro);
        switch (mode) {
            case "batch" -> runBatch(flights, analyzers);
            default -> new Wizard(flights, analyzers).run();
        }
    }

    private static void serve(List<FlightData> flights, Map<String, FlightAnalyzer> analyzers, Path startDir, int port)
            throws Exception {
        String url = new WebServer(flights, analyzers, startDir).start(port);
        System.out.println("Web UI: " + url);
        System.out.println("Πάτα Ctrl+C για τερματισμό.");
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
            }
        } catch (Throwable ignored) {
        }
        Thread.currentThread().join();
    }

    private static void runBatch(List<FlightData> flights, List<FlightAnalyzer> analyzers) {
        for (FlightAnalyzer a : analyzers) {
            System.out.println();
            System.out.println("############ " + a.title() + " ############");
            for (FlightData f : flights) {
                var r = a.analyze(f);
                System.out.println("----- " + f.sourceName + " S#" + (f.sessionIndex + 1) + " -----");
                System.out.printf("SCORE %.0f | %s%n", r.score, r.verdict);
                for (String line : r.findings) System.out.println("   " + line);
            }
        }
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}