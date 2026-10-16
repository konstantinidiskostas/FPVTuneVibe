package com.example.fpv.analysis;

import com.example.fpv.blackbox.FlightData;
import com.example.fpv.blackbox.Header;

import java.util.ArrayList;
import java.util.List;

public final class TrackingAnalyzer implements FlightAnalyzer {
    private static final String[] AXIS_NAMES = {"Roll", "Pitch", "Yaw"};

    @Override
    public String id() {
        return "tracking";
    }

    @Override
    public String title() {
        return "Tracking: setpoint vs gyro (time-domain)";
    }

    @Override
    public String instructions() {
        return "Εδώ συγκρίνουμε το setpoint (που θέλει ο pilot) με το gyro (που πήγε πραγματικά) σε βήμα χρόνου. "
                + "Όσο πιο κοντά είναι οι γραμμές, τόσο καλύτερα ακολουθεί το drone την εντολή. "
                + "Για Feedforward, θέλεις ελάχιστη καθυστέρηση και μικρότερο tracking error. "
                + "Το γράφημα δείχνει το πιο ενεργό τμήμα της πτήσης (περισσότερα απότομα flicks).";
    }

    @Override
    public AnalysisResult analyze(FlightData f) {
        List<String> findings = new ArrayList<>();
        if (f.mainCount < 100) {
            return new AnalysisResult(0, "Πολύ λίγα frame", findings);
        }
        int axes = countNumbered(f, "setpoint");
        if (axes == 0 || !f.hasMainField("gyroADC[0]")) {
            return new AnalysisResult(0, "Δεν υπάρχουν πεδία setpoint/gyroADC",
                    List.of("ενεργοποίησε setpoint logging (debug_mode = GYRO_SCALED)"));
        }
        double fs = f.frameRateHz();
        if (fs < 200 || f.mainCount < 512) {
            return new AnalysisResult(0, "Το log είναι πολύ μικρό ή αργό",
                    List.of(String.format("rate %.0f Hz, %d frames", fs, f.mainCount)));
        }
        double scale = gyroScale(f);
        double[] scores = new double[axes];
        List<Chart> charts = new ArrayList<>();
        String label = f.sourceName + " S#" + (f.sessionIndex + 1);

        for (int a = 0; a < axes; a++) {
            String spName = "setpoint[" + a + "]";
            String gyName = "gyroADC[" + a + "]";
            if (!f.hasMainField(spName) || !f.hasMainField(gyName)) {
                scores[a] = 50;
                continue;
            }
            Window w = findActiveWindow(f, spName, (int) (fs * 0.6), (int) (fs * 1.2));
            if (w.len < 64) continue;
            double[] sp = slice(f, spName, w.start, w.len, scale, true);
            double[] gy = slice(f, gyName, w.start, w.len, scale, false);
            if (sp.length != gy.length) continue;
            double[] sps = downsample(sp, 400);
            double[] gys = downsample(gy, 400);
            String axisName = a < AXIS_NAMES.length ? AXIS_NAMES[a] : ("Axis " + a);
            String key = "trk" + a + "_" + f.sourceName + "#" + (f.sessionIndex + 1);
            double x0 = (f.timeAt(w.start) - f.timeAt(0)) / 1000.0;
            double dx = 1000.0 / fs * ((double) (w.len - 1) / (sps.length - 1) > 0
                    ? (double) (w.len - 1) / (sps.length - 1)
                    : 1.0);
            charts.add(new Chart(key, "tracking", axisName + ": setpoint vs gyro — " + label,
                    "ms", "deg/s", x0, (w.len > 1) ? 1000.0 / fs : 1.0, 0.0,
                    downsample(sp, 320), new double[]{}, new String[]{}, label + " setpoint"));
            charts.add(new Chart(key, "tracking", axisName + ": setpoint vs gyro — " + label,
                    "ms", "deg/s", x0, (w.len > 1) ? 1000.0 / fs : 1.0, 0.0,
                    downsample(gy, 320), new double[]{}, new String[]{}, label + " gyro"));
            // compute score
            double rmsErr = 0, overshoot = 0, totalAct = 0;
            for (int i = 0; i < w.len; i++) {
                double s = f.mainValue(w.start + i, spName);
                double g = f.mainValue(w.start + i, gyName) * scale;
                double err = s - g;
                rmsErr += err * err;
                if (Math.abs(s) > 90) {
                    totalAct++;
                    if ((s > 0 && g > s) || (s < 0 && g < s)) overshoot++;
                }
            }
            rmsErr = Math.sqrt(rmsErr / Math.max(1, w.len));
            double oversRatio = totalAct > 0 ? overshoot / totalAct : 0;
            double sc = 100;
            sc -= Stats.clamp((rmsErr - 5) / 40.0, 0, 1) * 60;
            sc -= oversRatio * 30;
            scores[a] = Stats.clamp(sc, 0, 100);
            findings.add(String.format("%s: RMS tracking error %.1f deg/s, overshoot %.0f%%", axisName, rmsErr, oversRatio * 100));
        }
        double total = 0;
        int c = 0;
        for (double s : scores) {
            if (s > 0) { total += s; c++; }
        }
        double score = c > 0 ? Stats.clamp(total / c, 0, 100) : 0;
        String verdict;
        if (score >= 80) verdict = "setpoint και gyro πολύ κοντά — καλό feedforward.";
        else if (score >= 60) verdict = "αποδεκτό tracking — μπορείς να βελτιώσεις FF.";
        else verdict = "μεγάλη καθυστέρηση ή error — αύξησε FF ή έλεγξε φίλτρα.";
        return new AnalysisResult(score, verdict, findings, charts);
    }

    private record Window(int start, int len) {
    }

    private static Window findActiveWindow(FlightData f, String spName, int minLen, int maxLen) {
        int bestStart = 0;
        double bestAct = -1;
        int step = Math.max(1, minLen / 2);
        int limit = Math.max(0, f.mainCount - minLen);
        for (int start = 0; start <= limit; start += step) {
            double act = 0;
            int end = Math.min(f.mainCount, start + maxLen);
            for (int i = start; i < end; i++) {
                act += Math.abs(f.mainValue(i, spName));
            }
            act /= (end - start);
            if (act > bestAct) {
                bestAct = act;
                bestStart = start;
            }
        }
        int len = Math.min(maxLen, f.mainCount - bestStart);
        return new Window(bestStart, len);
    }

    private static double[] slice(FlightData f, String name, int start, int len, double scale, boolean isSetpoint) {
        double[] out = new double[len];
        for (int i = 0; i < len; i++) {
            double v = f.mainValue(start + i, name);
            if (!isSetpoint) v = v * scale;
            out[i] = v;
        }
        return out;
    }

    private static double gyroScale(FlightData f) {
        Header h = f.header;
        double s = h.gyroScale();
        if (!(Float.NaN != s && s > 1e-6 && s <= 1.0)) {
            s = 2000.0 / 32768.0;
        }
        return s;
    }

    private static double[] downsample(double[] a, int maxPoints) {
        if (a.length <= maxPoints) return a;
        double[] out = new double[maxPoints];
        for (int i = 0; i < maxPoints; i++) {
            double pos = i * (a.length - 1.0) / (maxPoints - 1);
            int lo = (int) Math.floor(pos);
            int hi = Math.min(a.length - 1, lo + 1);
            double frac = pos - lo;
            out[i] = a[lo] * (1 - frac) + a[hi] * frac;
        }
        return out;
    }

    private static int countNumbered(FlightData f, String base) {
        int n = 0;
        while (f.hasMainField(base + "[" + n + "]")) n++;
        return n;
    }
}
