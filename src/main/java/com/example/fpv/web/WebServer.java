package com.example.fpv.web;

import com.example.fpv.analysis.AnalysisResult;
import com.example.fpv.analysis.Chart;
import com.example.fpv.analysis.FlightAnalyzer;
import com.example.fpv.analysis.Guide;
import com.example.fpv.analysis.ReportMetrics;
import com.example.fpv.blackbox.Event;
import com.example.fpv.blackbox.FlightData;
import com.example.fpv.blackbox.Header;
import com.example.fpv.blackbox.LogLoader;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

public final class WebServer {
    private volatile List<FlightData> flights;
    private final Map<String, FlightAnalyzer> analyzers;
    private final Path startDir;

    public WebServer(List<FlightData> flights, Map<String, FlightAnalyzer> analyzers, Path startDir) {
        this.flights = flights != null ? flights : new ArrayList<>();
        this.analyzers = analyzers;
        this.startDir = startDir != null ? startDir.toAbsolutePath().normalize() : null;
    }

    public String start(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/api/guides", this::handleGuides);
        server.createContext("/api/flights", this::handleFlights);
        server.createContext("/api/analyze", this::handleAnalyze);
        server.createContext("/api/ai-report", this::handleAiReport);
        server.createContext("/api/header/", this::handleHeader);
        server.createContext("/api/browse", this::handleBrowse);
        server.createContext("/api/load", this::handleLoad);
        server.createContext("/api/load-file", this::handleLoadFile);
        server.createContext("/api/clear", this::handleClear);
        server.createContext("/favicon.ico", ex -> {
            ex.sendResponseHeaders(204, -1);
            ex.close();
        });
        server.createContext("/", this::handleRoot);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        return "http://127.0.0.1:" + port + "/";
    }

    private void handleGuides(HttpExchange ex) throws IOException {
        try {
            StringBuilder sb = new StringBuilder("[");
            List<Guide.Section> sections = Guide.sections();
            for (int s = 0; s < sections.size(); s++) {
                if (s > 0) sb.append(',');
                Guide.Section sec = sections.get(s);
                sb.append("{\"id\":").append(Json.esc(sec.id()))
                        .append(",\"title\":").append(Json.esc(sec.title()))
                        .append(",\"home\":").append(Json.esc(sec.home()))
                        .append(",\"steps\":[");
                for (int i = 0; i < sec.steps().size(); i++) {
                    if (i > 0) sb.append(',');
                    Guide.Step st = sec.steps().get(i);
                    sb.append("{\"id\":").append(Json.esc(st.id()))
                            .append(",\"phase\":").append(Json.esc(st.phase()))
                            .append(",\"title\":").append(Json.esc(st.title()))
                            .append(",\"type\":").append(Json.esc(st.type()))
                            .append(",\"instructions\":").append(Json.esc(st.instructions()))
                            .append(",\"url\":").append(Json.esc(st.url()))
                            .append(",\"analyzer\":").append(Json.esc(st.analyzer() == null ? "" : st.analyzer()))
                            .append('}');
                }
                sb.append("]}");
            }
            respond(ex, sb.append(']').toString());
        } catch (Throwable t) {
            System.err.println("[guides] σφάλμα: " + t);
            respondError(ex, String.valueOf(t.getMessage()));
        }
    }

    private void handleFlights(HttpExchange ex) throws IOException {
        respond(ex, flightsJson());
    }

    private String flightsJson() {
        List<FlightData> fs = this.flights;
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < fs.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(flightJson(fs, i));
        }
        return sb.append(']').toString();
    }

    private void handleAnalyze(HttpExchange ex) throws IOException {
        try {
            Map<String, String> q = parseQuery(ex);
            String analyzerId = q.getOrDefault("analyzer", "pid");
            double min = parseDouble(q.get("min"), 0.0);
            FlightAnalyzer a = analyzers.get(analyzerId);
            if (a == null) {
                respondError(ex, "Άγνωστος analyzer: " + analyzerId);
                return;
            }
            List<FlightData> fs = this.flights;
            if (fs.isEmpty()) {
                respondError(ex, "Δεν έχει φορτωθεί log. Πάτησε «Φόρτωση log…» πρώτα.");
                return;
            }
            List<String> wanted = queryValues(ex, "flight");
            java.util.Set<String> sel = wanted.isEmpty() ? null : new java.util.HashSet<>(wanted);

            long t0 = System.currentTimeMillis();
            List<Object[]> rows = new ArrayList<>();
            for (int i = 0; i < fs.size(); i++) {
                FlightData f = fs.get(i);
                if (sel != null && !sel.contains(flightKey(f))) continue;
                double dur = f.mainCount > 1
                        ? (f.timeAt(f.mainCount - 1) - f.timeAt(0)) / 1_000_000.0 : 0;
                if (dur < min) continue;
                AnalysisResult r;
                try {
                    r = a.analyze(f);
                } catch (RuntimeException ex2) {
                    r = new AnalysisResult(0, "Αποτυχία ανάλυσης: " + ex2, List.of(String.valueOf(ex2)));
                }
                rows.add(new Object[]{i + 1, dur, r});
            }
            rows.sort(Comparator.comparingDouble(o -> ((AnalysisResult) ((Object[]) o)[2]).score).reversed());

            if (rows.isEmpty()) {
                respond(ex, "{\"step\":" + Json.esc(a.title())
                        + ",\"stepId\":" + Json.esc(analyzerId)
                        + ",\"instructions\":" + Json.esc(a.instructions())
                        + ",\"tied\":false,\"results\":[],\"charts\":[]}");
                return;
            }

            boolean tied = rows.size() > 1;
            AnalysisResult best = (AnalysisResult) ((Object[]) rows.get(0))[2];
            AnalysisResult second = tied ? (AnalysisResult) ((Object[]) rows.get(1))[2] : null;
            tied = second != null && Math.abs(best.score - second.score) < 5;

            StringBuilder resSb = new StringBuilder("[");
            java.util.Map<String, Chart> templates = new java.util.LinkedHashMap<>();
            java.util.Map<String, List<Object[]>> linesByKey = new java.util.LinkedHashMap<>();
            for (int i = 0; i < rows.size(); i++) {
                if (i > 0) resSb.append(',');
                Object[] row = rows.get(i);
                int idx = (Integer) row[0];
                AnalysisResult r = (AnalysisResult) row[2];
                FlightData fd = fs.get(idx - 1);
                List<String> sts = a.settings(fd);
                resSb.append("{\"rank\":").append(i + 1)
                        .append(",\"index\":").append(idx)
                        .append(",\"score\":").append(Json.num(r.score))
                        .append(",\"verdict\":").append(Json.esc(r.verdict))
                        .append(",\"advice\":").append(Json.esc(r.advice))
                        .append(",\"settings\":[");
                for (int j = 0; j < sts.size(); j++) {
                    if (j > 0) resSb.append(',');
                    resSb.append(Json.esc(sts.get(j)));
                }
                resSb.append("],\"findings\":[");
                for (int j = 0; j < r.findings.size(); j++) {
                    if (j > 0) resSb.append(',');
                    resSb.append(Json.esc(r.findings.get(j)));
                }
                resSb.append("]}");

                String label = fd.sourceName + " S#" + (fd.sessionIndex + 1);
                for (Chart c : r.charts) {
                    templates.putIfAbsent(c.key, c);
                    linesByKey.computeIfAbsent(c.key, k -> new ArrayList<>())
                            .add(new Object[]{label, c.y, c.metrics, c.seriesName});
                }
            }
            resSb.append(']');

            StringBuilder chSb = new StringBuilder("[");
            int ci = 0;
            for (java.util.Map.Entry<String, Chart> e : templates.entrySet()) {
                Chart c = e.getValue();
                if (ci++ > 0) chSb.append(',');
                chSb.append("{\"key\":").append(Json.esc(c.key))
                        .append(",\"kind\":").append(Json.esc(c.kind))
                        .append(",\"title\":").append(Json.esc(c.title))
                        .append(",\"xLabel\":").append(Json.esc(c.xLabel))
                        .append(",\"yLabel\":").append(Json.esc(c.yLabel))
                        .append(",\"x0\":").append(Json.num(c.x0))
                        .append(",\"dx\":").append(Json.num(c.dx))
                        .append(",\"refY\":").append(Double.isNaN(c.refY) ? "null" : Json.num(c.refY))
                        .append(",\"metricLabels\":[");
                for (int j = 0; j < c.metricLabels.length; j++) {
                    if (j > 0) chSb.append(',');
                    chSb.append(Json.esc(c.metricLabels[j]));
                }
                chSb.append("],\"lines\":[");
                List<Object[]> lines = linesByKey.get(c.key);
                for (int l = 0; l < lines.size(); l++) {
                    if (l > 0) chSb.append(',');
                    Object[] ln = lines.get(l);
                    String lname = (String) ln[0];
                    if (ln.length > 3 && ln[3] != null) lname = (String) ln[3];
                    chSb.append("{\"name\":").append(Json.esc(lname)).append(",\"y\":[");
                    double[] y = (double[]) ln[1];
                    for (int k = 0; k < y.length; k++) {
                        if (k > 0) chSb.append(',');
                        chSb.append(Json.num(y[k]));
                    }
                    chSb.append("],\"m\":[");
                    double[] mo = (double[]) ln[2];
                    for (int k = 0; k < mo.length; k++) {
                        if (k > 0) chSb.append(',');
                        chSb.append(Json.num(mo[k]));
                    }
                    chSb.append("]}");
                }
                chSb.append("]}");
            }
            chSb.append(']');

            StringBuilder sb = new StringBuilder();
            sb.append("{\"step\":").append(Json.esc(a.title()))
                    .append(",\"stepId\":").append(Json.esc(analyzerId))
                    .append(",\"instructions\":").append(Json.esc(a.instructions()))
                    .append(",\"tied\":").append(tied)
                    .append(",\"results\":").append(resSb)
                    .append(",\"charts\":").append(chSb)
                    .append('}');
            respond(ex, sb.toString());
            System.out.printf("[analyze] %s min=%.1f -> %d πτήσεις σε %d ms%n",
                    analyzerId, min, rows.size(), System.currentTimeMillis() - t0);
        } catch (Throwable t) {
            System.err.println("[analyze] σφάλμα: " + t);
            respondError(ex, String.valueOf(t.getMessage()));
        }
    }

    private void handleHeader(HttpExchange ex) throws IOException {
        String raw = ex.getRequestURI().getPath().substring("/api/header/".length());
        int idx = parseInt(raw, -1);
        List<FlightData> fs = this.flights;
        if (idx < 1 || idx > fs.size()) {
            respond(ex, "{\"error\":\"no such flight\"}");
            return;
        }
        FlightData f = fs.get(idx - 1);
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"index\":").append(idx)
                .append(",\"source\":").append(Json.esc(f.sourceName))
                .append(",\"session\":").append(f.sessionIndex + 1)
                .append(",\"craft\":").append(Json.esc(f.header.craftName()))
                .append(",\"board\":").append(Json.esc(f.header.board()))
                .append(",\"firmware\":").append(Json.esc(f.header.firmware()))
                .append(",\"dataVersion\":").append(f.header.dataVersion())
                .append(",\"framesI\":").append(f.stats.frameCounts.getOrDefault('I', 0))
                .append(",\"framesP\":").append(f.stats.frameCounts.getOrDefault('P', 0))
                .append(",\"framesS\":").append(f.slowCount)
                .append(",\"framesG\":").append(f.gpsCount)
                .append(",\"corruptFrames\":").append(f.stats.corruptFrames)
                .append(",\"corruptBytes\":").append(f.stats.corruptBytes)
                .append(",\"resyncs\":").append(f.stats.resyncs)
                .append(",\"reachedLogEnd\":").append(f.stats.reachedLogEnd)
                .append(",\"hasHome\":").append(f.hasHome)
                .append(",\"events\":[");
        for (int i = 0; i < f.events.size(); i++) {
            Event e = f.events.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"code\":").append(e.code)
                    .append(",\"name\":").append(Json.esc(e.name))
                    .append(",\"timeS\":").append(Json.num(e.timeUs / 1_000_000.0))
                    .append('}');
        }
        respond(ex, sb.append("]}").toString());
    }

    private void handleBrowse(HttpExchange ex) throws IOException {
        try {
            Map<String, String> q = parseQuery(ex);
            String raw = q.get("path");
            Path dir;
            if (raw == null || raw.isBlank()) {
                dir = startDir != null ? startDir : Paths.get(System.getProperty("user.home"));
            } else {
                dir = Paths.get(raw).toAbsolutePath().normalize();
            }
            if (Files.isRegularFile(dir)) dir = dir.getParent();
            if (dir == null || !Files.isDirectory(dir)) {
                respond(ex, "{\"error\":\"Μη έγκυρος φάκελος\"}");
                return;
            }
            List<Path> dirs = new ArrayList<>();
            List<Path> files = new ArrayList<>();
            try (Stream<Path> s = Files.list(dir)) {
                for (Path p : (Iterable<Path>) s::iterator) {
                    String name = p.getFileName().toString();
                    if (name.startsWith(".")) continue;
                    if (Files.isDirectory(p)) {
                        dirs.add(p);
                    } else {
                        String lower = name.toLowerCase();
                        if (lower.endsWith(".bbl") || lower.endsWith(".bfl")) files.add(p);
                    }
                }
            }
            dirs.sort(Comparator.comparing(p -> p.getFileName().toString().toLowerCase()));
            files.sort(Comparator.comparing(p -> p.getFileName().toString().toLowerCase()));

            StringBuilder sb = new StringBuilder("{");
            sb.append("\"path\":").append(Json.esc(dir.toString()))
                    .append(",\"parent\":").append(Json.esc(dir.getParent() != null ? dir.getParent().toString() : ""))
                    .append(",\"dirs\":[");
            for (int i = 0; i < dirs.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append("{\"name\":").append(Json.esc(dirs.get(i).getFileName().toString()))
                        .append(",\"path\":").append(Json.esc(dirs.get(i).toString())).append('}');
            }
            sb.append("],\"files\":[");
            for (int i = 0; i < files.size(); i++) {
                if (i > 0) sb.append(',');
                long size = Files.size(files.get(i));
                sb.append("{\"name\":").append(Json.esc(files.get(i).getFileName().toString()))
                        .append(",\"path\":").append(Json.esc(files.get(i).toString()))
                        .append(",\"size\":").append(size).append('}');
            }
            respond(ex, sb.append("]}").toString());
        } catch (Throwable t) {
            System.err.println("[browse] σφάλμα: " + t);
            respondError(ex, String.valueOf(t.getMessage()));
        }
    }

    private void handleLoad(HttpExchange ex) throws IOException {
        try {
            Map<String, String> q = parseQuery(ex);
            String raw = q.get("path");
            if (raw == null || raw.isBlank()) {
                respondError(ex, "Δώσε path");
                return;
            }
            Path p = Paths.get(raw).toAbsolutePath().normalize();
            if (!Files.exists(p)) {
                respondError(ex, "Δεν υπάρχει: " + p);
                return;
            }
            long t0 = System.currentTimeMillis();
            List<FlightData> loaded = new ArrayList<>();
            if (Files.isDirectory(p)) {
                loaded.addAll(LogLoader.loadDirectory(p));
            } else {
                loaded.addAll(LogLoader.loadFile(p, 0));
            }
            if (loaded.isEmpty()) {
                respondError(ex, "Δεν βρέθηκαν έγκυρα sessions στο " + p);
                return;
            }
            this.flights = loaded;
            respond(ex, flightsJson());
            System.out.printf("[load] %s -> %d πτήσεις σε %d ms%n", p, loaded.size(), System.currentTimeMillis() - t0);
        } catch (Throwable t) {
            System.err.println("[load] σφάλμα: " + t);
            respondError(ex, String.valueOf(t.getMessage()));
        }
    }

    private void handleLoadFile(HttpExchange ex) throws IOException {
        try {
            List<String> paths = queryValues(ex, "path");
            if (paths.isEmpty()) {
                respondError(ex, "Δώσε path");
                return;
            }
            List<FlightData> merged = new ArrayList<>(this.flights);
            java.util.Set<String> have = new java.util.HashSet<>();
            for (FlightData f : merged) have.add(flightKey(f));
            int added = 0;
            for (String raw : paths) {
                Path p = Paths.get(raw).toAbsolutePath().normalize();
                if (!Files.exists(p)) continue;
                List<FlightData> loaded = Files.isDirectory(p)
                        ? LogLoader.loadDirectory(p) : LogLoader.loadFile(p, 0);
                for (FlightData f : loaded) {
                    if (have.add(flightKey(f))) {
                        merged.add(f);
                        added++;
                    }
                }
            }
            if (added == 0) {
                respondError(ex, "Δεν προστέθηκε νέα πτήση (ίσως υπάρχει ήδη).");
                return;
            }
            this.flights = merged;
            respond(ex, flightsJson());
            System.out.printf("[load-file] +%d -> σύνολο %d πτήσεις%n", added, merged.size());
        } catch (Throwable t) {
            System.err.println("[load-file] σφάλμα: " + t);
            respondError(ex, String.valueOf(t.getMessage()));
        }
    }

    private void handleClear(HttpExchange ex) throws IOException {
        try {
            this.flights = new ArrayList<>();
            respond(ex, "[]");
            System.out.println("[clear] άδειασμα λίστας πτήσεων");
        } catch (Throwable t) {
            respondError(ex, String.valueOf(t.getMessage()));
        }
    }

    private void handleRoot(HttpExchange ex) throws IOException {
        if (!ex.getRequestURI().getPath().equals("/")) {
            byte[] body = "not found".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(404, body.length);
            ex.getResponseBody().write(body);
            ex.close();
            return;
        }
        respondHtml(ex, PAGE);
    }

    private void respond(HttpExchange ex, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(200, body.length);
        ex.getResponseBody().write(body);
        ex.close();
    }

    private void respondError(HttpExchange ex, String message) throws IOException {
        String json = "{\"error\":" + Json.esc(message == null ? "άγνωστο σφάλμα" : message) + "}";
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(200, body.length);
        ex.getResponseBody().write(body);
        ex.close();
    }

    private void respondHtml(HttpExchange ex, String html) throws IOException {
        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        ex.sendResponseHeaders(200, body.length);
        ex.getResponseBody().write(body);
        ex.close();
    }

    private String flightJson(List<FlightData> fs, int i) {
        FlightData f = fs.get(i);
        double dur = f.mainCount > 1
                ? (f.timeAt(f.mainCount - 1) - f.timeAt(0)) / 1_000_000.0 : 0;
        double corruptPct = f.stats.corruptFrames * 100.0 / Math.max(1, f.mainCount);
        return "{\"index\":" + (i + 1)
                + ",\"key\":" + Json.esc(flightKey(f))
                + ",\"source\":" + Json.esc(f.sourceName)
                + ",\"session\":" + (f.sessionIndex + 1)
                + ",\"board\":" + Json.esc(f.header.board())
                + ",\"firmware\":" + Json.esc(f.header.firmware())
                + ",\"dataVersion\":" + f.header.dataVersion()
                + ",\"durationS\":" + Json.num(dur)
                + ",\"frames\":" + f.mainCount
                + ",\"corruptPct\":" + Json.num(corruptPct)
                + '}';
    }

    private static String flightKey(FlightData f) {
        return f.sourceName + "|" + (f.sessionIndex + 1);
    }

    private Map<String, String> parseQuery(HttpExchange ex) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw == null) return out;
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            String key = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
            String val = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            out.put(key, val);
        }
        return out;
    }

    private List<String> queryValues(HttpExchange ex, String name) {
        List<String> out = new ArrayList<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw == null) return out;
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            String key = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
            if (key.equals(name)) {
                out.add(URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    private void handleAiReport(HttpExchange ex) throws IOException {
        try {
            List<FlightData> fs = this.flights;
            if (fs.isEmpty()) {
                respondError(ex, "Δεν έχει φορτωθεί log. Πάτησε «Φόρτωση log…» πρώτα.");
                return;
            }
            Map<String, String> q = parseQuery(ex);
            List<String> wanted = queryValues(ex, "flight");
            java.util.Set<String> sel = wanted.isEmpty() ? null : new java.util.HashSet<>(wanted);

            StringBuilder md = new StringBuilder();
            md.append(SYSTEM_PROMPT);

            md.append("\n\n---\n\n## Build details (reported by the pilot, not in the log)\n");
            md.append("- Drone class: ").append(pv(q, "droneClass")).append('\n');
            md.append("- Flight style: ").append(pv(q, "style")).append('\n');
            md.append("- Analysis focus: ").append(pv(q, "focus")).append('\n');
            md.append("- Battery cells: ").append(pv(q, "cells")).append('\n');
            md.append("- Prop: ").append(pv(q, "prop")).append('\n');
            md.append("- Motor KV: ").append(pv(q, "motorKv")).append('\n');
            md.append("- Prop blades: ").append(pv(q, "propBlades")).append('\n');
            md.append("- ESC firmware: ").append(pv(q, "escFirmware")).append('\n');
            md.append("- FC mounting: ").append(pv(q, "fcMounting")).append('\n');
            md.append("- Betaflight version: ").append(pv(q, "bfVersion")).append('\n');

            md.append("\n## Flights\n");
            int found = 0;
            for (FlightData f : fs) {
                if (sel != null && !sel.contains(flightKey(f))) continue;
                found++;
                appendFlight(md, f, q);
            }
            if (found == 0) {
                respondError(ex, "Καμία επιλεγμένη πτήση για αναφορά.");
                return;
            }
            respond(ex, "{\"markdown\":" + Json.esc(md.toString()) + "}");
        } catch (Exception e) {
            respondError(ex, "Σφάλμα αναφοράς: " + e);
        }
    }

    private void appendFlight(StringBuilder md, FlightData f, Map<String, String> q) {
        Header h = f.header;
        double dur = f.mainCount > 1 ? (f.timeAt(f.mainCount - 1) - f.timeAt(0)) / 1_000_000.0 : 0;
        md.append("\n### ").append(f.sourceName).append(" S#").append(f.sessionIndex + 1).append('\n');
        md.append(String.format("- Board: %s · Firmware: %s · Data version: %d%n",
                nz(h.board()), nz(h.firmware()), h.dataVersion()));
        md.append(String.format("- Duration: %.1f s · Frames: %d · Gyro rate: %.0f Hz · pid_process_denom: %s · looptime: %s%n",
                dur, f.mainCount, f.frameRateHz(), hv(h, "pid_process_denom"), hv(h, "looptime")));

        md.append("\n**Current PID settings (from log header, may change mid-flight):**\n");
        md.append("- rollPID: ").append(hv(h, "rollPID")).append(" · pitchPID: ").append(hv(h, "pitchPID"))
                .append(" · yawPID: ").append(hv(h, "yawPID")).append('\n');
        md.append("- d_max: ").append(hv(h, "d_max")).append(" · d_max_gain: ").append(hv(h, "d_max_gain"))
                .append(" · d_max_advance: ").append(hv(h, "d_max_advance")).append('\n');
        md.append("- ff_weight: ").append(hv(h, "ff_weight")).append(" · feedforward_boost: ").append(hv(h, "feedforward_boost"))
                .append(" · feedforward_max_rate_limit: ").append(hv(h, "feedforward_max_rate_limit")).append('\n');
        md.append("- iterm_windup: ").append(hv(h, "iterm_windup")).append(" · iterm_relax_cutoff: ").append(hv(h, "iterm_relax_cutoff")).append('\n');
        md.append("- rates: ").append(hv(h, "rates")).append(" · rc_rates: ").append(hv(h, "rc_rates"))
                .append(" · rc_expo: ").append(hv(h, "rc_expo")).append('\n');

        boolean simplified = "2".equals(hv(h, "simplified_pids_mode"));
        if (simplified) {
            md.append("\n**SIMPLIFIED PID TUNING MODE IS ACTIVE.** Individual PID values are DERIVED from sliders.\n");
            md.append("Recommend slider changes (`set simplified_... = X` then `simplified_tuning apply`), NOT raw `set p_roll = X`.\n");
            md.append("- simplified_master_multiplier: ").append(hv(h, "simplified_master_multiplier")).append('\n');
            md.append("- simplified_pi_gain: ").append(hv(h, "simplified_pi_gain")).append(" · simplified_i_gain: ").append(hv(h, "simplified_i_gain"))
                    .append(" · simplified_d_gain: ").append(hv(h, "simplified_d_gain")).append('\n');
            md.append("- simplified_d_max_gain: ").append(hv(h, "simplified_d_max_gain")).append(" · simplified_feedforward_gain: ").append(hv(h, "simplified_feedforward_gain")).append('\n');
            md.append("- simplified_pitch_pi_gain: ").append(hv(h, "simplified_pitch_pi_gain")).append(" · simplified_pitch_d_gain: ").append(hv(h, "simplified_pitch_d_gain")).append('\n');
            md.append("- simplified_gyro_filter: ").append(hv(h, "simplified_gyro_filter")).append(" (x").append(hv(h, "simplified_gyro_filter_multiplier")).append(")")
                    .append(" · simplified_dterm_filter: ").append(hv(h, "simplified_dterm_filter")).append(" (x").append(hv(h, "simplified_dterm_filter_multiplier")).append(")").append('\n');
        }

        md.append("\n**Filters:**\n");
        md.append("- gyro_lpf1_static_hz: ").append(hv(h, "gyro_lpf1_static_hz")).append(" · gyro_lpf1_dyn_hz: ").append(hv(h, "gyro_lpf1_dyn_hz"))
                .append(" · gyro_lpf2_static_hz: ").append(hv(h, "gyro_lpf2_static_hz")).append('\n');
        md.append("- dterm_lpf1_static_hz: ").append(hv(h, "dterm_lpf1_static_hz")).append(" · dterm_lpf1_dyn_hz: ").append(hv(h, "dterm_lpf1_dyn_hz"))
                .append(" · dterm_lpf2_static_hz: ").append(hv(h, "dterm_lpf2_static_hz")).append('\n');
        md.append("- dyn_notch_count: ").append(hv(h, "dyn_notch_count")).append(" · dyn_notch_min_hz: ").append(hv(h, "dyn_notch_min_hz"))
                .append(" · dyn_notch_max_hz: ").append(hv(h, "dyn_notch_max_hz")).append(" · dyn_notch_q: ").append(hv(h, "dyn_notch_q")).append('\n');
        md.append("- dshot_bidir: ").append(hv(h, "dshot_bidir")).append(" · rpm_filter_harmonics: ").append(hv(h, "rpm_filter_harmonics")).append('\n');

        md.append("\n**Throttle / motor:**\n");
        md.append("- tpa_rate: ").append(hv(h, "tpa_rate")).append(" · tpa_breakpoint: ").append(hv(h, "tpa_breakpoint"))
                .append(" · tpa_low_rate: ").append(hv(h, "tpa_low_rate")).append('\n');
        md.append("- thrust_linear: ").append(hv(h, "thrust_linear")).append(" · throttle_boost: ").append(hv(h, "throttle_boost"))
                .append(" · vbat_sag_compensation: ").append(hv(h, "vbat_sag_compensation")).append('\n');
        md.append("- dyn_idle_min_rpm: ").append(hv(h, "dyn_idle_min_rpm")).append(" · motor_kv: ").append(hv(h, "motor_kv"))
                .append(" · motor_poles: ").append(hv(h, "motor_poles")).append('\n');

        appendExtended(md, f, parseInt(q.getOrDefault("propBlades", "3"), 3));

        md.append("\n**Measurements (from this log):**\n");
        for (Map.Entry<String, FlightAnalyzer> e : analyzers.entrySet()) {
            AnalysisResult r;
            try {
                r = e.getValue().analyze(f);
            } catch (RuntimeException ex2) {
                md.append("- ").append(e.getKey()).append(": αποτυχία (").append(ex2).append(")\n");
                continue;
            }
            md.append(String.format("%n#### %s — score %.0f%n", e.getKey(), r.score));
            md.append("Verdict: ").append(r.verdict).append('\n');
            for (String fi : r.findings) md.append("- ").append(fi).append('\n');
            for (Chart c : r.charts) {
                if (c.metrics != null && c.metrics.length > 0 && c.metricLabels != null) {
                    StringBuilder one = new StringBuilder();
                    for (int i = 0; i < c.metrics.length; i++) {
                        if (i > 0) one.append(", ");
                        one.append(c.metricLabels[i]).append('=').append(String.format("%.2f", c.metrics[i]));
                    }
                    md.append("- ").append(c.title).append(": ").append(one).append('\n');
                }
            }
        }
    }

    private static void appendExtended(StringBuilder md, FlightData f, int blades) {
        ReportMetrics m = ReportMetrics.compute(f, blades);
        boolean any = m.hasVbat || m.hasMotor || m.hasThrottle || m.hasBands || m.hasFilter;
        if (!any) return;

        if (m.hasVbat) {
            md.append("\n#### Battery Information\n");
            md.append(String.format("- Cells: %d · start: %.2f V · end: %.2f V · min under load: %.2f V%n",
                    m.cells, m.vStart, m.vEnd, m.vMin));
            md.append(String.format("- Rest voltage/cell: %.2f V · min/cell: %.2f V · sag/cell: %.2f V%n",
                    m.restPerCell, m.minPerCell, m.sagPerCell));
        }

        md.append("\n#### Loop Rates\n");
        md.append(String.format("- Gyro/logging sample rate: %.0f Hz · pid_process_denom: %s · looptime: %s%n",
                m.fs, hv(f.header, "pid_process_denom"), hv(f.header, "looptime")));
        md.append("- (logging rate ≈ PID loop rate in Betaflight)\n");

        if (m.hasMotor) {
            md.append("\n#### Motor & Telemetry Statistics\n");
            StringBuilder pct = new StringBuilder();
            for (int k = 0; k < m.motors; k++) {
                if (k > 0) pct.append(" · ");
                pct.append("M").append(k + 1).append(": ").append(String.format("%.0f%%", m.motorPct[k]));
            }
            md.append("- Avg motor output (mid-throttle): ").append(pct).append('\n');
            md.append(String.format("- Motor imbalance (worst deviation): %.0f%%%n", m.motorImbalancePct));
            if (m.hasErpm) {
                StringBuilder rpm = new StringBuilder();
                for (int k = 0; k < m.motors && k < m.rpmAvg.length; k++) {
                    if (k > 0) rpm.append(" · ");
                    rpm.append("M").append(k + 1).append(": ").append(String.format("%.0f", m.rpmAvg[k]));
                }
                md.append("- Avg RPM (approx): ").append(rpm)
                        .append(String.format(" · min %.0f · max %.0f%n", m.rpmMin, m.rpmMax));
                md.append(String.format("- Rotation frequency: %.0f Hz · blade-pass frequency (~%d blades): %.0f Hz%n",
                        m.rotationHz, blades, m.bladePassHz));
            }
        }

        md.append("\n#### Flight Context\n");
        md.append(String.format("- Data integrity: %.1f%% · corrupt frames: %d · log ended cleanly: %s%n",
                m.logCompletePct, m.corruptFrames, m.reachedLogEnd ? "yes" : "no"));
        md.append("- Disarm reasons: ").append(m.disarmReasons).append('\n');

        if (m.hasThrottle) {
            md.append("\n#### Per-regime breakdown (by throttle)\n");
            md.append("| Regime | Avg throttle | Gyro noise (deg/s RMS) | Tracking error (deg/s RMS) |\n");
            md.append("|---|---|---|---|\n");
            String[] names = {"Low (<30%)", "Mid (30-70%)", "High (>70%)"};
            for (int b = 0; b < 3; b++) {
                md.append(String.format("| %s | %.0f%% | %.2f | %.2f |%n",
                        names[b], m.thrByThr[b], m.noiseByThr[b], m.trkErrByThr[b]));
            }
            md.append(String.format("- Overall average throttle: %.0f%%%n", m.avgThrottlePct));
        }

        if (m.hasBands) {
            md.append("\n#### Frequency-Domain Noise Analysis (deg/s RMS per band)\n");
            md.append("| Axis | 0-50 Hz (propwash) | 50-150 Hz (motor) | 150-350 Hz (frame) | 350+ Hz (high) |\n");
            md.append("|---|---|---|---|---|\n");
            String[] ax = {"Roll", "Pitch", "Yaw"};
            for (int a = 0; a < 3; a++) {
                md.append(String.format("| %s | %.1f | %.1f | %.1f | %.1f |%n",
                        ax[a], m.bands[a][0], m.bands[a][1], m.bands[a][2], m.bands[a][3]));
            }
        }

        if (m.hasFilter) {
            md.append("\n#### Filter Effectiveness Assessment\n");
            md.append(String.format("- Gyro noise raw (HP RMS): %.2f deg/s · filtered: %.2f deg/s · reduction: %.0f%%%n",
                    m.noiseRawRms, m.noiseFiltRms, m.filterReductionPct));
            md.append("- Grade: ").append(filterGrade(m.filterReductionPct)).append('\n');
        }
    }

    private static String filterGrade(double reductionPct) {
        if (reductionPct >= 60) return "excellent (φίλτρα δουλεύουν πολύ καλά)";
        if (reductionPct >= 40) return "good";
        if (reductionPct >= 20) return "moderate (ίσως χρειάζεται περισσότερο filtering)";
        return "low (έλεγξε φίλτρα — ελάχιστη μείωση θορύβου)";
    }

    private static String pv(Map<String, String> q, String key) {
        String v = q.get(key);
        return v == null || v.isBlank() ? "-" : v;
    }

    private static String hv(Header h, String key) {
        String v = h.raw.get(key);
        return v == null || v.isBlank() ? "-" : v;
    }

    private static String nz(String s) {
        return s == null || s.isBlank() ? "-" : s;
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static double parseDouble(String s, double def) {
        try {
            return Double.parseDouble(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static final String SYSTEM_PROMPT = """
            You are an expert FPV drone PID tuning engineer. You analyze Betaflight blackbox log data and provide precise, actionable tuning recommendations.

            ## Your Approach
            1. First, summarize what you observe in the data — identify the key patterns and anomalies.
            2. Identify the single most critical issue that should be addressed first.
            3. Provide ranked recommendations, each with the exact Betaflight CLI commands.
            4. For each recommendation, explain *why* the change helps, not just *what* to change.

            ## Rules
            - Be conservative: recommend small incremental changes (e.g., ~5-10% on PIDs per iteration).
            - Only suggest CLI commands that exist in the user's firmware version (specified in the data).
            - Format CLI commands in code blocks: `set p_pitch = 50`
            - If data for a metric says "not measured", do NOT make recommendations based on that metric.
            - If you are unsure, state your confidence level and why.
            - CRITICAL: If Simplified PID Tuning mode is active, recommend slider changes (e.g., `set simplified_d_gain = 120`) followed by `simplified_tuning apply`, NOT individual PID set commands. Individual PID values will be overwritten by the sliders.
            - When dynamic filters are enabled, the static Hz value is the minimum — the actual cutoff rises with throttle. Recommend changes to the dynamic range, not just the static value.

            ## Reference Ranges (typical 5-inch freestyle quad, Betaflight 4.3+)
            Use these as baselines — actual values depend on drone size, weight, and style:

            ### PID Values
            | Parameter | Low | Typical | High | Notes |
            |-----------|-----|---------|------|-------|
            | P (Roll/Pitch) | 30-40 | 42-55 | 60-80 | Higher = sharper response, risk of oscillation |
            | I (Roll/Pitch) | 60-80 | 80-120 | 120-150 | Higher = better hover stability, risk of bounce-back |
            | D-Max (Roll/Pitch) | 25-30 | 32-42 | 45-60 | Higher = better propwash handling, risk of hot motors |
            | D-Min (Roll/Pitch) | 18-22 | 22-30 | 30-40 | Active D at low stick input |
            | FF (Roll/Pitch) | 80-100 | 100-140 | 140-200 | Higher = faster stick tracking, risk of overshoot |
            | P (Yaw) | 20-30 | 30-45 | 45-60 | Lower than roll/pitch; too high = yaw oscillation |
            | I (Yaw) | 60-80 | 80-120 | 120-150 | Critical for yaw holding |

            ### Step Response
            | Metric | Good | Acceptable | Problematic |
            |--------|------|------------|-------------|
            | Overshoot | <5% | 5-15% | >15% (lower P or raise D) |
            | Undershoot | <3% | 3-8% | >8% (raise P or FF) |
            | Rise Time | 15-25ms | 25-40ms | >40ms (raise P or FF) |
            | Settling Time | <50ms | 50-80ms | >80ms (tune D or D-Max) |

            ### Noise Levels
            | Metric | Good | Moderate | Problematic |
            |--------|------|----------|-------------|
            | Gyro Noise RMS | <3 deg/s | 3-8 deg/s | >8 deg/s |
            | D-Term Noise RMS | <5 | 5-15 | >15 (hot motors likely) |

            ### Tracking Error
            | Metric | Good | Acceptable | Problematic |
            |--------|------|------------|-------------|
            | RMS Error | <2 deg/s | 2-5 deg/s | >5 deg/s (raise P/I/FF) |

            ### Filter Settings
            | Parameter | Aggressive (racing) | Balanced | Conservative (cine) |
            |-----------|-------------------|----------|---------------------|
            | Gyro LPF1 | 250-300Hz | 150-250Hz | 100-150Hz |
            | Gyro LPF2 | 400-500Hz | 250-400Hz | 200-300Hz |
            | D-Term LPF1 | 150-200Hz | 100-150Hz | 80-120Hz |
            | D-Term LPF2 | 250-350Hz | 150-250Hz | 120-200Hz |
            | Filter Latency | <3ms excellent | 3-6ms good | 6-10ms acceptable, >10ms causes lag |

            ### Motor Health
            | Metric | Good | Warning | Critical |
            |--------|------|---------|----------|
            | Saturation | <5% | 5-15% | >15% (lower authority/PIDs) |
            | Differential | <15% | 15-30% | >30% (balance issue or bent prop) |

            ### Propwash
            | Severity Score | Rating | Action |
            |---------------|--------|--------|
            | 0-20 | Good | Minor tweaks only |
            | 20-50 | Moderate | Adjust D-Max/D-Min ratio, check filters |
            | 50+ | Severe | Significant D/filter rework needed |

            ## How to respond
            A pilot will read your reply directly in a chat, so write clear, skimmable Markdown — no JSON or rigid schema.
            1. Start with one or two sentences: what this flight looks like overall and the single most important issue to fix first.
            2. Then give ranked recommendations, most impactful first. For each: what to change, the exact Betaflight CLI command(s) in a code block, and a brief "why".
            3. Close with any quick wins and anything that already looks good.
            Group the CLI commands so the pilot can copy a block, apply it, and re-fly to compare. Keep it concise.
            """;

    private static final String PAGE = """
            <!DOCTYPE html>
            <html lang="el">
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>FPV Log Analyzer</title>
            <style>
              :root{--bg:#0d1220;--panel:#151d31;--panel2:#1a2338;--line:#26345a;--txt:#e8edf7;
                     --dim:#8fa0c4;--acc:#4f8cff;--ok:#2ecc71;--warn:#f1c40f;--bad:#e74c3c;}
              *{box-sizing:border-box}
              body{margin:0;font-family:ui-sans-serif,system-ui,Segoe UI,Roboto,sans-serif;
                   background:var(--bg);color:var(--txt);}
              header{padding:16px 24px;background:linear-gradient(180deg,#111a30,#0d1220);
                     border-bottom:1px solid var(--line);display:flex;align-items:center;gap:16px;flex-wrap:wrap}
              header h1{font-size:19px;margin:0;letter-spacing:.3px}
              header .sub{color:var(--dim);font-size:13px}
              header .status{margin-left:auto;background:var(--panel);border:1px solid var(--line);
                     border-radius:999px;padding:6px 14px;font-size:13px;color:var(--dim)}
              header .status b{color:var(--txt)}
              .wrap{max-width:1220px;margin:0 auto;padding:18px 24px 80px}
              .tabs{display:flex;gap:8px;flex-wrap:wrap;margin-bottom:16px}
              .tab{background:var(--panel);border:1px solid var(--line);border-radius:10px;padding:8px 16px;
                   font-size:14px;color:var(--dim);cursor:pointer;user-select:none}
              .tab.on{background:var(--acc);color:#0d1220;border-color:var(--acc);font-weight:700}
              .cols{display:grid;grid-template-columns:minmax(0,.85fr) minmax(0,1.35fr);gap:20px;align-items:start}
              @media(max-width:940px){.cols{grid-template-columns:1fr}}
              .card{background:var(--panel);border:1px solid var(--line);border-radius:12px;padding:16px 18px;margin-bottom:18px}
              .card h2{font-size:14px;margin:0 0 12px;color:var(--acc);text-transform:uppercase;letter-spacing:.5px}
              .phase{font-size:11px;color:var(--dim);text-transform:uppercase;letter-spacing:.6px;
                     margin:14px 0 6px;padding-top:4px}
              .phase:first-child{margin-top:0}
              .steplist{display:flex;flex-direction:column;gap:2px}
              .sitem{display:flex;gap:10px;align-items:center;padding:8px 10px;border-radius:9px;cursor:pointer;
                     font-size:13.5px;color:var(--txt)}
              .sitem:hover{background:var(--panel2)}
              .sitem.on{background:rgba(79,140,255,.16);box-shadow:inset 3px 0 0 var(--acc)}
              .sitem .tag{flex:0 0 auto;font-size:11px;font-weight:700;color:var(--dim);min-width:28px}
              .sitem.on .tag{color:var(--acc)}
              .sitem .an{margin-left:auto;font-size:10px;color:var(--acc);border:1px solid var(--acc);
                     border-radius:5px;padding:1px 5px}
              .detail h3{margin:0 0 4px;font-size:18px}
              .detail .ph{color:var(--acc);font-size:11.5px;text-transform:uppercase;letter-spacing:.6px}
              .box{background:var(--panel2);border:1px solid var(--line);border-left:4px solid var(--acc);
                   border-radius:10px;padding:12px 16px;font-size:13.5px;line-height:1.65;color:var(--txt);margin:14px 0;
                   white-space:pre-wrap}
              .box.err{border-left-color:var(--bad);color:#ffb4ab}
              .row{display:flex;gap:10px;flex-wrap:wrap;align-items:center;margin:14px 0}
              .pf{display:flex;flex-direction:column;gap:3px;font-size:11px;color:var(--dim);
                  text-transform:uppercase;letter-spacing:.4px}
              .pf select,.pf input{background:var(--panel2);color:var(--txt);border:1px solid var(--line);
                  border-radius:8px;padding:7px 9px;font-size:13px;font-family:inherit}
              .phead{font-size:11px;color:var(--acc);text-transform:uppercase;letter-spacing:.6px;
                  margin:12px 0 4px;padding:0 4px}
              .phead:first-child{margin-top:0}
              button{background:var(--acc);color:#0d1220;border:none;border-radius:9px;padding:9px 18px;
                     font-weight:700;font-size:13px;cursor:pointer}
              button.ghost{background:var(--panel2);color:var(--txt);border:1px solid var(--line)}
              button:hover{filter:brightness(1.12)}
              button:disabled{opacity:.5;cursor:wait}
              a{color:var(--acc)}
              .hidden{display:none}
              #busy{color:var(--dim);font-size:14px;margin:14px 2px;display:flex;align-items:center;gap:10px}
              .spinner{width:16px;height:16px;border:2px solid var(--line);border-top-color:var(--acc);
                       border-radius:50%;animation:spin .8s linear infinite;display:inline-block}
              @keyframes spin{to{transform:rotate(360deg)}}
              .scroll{max-height:56vh;overflow:auto;border:1px solid var(--line);border-radius:10px}
              table{border-collapse:collapse;width:100%;font-size:13px}
              th,td{padding:7px 10px;text-align:left;white-space:nowrap;border-bottom:1px solid var(--line)}
              th{position:sticky;top:0;background:var(--panel2);color:var(--dim);font-weight:600;font-size:11.5px;
                 text-transform:uppercase;letter-spacing:.3px}
              tr:hover td{background:var(--panel2)}
              td.num,th.num{text-align:right;font-variant-numeric:tabular-nums}
              .mono{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:12px}
              .badge{display:inline-block;border-radius:6px;padding:2px 8px;font-size:12px;font-weight:700}
              .b-ok{background:rgba(46,204,113,.15);color:var(--ok)}
              .b-warn{background:rgba(241,196,15,.15);color:var(--warn)}
              .b-bad{background:rgba(231,76,60,.15);color:var(--bad)}
              .b-dim{background:rgba(143,160,196,.15);color:var(--dim)}
              .rec{border-radius:12px;padding:14px 16px;margin:14px 0;border:1px solid var(--acc);
                   background:linear-gradient(180deg,rgba(79,140,255,.12),transparent 140%)}
              .rec .big{font-size:17px;font-weight:800}
              .rec .tie{color:var(--warn);font-size:13px;margin-top:6px}
              .rec .next{color:var(--dim);font-size:13px;margin-top:6px}
              details{border:1px solid var(--line);border-radius:10px;margin-top:8px;background:var(--panel2)}
              summary{cursor:pointer;padding:10px 14px;font-size:13px;font-weight:600}
              details .in{padding:4px 14px 12px;font-size:13px;line-height:1.6;color:var(--dim)}
              .scores td.vrd{color:var(--dim);white-space:normal;line-height:1.45;max-width:360px}
              .chartwrap{background:var(--panel2);border:1px solid var(--line);border-radius:10px;
                     padding:10px 12px 6px;margin:10px 0 16px}
              .chartTitle{font-size:13px;font-weight:700;margin-bottom:6px;color:var(--txt)}
              .legend{display:flex;flex-wrap:wrap;gap:6px 16px;font-size:12px;margin-top:8px;color:var(--txt)}
              .lgi{display:flex;align-items:center;gap:6px;white-space:nowrap}
              .sw{width:12px;height:12px;border-radius:3px;display:inline-block}
              .lgm{color:var(--dim)}
              tr.unsel td{opacity:.42}
              tr.unsel:hover td{opacity:.75}
              .foot{margin-top:26px;color:var(--dim);font-size:12px;text-align:center}
            </style>
            </head>
            <body>
            <header>
              <h1>FPV Log Analyzer</h1>
              <span class="sub">Οδηγός βήμα-βήμα · blackbox .bbl/.bfl</span>
              <span class="status" id="status">Δεν έχει φορτωθεί log</span>
            </header>
            <div class="wrap">
              <div class="tabs" id="tabs"></div>
              <div class="card hidden" id="profile">
                <h2>Στοιχεία build (για την αναφορά AI)</h2>
                <div class="row" style="margin-top:0">
                  <label class="pf">Drone
                    <select id="pClass" onchange="applyProfile()">
                      <option value="whoop">Tiny whoop (&lt;3")</option>
                      <option value="toothpick">Toothpick / cinewhoop (3-4")</option>
                      <option value="5inch" selected>5"</option>
                      <option value="7inch">7"+ long range</option>
                      <option value="cine">Cine-lifter</option>
                    </select>
                  </label>
                  <label class="pf">Στυλ πτήσης
                    <select id="pStyle" onchange="applyProfile()">
                      <option value="freestyle" selected>Freestyle</option>
                      <option value="racing">Αγωνιστικό</option>
                      <option value="cinematic">Cinematic</option>
                      <option value="longrange">Long range</option>
                      <option value="cruise">Cruising</option>
                    </select>
                  </label>
                  <label class="pf">Μπαταρία
                    <select id="pCells" onchange="applyProfile()">
                      <option value="1">1S</option><option value="2">2S</option><option value="3">3S</option>
                      <option value="4" selected>4S</option><option value="5">5S</option><option value="6">6S</option>
                    </select>
                  </label>
                  <label class="pf">Έλικα
                    <input id="pProp" placeholder="π.χ. 5x4.3x3" onchange="applyProfile()">
                  </label>
                  <label class="pf">Betaflight
                    <input id="pVer" placeholder="π.χ. 4.4" onchange="applyProfile()" style="width:70px">
                  </label>
                  <label class="pf">Στόχος (focus)
                    <select id="pFocus" onchange="applyProfile()">
                      <option value="general" selected>General / ισορροπία</option>
                      <option value="latency">Reduce latency (snappy)</option>
                      <option value="noise">Reduce noise (δροσερά)</option>
                      <option value="performance">Max performance</option>
                    </select>
                  </label>
                  <label class="pf">KV κινητήρα
                    <input id="pKv" placeholder="π.χ. 2400" onchange="applyProfile()" style="width:80px">
                  </label>
                  <label class="pf">Φύλλα έλικας
                    <select id="pBlades" onchange="applyProfile()">
                      <option value="">—</option>
                      <option value="2">2</option><option value="3">3</option>
                      <option value="4">4</option><option value="5">5</option>
                    </select>
                  </label>
                  <label class="pf">ESC firmware
                    <select id="pEsc" onchange="applyProfile()">
                      <option value="">—</option>
                      <option value="blheli_s">BLHeli_S (stock)</option>
                      <option value="blheli_32">BLHeli_32</option>
                      <option value="bluejay">Bluejay</option>
                      <option value="am32">AM32</option>
                    </select>
                  </label>
                  <label class="pf">Στήριξη FC
                    <select id="pMount" onchange="applyProfile()">
                      <option value="">—</option>
                      <option value="soft">Μαλακή (soft mount)</option>
                      <option value="hard">Σκληρή (hard mount)</option>
                    </select>
                  </label>
                  <span id="pAdapt" style="color:var(--dim);font-size:12.5px"></span>
                </div>
              </div>
              <div class="cols">
                <div class="card">
                  <h2>Βήματα</h2>
                  <div class="steplist" id="steplist"></div>
                </div>
                <div class="card detail" id="detail"></div>
              </div>

              <div id="browser" class="card hidden">
                <h2>Φόρτωση log</h2>
                <div class="row">
                  <button class="ghost" onclick="browseUp()">↑ Πάνω</button>
                  <span class="mono" id="curPath" style="color:var(--dim);word-break:break-all"></span>
                </div>
                <div class="row" style="margin-top:0">
                  <button onclick="loadBrowsed()">Φόρτωση επιλεγμένων</button>
                  <button class="ghost" onclick="browseSel.clear();browse(browsePath);">Καθαρισμός επιλογής</button>
                  <span id="browseCount" style="color:var(--dim);font-size:12.5px"></span>
                </div>
                <div class="scroll" style="max-height:38vh">
                  <table><tbody id="btab"></tbody></table>
                </div>
                <div style="color:var(--dim);font-size:12px;margin-top:8px">
                  Κλικ σε φάκελο = άνοιγμα · τσέκαρε πολλά .bbl/.bfl και πάτησε «Φόρτωση επιλεγμένων»
                  (προστίθενται στη λίστα, δεν αντικαθίστανται).
                </div>
              </div>

              <div id="busy" class="hidden"></div>
              <div id="result"></div>

              <div class="card">
                <h2>Φορτωμένες πτήσεις</h2>
                <div class="row" style="margin-top:0">
                  <button class="ghost" onclick="selectAllFlights(true)">Επιλογή όλων</button>
                  <button class="ghost" onclick="selectAllFlights(false)">Καθαρισμός</button>
                  <button class="ghost" onclick="invertSelection()">Αντιστροφή</button>
                  <span id="selCount" style="color:var(--dim);font-size:12.5px"></span>
                  <button class="ghost" style="margin-left:auto" onclick="clearFlights()">Άδειασμα λίστας</button>
                </div>
                <div style="color:var(--dim);font-size:12px;margin:-6px 0 10px">
                  Τσέκαρε τις πτήσεις που θέλεις· η ανάλυση θα βγάλει την καλύτερη μόνο από τις επιλεγμένες.
                  Διπλό κλικ σε γραμμή = πληροφορίες.
                </div>
                <div class="scroll" style="max-height:32vh">
                  <table><thead><tr>
                    <th style="width:34px"></th><th>#</th><th>Αρχείο</th><th>Πτήση</th><th>Board · Firmware</th>
                    <th class="num">Διάρκεια</th><th class="num">Frames</th><th class="num">Corrupt</th>
                  </tr></thead><tbody id="ftab"></tbody></table>
                </div>
              </div>
              <div class="foot">Ενοποιημένος οδηγός βασισμένος σε fpv-drone.info · Oscar Liang · Betaflight PID Tuning Guide — τα δεδομένα μένουν τοπικά.</div>
            </div>

            <script>
            "use strict";
            const flights = [];
            const selected = new Set();
            let guides = [], active = 0, sel = [0,0], busyTimer = null, busyStart = 0, browsePath = "", browseSel = new Set();
            let profile = { droneClass:"5inch", style:"freestyle", cells:"4", prop:"", bfVersion:"",
                            focus:"general", motorKv:"", propBlades:"", escFirmware:"", fcMounting:"" };

            function profileQuery(){
              return "?droneClass="+encodeURIComponent(profile.droneClass)+
                     "&style="+encodeURIComponent(profile.style)+
                     "&cells="+encodeURIComponent(profile.cells)+
                     "&prop="+encodeURIComponent(profile.prop)+
                     "&bfVersion="+encodeURIComponent(profile.bfVersion)+
                     "&focus="+encodeURIComponent(profile.focus)+
                     "&motorKv="+encodeURIComponent(profile.motorKv)+
                     "&propBlades="+encodeURIComponent(profile.propBlades)+
                     "&escFirmware="+encodeURIComponent(profile.escFirmware)+
                     "&fcMounting="+encodeURIComponent(profile.fcMounting);
            }

            function loadProfile(){
              try{
                const saved = JSON.parse(localStorage.getItem("fpvProfile")||"null");
                if (saved) profile = Object.assign(profile, saved);
              }catch(e){}
              const map = { pClass:"droneClass", pStyle:"style", pCells:"cells", pProp:"prop", pVer:"bfVersion",
                            pFocus:"focus", pKv:"motorKv", pBlades:"propBlades", pEsc:"escFirmware", pMount:"fcMounting" };
              Object.keys(map).forEach(id=>{ const el=$("#"+id); if(el && profile[map[id]]!==undefined && profile[map[id]]!=="") el.value = profile[map[id]]; });
              updateAdaptLabel();
            }

            function updateAdaptLabel(){
              const el = $("#pAdapt"); if(!el) return;
              el.textContent = "→ τα στοιχεία χρησιμοποιούνται μόνο στην αναφορά AI";
            }

            async function applyProfile(){
              profile = {
                droneClass: $("#pClass").value, style: $("#pStyle").value, cells: $("#pCells").value,
                prop: $("#pProp").value.trim(), bfVersion: $("#pVer").value.trim(),
                focus: $("#pFocus").value, motorKv: $("#pKv").value.trim(),
                propBlades: $("#pBlades").value, escFirmware: $("#pEsc").value,
                fcMounting: $("#pMount").value
              };
              try{ localStorage.setItem("fpvProfile", JSON.stringify(profile)); }catch(e){}
              updateAdaptLabel();
            }

            async function reloadGuides(){
              try{
                guides = await api("/api/guides");
                if (active >= guides.length) active = 0;
                if (sel[1] >= guides[active].steps.length) sel[1] = 0;
                renderTabs(); renderNav(); select(active, sel[1]);
              }catch(e){ showError("Σφάλμα οδηγού: "+e.message); }
            }

            const $ = s => document.querySelector(s);
            const esc = s => String(s).replace(/[&<>"']/g, c => ({"&":"&amp;","<":"&lt;",">":"&gt;","\\"":"&quot;","'":"&#39;"}[c]));

            function showError(msg){
              const r = $("#result");
              r.innerHTML = '<div class="box err">'+esc(msg)+'</div>';
            }
            window.addEventListener("error", ev => showError("JS σφάλμα: "+ev.message));

            function setBusy(b, msg){
              const el = $("#busy");
              if (b){
                el.classList.remove("hidden");
                busyStart = Date.now();
                const base = msg || "Υπολογισμός";
                const upd = () => { el.innerHTML = '<span class="spinner"></span> '+base+' '+((Date.now()-busyStart)/1000).toFixed(0)+'s'; };
                upd(); clearInterval(busyTimer); busyTimer = setInterval(upd, 500);
                document.querySelectorAll("button").forEach(bt=>bt.disabled=true);
              } else {
                clearInterval(busyTimer); busyTimer = null;
                el.classList.add("hidden");
                document.querySelectorAll("button").forEach(bt=>bt.disabled=false);
              }
            }

            async function api(url, opts){
              const ctrl = new AbortController();
              const to = setTimeout(()=>ctrl.abort(), 600000);
              try{
                const r = await fetch(url, Object.assign({signal:ctrl.signal}, opts||{}));
                const text = await r.text();
                let data;
                try { data = JSON.parse(text); } catch(e){ throw new Error("μη έγκυρη απάντηση: "+text.slice(0,120)); }
                if (data && data.error) throw new Error(data.error);
                if (!r.ok) throw new Error("HTTP "+r.status);
                return data;
              } finally { clearTimeout(to); }
            }

            async function boot(){
              try{
                loadProfile();
                guides = await api("/api/guides");
                await refreshFlights();
                renderTabs(); renderNav(); select(0,0);
              }catch(e){ showError("Σφάλμα εκκίνησης: "+e.message); }
            }

            async function refreshFlights(){
              const f = await api("/api/flights");
              flights.length = 0; flights.push(...f);
              const keys = new Set(f.map(x=>x.key));
              for (const k of [...selected]) if (!keys.has(k)) selected.delete(k);
              if (selected.size===0) f.forEach(x=>selected.add(x.key));
              renderStatus(); renderTable();
            }

            function updateSelCount(){
              const el = $("#selCount");
              if (el) el.textContent = selected.size+" / "+flights.length+" επιλεγμένες";
            }

            function selectAllFlights(on){
              if (on) flights.forEach(f=>selected.add(f.key));
              else selected.clear();
              renderTable();
            }

            function invertSelection(){
              flights.forEach(f=>{ if(selected.has(f.key)) selected.delete(f.key); else selected.add(f.key); });
              renderTable();
            }

            async function clearFlights(){
              try{
                await api("/api/clear");
                selected.clear();
                await refreshFlights();
              }catch(e){ showError("Σφάλμα: "+e.message); }
            }

            function renderStatus(){
              const nFiles = new Set(flights.map(f=>f.source)).size;
              const s = $("#status");
              if (flights.length===0){
                s.innerHTML = "Δεν έχει φορτωθεί log";
              } else {
                const names = [...new Set(flights.map(f=>f.source))];
                const label = names.length===1 ? names[0] : names.length+" αρχεία";
                s.innerHTML = '<b>'+esc(label)+'</b> · '+flights.length+' πτήσεις';
              }
            }

            function renderTabs(){
              const el = $("#tabs"); el.innerHTML = "";
              guides.forEach((g,i)=>{
                const d = document.createElement("div");
                d.className = "tab"+(i===active?" on":"");
                d.textContent = g.title;
                d.onclick = ()=>{ active=i; sel=[i,0]; renderTabs(); renderNav(); select(i,0); };
                el.appendChild(d);
              });
              const ai = document.createElement("div");
              ai.className = "tab"+(active>=guides.length?" on":"");
              ai.textContent = "AI Tuning";
              ai.onclick = ()=>{ active=guides.length; renderTabs(); renderNav(); select(guides.length,0); };
              el.appendChild(ai);
            }

            function renderNav(){
              const el = $("#steplist"); el.innerHTML = "";
              if (active>=guides.length){
                const row = document.createElement("div");
                row.className = "sitem on";
                row.innerHTML = '<span class="tag">AI</span><span>Αναφορά για AI</span>';
                el.appendChild(row);
                return;
              }
              let lastPhase = null;
              guides[active].steps.forEach((s,i)=>{
                if (s.phase !== lastPhase){
                  const ph = document.createElement("div");
                  ph.className = "phead"; ph.textContent = s.phase;
                  el.appendChild(ph); lastPhase = s.phase;
                }
                const row = document.createElement("div");
                row.className = "sitem"+(sel[1]===i?" on":"");
                row.innerHTML = '<span class="tag">'+esc(s.id)+'</span><span>'+esc(s.title)+'</span>'+
                  (s.type==="analyze" ? '<span class="an">ανάλυση</span>' : '');
                row.onclick = ()=>{ sel=[active,i]; renderNav(); select(active,i); };
                el.appendChild(row);
              });
            }

            function step(){ return active<guides.length ? guides[active].steps[sel[1]] : null; }

            let aiMarkdown = "";

            function renderAi(){
              const d = $("#detail"); d.innerHTML = "";
              d.innerHTML = '<div class="ph">AI</div><h3>AI Tuning · Αναφορά για chat</h3>';
              const box = document.createElement("div"); box.className="box";
              box.textContent = "Δημιουργεί ένα κείμενο έτοιμο για paste σε Claude / ChatGPT / Gemini. "
                + "Περιλαμβάνει:\\n"
                + "  · το system prompt (ρόλος, κανόνες, reference ranges)\\n"
                + "  · τα build details που δήλωσες\\n"
                + "  · για κάθε επιλεγμένη πτήση: τις τρέχουσες ρυθμίσεις από το log (PID, sliders, φίλτρα, "
                + "TPA, simplified mode) και τα αποτελέσματα όλων των αναλύσεων.\\n\\n"
                + "Διάλεξε πτήσεις από τη λίστα «Φορτωμένες πτήσεις» και πάτα «Δημιουργία». Μετά «Αντιγραφή» "
                + "και κάνε paste στο AI chat.";
              d.appendChild(box);
              const row = document.createElement("div"); row.className="row";
              const gen = document.createElement("button"); gen.textContent="Δημιουργία αναφοράς";
              gen.onclick = ()=>genAiReport(gen);
              const cp = document.createElement("button"); cp.className="ghost"; cp.textContent="Αντιγραφή";
              cp.disabled = !aiMarkdown; cp.onclick = ()=>copyAi(cp);
              const dl = document.createElement("button"); dl.className="ghost"; dl.textContent="Λήψη .md";
              dl.disabled = !aiMarkdown; dl.onclick = downloadAi;
              row.appendChild(gen); row.appendChild(cp); row.appendChild(dl);
              d.appendChild(row);
              const pre = document.createElement("div"); pre.className="box"; pre.id="aiPreview";
              pre.style.maxHeight="none";
              pre.textContent = aiMarkdown || "(δεν έχει δημιουργηθεί ακόμη αναφορά)";
              d.appendChild(pre);
            }

            async function genAiReport(btn){
              if (flights.length===0){ showError("Δεν έχει φορτωθεί log."); return; }
              if (selected.size===0){ showError("Διάλεξε τουλάχιστον μία πτήση."); return; }
              const old = btn.textContent; btn.textContent="Δημιουργία…"; btn.disabled=true;
              setBusy(true, "Δημιουργία αναφοράς AI");
              try{
                let url = "/api/ai-report"+profileQuery();
                selected.forEach(k=>url += "&flight="+encodeURIComponent(k));
                const data = await api(url);
                aiMarkdown = data.markdown || "";
                const pre = $("#aiPreview"); if (pre) pre.textContent = aiMarkdown;
                renderAiNavButtons();
                btn.textContent="Έτοιμη ✓";
                setTimeout(()=>{ btn.textContent=old; }, 1500);
              }catch(e){ showError("Σφάλμα αναφοράς: "+e.message); }
              finally{ setBusy(false); btn.disabled=false; }
            }

            function renderAiNavButtons(){
              const d = $("#detail");
              const btns = d.querySelectorAll("button");
              btns.forEach(b=>{
                if (b.textContent==="Αντιγραφή" || b.textContent==="Λήψη .md") b.disabled=false;
              });
            }

            async function copyAi(btn){
              if (!aiMarkdown) return;
              try{
                await navigator.clipboard.writeText(aiMarkdown);
                const old=btn.textContent; btn.textContent="Αντιγράφηκε ✓";
                setTimeout(()=>btn.textContent=old, 1600);
              }catch(e){
                const w=window.open("","_blank"); if(w) w.document.write('<pre>'+esc(aiMarkdown)+'</pre>');
              }
            }

            function downloadAi(){
              if (!aiMarkdown) return;
              const blob=new Blob([aiMarkdown],{type:"text/markdown"});
              const a=document.createElement("a");
              a.href=URL.createObjectURL(blob); a.download="fpv-ai-report.md";
              document.body.appendChild(a); a.click(); a.remove();
            }

            function select(gi, si){
              const prof = $("#profile");
              if (active>=guides.length){ if(prof) prof.classList.remove("hidden"); renderAi(); return; }
              if (prof) prof.classList.add("hidden");
              const s = step();
              const d = $("#detail"); d.innerHTML = "";
              d.innerHTML = '<div class="ph">'+esc(s.phase)+'</div><h3>'+esc(s.id)+' · '+esc(s.title)+'</h3>';
              const box = document.createElement("div"); box.className="box"; box.textContent = s.instructions;
              d.appendChild(box);

              const row = document.createElement("div"); row.className="row";
              const link = document.createElement("a");
              link.href = s.url; link.target="_blank"; link.textContent = "Άνοιγμα οδηγού ↗";
              link.style.cssText="font-size:12.5px;margin-left:auto";
              if (s.type==="analyze"){
                const b1 = document.createElement("button"); b1.className="ghost"; b1.textContent="Φόρτωση log…";
                b1.onclick = ()=>toggleBrowser(true);
                const b2 = document.createElement("button"); b2.textContent="Ανάλυση";
                b2.onclick = ()=>runAnalyze(s);
                row.appendChild(b1); row.appendChild(b2);
              } else {
                const nb = document.createElement("button"); nb.className="ghost"; nb.textContent="Επόμενο →";
                nb.onclick = ()=>{ const n=sel[1]+1; if(n<guides[active].steps.length){ sel=[active,n]; renderNav(); select(active,n);} };
                row.appendChild(nb);
              }
              row.appendChild(link);
              d.appendChild(row);
            }

            async function runAnalyze(s){
              if (flights.length===0){ showError("Δεν έχει φορτωθεί log. Πάτησε «Φόρτωση log…» πρώτα."); return; }
              if (selected.size===0){ showError("Διάλεξε τουλάχιστον μία πτήση: τσέκαρε γραμμές στη λίστα «Φορτωμένες πτήσεις»."); return; }
              $("#result").innerHTML = "";
              setBusy(true, "Υπολογισμός "+s.id);
              try{
                let url = "/api/analyze?analyzer="+encodeURIComponent(s.analyzer)+"&min=0";
                selected.forEach(k=>url += "&flight="+encodeURIComponent(k));
                const data = await api(url);
                renderResult(s, data);
              }catch(e){ showError("Σφάλμα: "+e.message); }
              finally{ setBusy(false); }
            }

            function renderResult(s, data){
              const res = $("#result"); res.innerHTML = "";
              if (!data.results || data.results.length===0){
                showError("Καμία πτήση για ανάλυση.");
                return;
              }
              const rec = document.createElement("div"); rec.className="rec";
              const first = data.results[0];
              const f = flights.find(x=>x.index===first.index);
              const bcls = first.score>=80?'b-ok':first.score>=60?'b-warn':'b-bad';
              rec.innerHTML = '<span class="big">Καλύτερη: '+esc(f?f.source:"?")+' (S#'+(f?f.session:first.index)+')</span> '+
                '<span class="badge '+bcls+'">score '+first.score.toFixed(0)+'</span>';
              if (data.tied){
                rec.innerHTML += '<div class="tie">Η 2η πτήση έχει σχεδόν ίδιο score ('+data.results[1].score.toFixed(0)+
                  ' έναντι '+first.score.toFixed(0)+') — πέτα ξανά και ξανασύγκρινε.</div>';
              }
              if (first.advice){
                const na = document.createElement("div"); na.className="next";
                na.style.whiteSpace="pre-wrap"; na.textContent = first.advice;
                rec.appendChild(na);
              } else if (!data.tied){
                rec.innerHTML += '<div class="next">Βάλε τη ρύθμιση της καλύτερης πτήσης και συνέχισε στο επόμενο βήμα.</div>';
              }
              res.appendChild(rec);

              const tab = document.createElement("div"); tab.className="scroll";
              const t = document.createElement("table"); t.className="scores";
              t.innerHTML = '<thead><tr><th>Θέση</th><th>Αρχείο</th><th>Πτήση</th><th class="num">Score</th><th>Αξιολόγηση</th></tr></thead>';
              const tb = document.createElement("tbody");
              data.results.forEach(r=>{
                const ff = flights.find(x=>x.index===r.index);
                const tr = document.createElement("tr");
                const b = r.score>=80?'b-ok':r.score>=60?'b-warn':'b-bad';
                tr.innerHTML = '<td>'+r.rank+'η</td><td class="mono">'+esc(ff?ff.source:r.index)+'</td>'+
                  '<td>S#'+(ff?ff.session:'?')+'</td><td class="num"><span class="badge '+b+'">'+r.score.toFixed(0)+'</span></td>'+
                  '<td class="vrd">'+esc(r.verdict)+'</td>';
                tb.appendChild(tr);
              });
              t.appendChild(tb); tab.appendChild(t); res.appendChild(tab);

              data.results.forEach(r=>{
                const ff = flights.find(x=>x.index===r.index);
                const d = document.createElement("details");
                const sum = document.createElement("summary");
                const b = r.score>=80?'b-ok':r.score>=60?'b-warn':'b-bad';
                sum.innerHTML = '<b>'+esc(ff?ff.source:'?')+'</b> (S#'+(ff?ff.session:'?')+') '+
                  '<span class="badge '+b+'">'+r.score.toFixed(0)+'</span> '+esc(r.verdict);
                d.appendChild(sum);
                if (r.settings && r.settings.length){
                  const sh = document.createElement("div"); sh.className="mono";
                  sh.style.cssText="font-size:12px;color:var(--dim);margin:6px 0 2px;white-space:pre-wrap";
                  sh.textContent = "Ρυθμίσεις πτήσης: " + r.settings.join(" · ");
                  d.appendChild(sh);
                }
                const ul = document.createElement("ul"); ul.className="in";
                r.findings.forEach(li=>{const x=document.createElement("li"); x.textContent=li; ul.appendChild(x);});
                if (r.findings.length===0){ const x=document.createElement("li"); x.textContent="(κανένα εύρημα)"; ul.appendChild(x); }
                d.appendChild(ul);
                if (r.advice){
                  const av = document.createElement("div"); av.className="next";
                  av.style.whiteSpace="pre-wrap"; av.textContent = r.advice;
                  d.appendChild(av);
                }
                res.appendChild(d);
              });

              if (data.charts && data.charts.length){
                const ch=document.createElement("h2");
                ch.textContent="Γραφήματα";
                ch.style.cssText="font-size:14px;color:var(--dim);margin:20px 0 8px";
                res.appendChild(ch);
                data.charts.forEach(c=>res.appendChild(drawChart(c)));
              }
              const repRow = document.createElement("div");
              repRow.className="row"; repRow.style.marginTop="18px";
              const rb = document.createElement("button");
              rb.className="ghost"; rb.textContent="Αντιγραφή αναφοράς (για AI)";
              rb.onclick = ()=>copyReport(s, data, rb);
              const rj = document.createElement("button");
              rj.className="ghost"; rj.textContent="Λήψη JSON";
              rj.onclick = ()=>downloadReport(s, data);
              repRow.appendChild(rb); repRow.appendChild(rj);
              res.appendChild(repRow);
              res.scrollIntoView({behavior:"smooth", block:"start"});
            }

            function buildReport(s, data){
              const L=[];
              L.push("# Ανάλυση πτήσης — FPV Tuning");
              L.push("");
              L.push("## Setup");
              L.push("- Drone: "+profile.droneClass+" · Στυλ: "+profile.style+" · Focus: "+(profile.focus||"general"));
              L.push("- Μπαταρία: "+profile.cells+"S · Έλικα: "+(profile.prop||"-")+
                     " · KV: "+(profile.motorKv||"-")+" · Φύλλα: "+(profile.propBlades||"-"));
              L.push("- ESC: "+(profile.escFirmware||"-")+" · FC mount: "+(profile.fcMounting||"-")+
                     " · Betaflight: "+(profile.bfVersion||"-"));
              L.push("");
              L.push("## Βήμα οδηγού");
              L.push("- "+s.id+" · "+s.title+" (analyzer: "+s.analyzer+")");
              L.push("");
              L.push("## Αποτελέσματα (κατά σειρά)");
              data.results.forEach(r=>{
                const ff = flights.find(x=>x.index===r.index);
                L.push("");
                L.push("### "+r.rank+"η — "+(ff?ff.source:'?')+" (S#"+(ff?ff.session:'?')+") · score "+r.score.toFixed(0));
                L.push("- Αξιολόγηση: "+r.verdict);
                if (r.settings && r.settings.length) L.push("- Ρυθμίσεις: "+r.settings.join(" · "));
                (r.findings||[]).forEach(fi=>L.push("- "+fi));
                if (r.advice) L.push("- Πρόταση: "+r.advice.replace(/\\n/g, " "));
              });
              if (data.charts && data.charts.length){
                L.push("");
                L.push("## Γραφήματα / μετρικές");
                data.charts.forEach(c=>{
                  (c.lines||[]).forEach(l=>{
                    const mets=(c.metricLabels||[]).map((lb,idx)=>lb+"="+(l.m&&l.m[idx]!=null?Number(l.m[idx]).toFixed(2):"-")).join(", ");
                    L.push("- "+c.title+" · "+l.name+(mets?" ("+mets+")":""));
                  });
                });
              }
              L.push("");
              L.push("## Οδηγία ανάλυσης");
              L.push(s.instructions);
              return L.join("\\n");
            }

            async function copyReport(s, data, btn){
              const txt = buildReport(s, data);
              try{
                await navigator.clipboard.writeText(txt);
                const old = btn.textContent; btn.textContent="Αντιγράφηκε ✓";
                setTimeout(()=>btn.textContent=old, 1800);
              }catch(e){
                const w = window.open("", "_blank"); if (w){ w.document.write('<pre>'+esc(txt)+'</pre>'); }
              }
            }

            function downloadReport(s, data){
              const payload = { profile, step:{ id:s.id, title:s.title, analyzer:s.analyzer },
                                generated: new Date().toISOString(), results:data.results, charts:data.charts };
              const blob = new Blob([JSON.stringify(payload,null,2)], {type:"application/json"});
              const a = document.createElement("a");
              a.href = URL.createObjectURL(blob);
              a.download = "fpv-report-"+s.id+".json";
              document.body.appendChild(a); a.click(); a.remove();
            }

            const PALETTE=["#4f8cff","#e74c3c","#2ecc71","#f1c40f","#a259ff","#00c2d1","#ff8c42","#ff6bcb","#7bed9f","#dfe6e9"];
            function drawChart(c){
              const wrap=document.createElement("div"); wrap.className="chartwrap";
              const t=document.createElement("div"); t.className="chartTitle";
              t.textContent=c.title+"  ("+c.xLabel+")";
              wrap.appendChild(t);
              const lines=c.lines||[];
              if(!lines.length) return wrap;
              let maxN=0; lines.forEach(l=>{if(l.y.length>maxN)maxN=l.y.length;});
              let ymin=Infinity,ymax=-Infinity;
              lines.forEach(l=>l.y.forEach(v=>{if(v<ymin)ymin=v;if(v>ymax)ymax=v;}));
              const hasRef=(c.refY!==null && c.refY!==undefined && !isNaN(c.refY));
              if(hasRef){ymin=Math.min(ymin,c.refY);ymax=Math.max(ymax,c.refY);}
              if(!isFinite(ymin)||!isFinite(ymax)){ymin=0;ymax=1;}
              const ys=(ymax-ymin)||1; ymin-=ys*0.06; ymax+=ys*0.06;
              const x0=c.x0, x1=c.x0+c.dx*(maxN-1);
              const W=580,H=240,mL=46,mR=12,mT=12,mB=28;
              const sc=(v,a,b)=> (b-a)?(v-a)/(b-a):0;
              const X=x=> mL+sc(x,x0,x1)*(W-mL-mR);
              const Y=y=> mT+(1-sc(y,ymin,ymax))*(H-mT-mB);
              let g="";
              for(let i=0;i<=4;i++){
                const val=ymin+(ymax-ymin)*i/4, yy=Y(val);
                g+='<line x1="'+mL+'" y1="'+yy+'" x2="'+(W-mR)+'" y2="'+yy+'" stroke="#26345a" stroke-width="1"/>';
                g+='<text x="'+(mL-6)+'" y="'+(yy+4)+'" text-anchor="end" font-size="10" fill="#8fa0c4">'+val.toFixed(2)+'</text>';
              }
              for(let i=0;i<=5;i++){
                const val=x0+(x1-x0)*i/5, xx=X(val);
                g+='<line x1="'+xx+'" y1="'+mT+'" x2="'+xx+'" y2="'+(H-mB)+'" stroke="#1c2740" stroke-width="1"/>';
                g+='<text x="'+xx+'" y="'+(H-mB+14)+'" text-anchor="middle" font-size="10" fill="#8fa0c4">'+val.toFixed(0)+'</text>';
              }
              if(hasRef){
                const yy=Y(c.refY);
                g+='<line x1="'+mL+'" y1="'+yy+'" x2="'+(W-mR)+'" y2="'+yy+'" stroke="#f1c40f" stroke-dasharray="4 4" stroke-width="1"/>';
              }
              let paths="";
              lines.forEach((l,i)=>{
                const col=PALETTE[i%PALETTE.length];
                let d="";
                for(let k=0;k<l.y.length;k++){
                  d+=(k?"L":"M")+X(x0+c.dx*k).toFixed(1)+" "+Y(l.y[k]).toFixed(1)+" ";
                }
                paths+='<path d="'+d+'" fill="none" stroke="'+col+'" stroke-width="1.6"/>';
              });
              wrap.innerHTML += '<svg viewBox="0 0 '+W+' '+H+'" width="100%" preserveAspectRatio="xMidYMid meet">'+g+paths+'</svg>';
              let leg='<div class="legend">';
              lines.forEach((l,i)=>{
                const col=PALETTE[i%PALETTE.length];
                const mets=(c.metricLabels||[]).map((lb,idx)=> lb+" "+(l.m&&l.m[idx]!=null?Number(l.m[idx]).toFixed(2):"-")).join(" · ");
                leg+='<span class="lgi"><span class="sw" style="background:'+col+'"></span>'+esc(l.name)+' <span class="lgm">'+esc(mets)+'</span></span>';
              });
              leg+='</div>';
              wrap.innerHTML += leg;
              return wrap;
            }

            function renderTable(){
              const tb = $("#ftab"); tb.innerHTML = "";
              flights.forEach(f=>{
                const tr = document.createElement("tr");
                const on = selected.has(f.key);
                if (!on) tr.className = "unsel";
                const td0 = document.createElement("td");
                const cb = document.createElement("input");
                cb.type = "checkbox"; cb.checked = on;
                cb.onchange = ()=>{
                  if (cb.checked) selected.add(f.key); else selected.delete(f.key);
                  tr.classList.toggle("unsel", !cb.checked);
                  updateSelCount();
                };
                td0.appendChild(cb);
                const cp = f.corruptPct;
                const cpBadge = cp===0 ? '<span class="badge b-ok">οκ</span>'
                              : '<span class="badge b-warn">'+cp.toFixed(0)+'%</span>';
                tr.innerHTML += '<td>'+f.index+'</td><td class="mono">'+esc(f.source)+'</td><td>S#'+f.session+'</td>'+
                  '<td class="mono">'+esc(f.board)+' · '+esc(f.firmware)+'</td>'+
                  '<td class="num">'+f.durationS.toFixed(1)+' s</td>'+
                  '<td class="num">'+f.frames.toLocaleString('el-GR')+'</td><td class="num">'+cpBadge+'</td>';
                tr.insertBefore(td0, tr.firstChild);
                tr.addEventListener("dblclick", ()=>showHeader(f.index));
                tb.appendChild(tr);
              });
              updateSelCount();
            }

            async function showHeader(idx){
              try{
                const h = await api("/api/header/"+idx);
                alert("Πτήση #"+h.index+"  "+h.source+" (S#"+h.session+")\\n"+
                      "Craft: "+h.craft+"\\nBoard: "+h.board+"\\nFirmware: "+h.firmware+" (data "+h.dataVersion+")\\n"+
                      "Frames I/P/S/G: "+h.framesI+"/"+h.framesP+"/"+h.framesS+"/"+h.framesG+"\\n"+
                      "corrupt: "+h.corruptFrames+" frames ("+h.corruptBytes+" bytes, "+h.resyncs+" resyncs)\\n"+
                      "End: "+(h.reachedLogEnd?"κανονικό":"κόπηκε"));
              }catch(e){ showError("Σφάλμα: "+e.message); }
            }

            function toggleBrowser(on){
              const b = $("#browser");
              const show = (on===undefined) ? b.classList.contains("hidden") : on;
              b.classList.toggle("hidden", !show);
              if (show) browse(browsePath || "").catch(e=>showError(e.message));
            }
            function browseUp(){
              if (!browsePath) return;
              const parts = browsePath.replace(/\\/+$/,"").split("/");
              parts.pop();
              browse(parts.join("/") || "/");
            }
            async function browse(path){
              const d = await api("/api/browse?path="+encodeURIComponent(path||""));
              browsePath = d.path;
              $("#curPath").textContent = d.path;
              const tb = $("#btab"); tb.innerHTML = "";
              if (d.parent){
                const tr = document.createElement("tr");
                tr.style.cursor="pointer";
                tr.innerHTML = '<td colspan="2" class="mono">[πάνω] '+esc(d.parent)+'</td>';
                tr.onclick = browseUp; tb.appendChild(tr);
              }
              d.dirs.forEach(dir=>{
                const tr = document.createElement("tr");
                tr.style.cursor="pointer";
                tr.innerHTML = '<td class="mono">φάκελος: '+esc(dir.name)+'</td><td class="num"></td>';
                tr.onclick = ()=>browse(dir.path); tb.appendChild(tr);
              });
              d.files.forEach(fl=>{
                const tr = document.createElement("tr");
                const td0 = document.createElement("td"); td0.style.width="28px";
                const cb = document.createElement("input");
                cb.type = "checkbox"; cb.checked = browseSel.has(fl.path);
                cb.onchange = ()=>{ if(cb.checked) browseSel.add(fl.path); else browseSel.delete(fl.path); updateBrowseCount(); };
                td0.appendChild(cb);
                const td1 = document.createElement("td");
                td1.className = "mono"; td1.style.cursor="pointer";
                td1.textContent = "log: "+fl.name;
                td1.onclick = ()=>{ cb.checked=!cb.checked; cb.onchange(); };
                const td2 = document.createElement("td"); td2.className="num"; td2.textContent = sizeFmt(fl.size);
                tr.appendChild(td0); tr.appendChild(td1); tr.appendChild(td2);
                tb.appendChild(tr);
              });
              updateBrowseCount();
              if (!d.parent && d.dirs.length===0 && d.files.length===0){
                const tr = document.createElement("tr");
                tr.innerHTML='<td colspan="3" style="color:var(--dim)">(άδειος φάκελος)</td>'; tb.appendChild(tr);
              }
            }
            function updateBrowseCount(){
              const el = $("#browseCount");
              if (el) el.textContent = browseSel.size ? browseSel.size+" επιλεγμένα" : "";
            }
            async function loadBrowsed(){
              if (browseSel.size===0){ showError("Διάλεξε πρώτα ένα ή περισσότερα .bbl/.bfl."); return; }
              await loadPaths([...browseSel]);
              browseSel.clear(); updateBrowseCount();
              await browse(browsePath);
            }
            function sizeFmt(b){
              if (b>=1048576) return (b/1048576).toFixed(1)+" MB";
              if (b>=1024) return (b/1024).toFixed(0)+" KB";
              return b+" B";
            }
            async function loadPath(path){ await loadPaths([path]); }
            async function loadPaths(paths){
              if (!paths.length) return;
              setBusy(true, "Φόρτωση log");
              try{
                let url = "/api/load-file";
                paths.forEach((p,i)=>url += (i?"&":"?")+"path="+encodeURIComponent(p));
                await api(url);
                await refreshFlights();
              }catch(e){ showError("Σφάλμα φόρτωσης: "+e.message); }
              finally{ setBusy(false); }
            }

            boot();
            </script>
            </body>
            </html>
            """;
}
