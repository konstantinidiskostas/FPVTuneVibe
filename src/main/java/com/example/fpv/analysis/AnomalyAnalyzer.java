package com.example.fpv.analysis;

import com.example.fpv.blackbox.FlightData;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class AnomalyAnalyzer implements FlightAnalyzer {
    private static final double MOTOR_MIN = 48;
    private static final double MOTOR_MAX = 2047;

    @Override
    public String id() {
        return "anomaly";
    }

    @Override
    public String title() {
        return "Έλεγχος ανωμαλιών (anomalies)";
    }

    @Override
    public String instructions() {
        return "Αυτός ο έλεγχος ψάχνει γνωστά «κόκκινα σημάδια» σε όλη την πτήση:\n"
                + "  · Motor saturation (τερματισμένοι κινητήρες)\n"
                + "  · Battery voltage sag (πτώση τάσης υπό φορτίο)\n"
                + "  · Gyro noise spikes (αιχμές θορύβου)\n"
                + "  · Motor output imbalance (μοτέρ εκτός ισορροπίας)\n"
                + "  · Desync / motor stall (eRPM κόβεται με ψηλή εντολή)\n"
                + "  · I-Term windup (I που «φορτώνει» χωρίς εντολή)\n\n"
                + "Ό,τι βρεθεί εμφανίζεται πρώτο ως προς impact. Δες πρώτα το πιο σοβαρό.";
    }

    @Override
    public AnalysisResult analyze(FlightData f) {
        List<String> findings = new ArrayList<>();
        List<Chart> charts = new ArrayList<>();
        if (f.mainCount < 100) {
            return new AnalysisResult(0, "Πολύ λίγα frame", findings);
        }
        double fs = f.frameRateHz();
        if (fs < 100) {
            return new AnalysisResult(0, "Άγνωστο/πολύ αργό rate", findings);
        }
        double scale = gyroScale(f);
        double penalty = 0;
        List<String> issues = new ArrayList<>();

        int step = Math.max(1, f.mainCount / 500);
        double tdx = 1000.0 / fs * step;

        penalty += motorSaturation(f, findings, issues, charts, step, tdx);
        penalty += batterySag(f, findings, issues, charts, step, tdx);
        penalty += gyroNoise(f, scale, findings, issues, charts, step, tdx);
        penalty += motorImbalance(f, findings, issues);
        penalty += desync(f, findings, issues);
        penalty += iTermWindup(f, findings, issues);

        if (findings.isEmpty()) {
            findings.add("Δεν εντοπίστηκαν ανωμαλίες — η πτήση φαίνεται καθαρή.");
        }
        double score = Stats.clamp(100 - penalty, 0, 100);
        String verdict;
        if (issues.isEmpty()) {
            verdict = "Καθαρή πτήση: κανένα πρόβλημα υγείας hardware/tune.";
        } else {
            verdict = "Βρέθηκαν: " + String.join(" · ", issues);
        }
        return new AnalysisResult(score, verdict, findings, charts);
    }

    // ---- checks ----

    private double motorSaturation(FlightData f, List<String> findings, List<String> issues,
                                   List<Chart> charts, int step, double tdx) {
        int motors = countNumbered(f, "motor");
        if (motors == 0) return 0;
        int sat = 0;
        for (int i = 0; i < f.mainCount; i++) {
            for (int m = 0; m < motors; m++) {
                if (f.mainValue(i, "motor[" + m + "]") >= 2000) {
                    sat++;
                    break;
                }
            }
        }
        double pct = 100.0 * sat / f.mainCount;
        double sev = pct < 5 ? 0 : pct < 15 ? 0.4 : 0.8;
        findings.add(String.format("Motor saturation: %.1f%% του χρόνου (>=2000) — %s",
                pct, pct < 5 ? "καλό" : pct < 15 ? "προσοχή (χαμήλωσε authority/PIDs)" : "σοβαρό (τερματισμοί)"));
        if (sev > 0) issues.add("motor saturation " + Math.round(pct) + "%");

        for (int m = 0; m < motors; m++) {
            String name = "motor[" + m + "]";
            if (!f.hasMainField(name)) continue;
            double[] y = new double[(f.mainCount + step - 1) / step];
            for (int k = 0; k < y.length; k++) {
                int idx = Math.min(f.mainCount - 1, k * step);
                y[k] = 100.0 * (f.mainValue(idx, name) - MOTOR_MIN) / (MOTOR_MAX - MOTOR_MIN);
            }
            charts.add(new Chart("mot", "time", "Έξοδος κινητήρων (% του εύρους)", "ms", "%",
                    0, tdx, Double.NaN, y, new double[]{}, new String[]{}, "M" + (m + 1)));
        }
        return sev * 30;
    }

    private double batterySag(FlightData f, List<String> findings, List<String> issues,
                              List<Chart> charts, int step, double tdx) {
        if (!f.hasMainField("vbatLatest")) return 0;
        double maxV = 0, minV = Double.MAX_VALUE;
        for (int i = 0; i < f.mainCount; i++) {
            double v = f.mainValue(i, "vbatLatest") / 100.0;
            if (v > maxV) maxV = v;
            if (v > 1 && v < minV) minV = v;
        }
        if (maxV < 1) return 0;
        int cells = (int) Math.max(1, Math.round(maxV / 4.2));
        // rest voltage = average of samples in lowest-throttle region
        boolean thr = f.hasMainField("rcCommand[3]");
        double rest = maxV, sagPerCell = 0;
        if (thr) {
            double sumLow = 0, sumHigh = 0, minLoad = Double.MAX_VALUE;
            int nLow = 0, nHigh = 0;
            for (int i = 0; i < f.mainCount; i++) {
                double t = f.mainValue(i, "rcCommand[3]");
                double v = f.mainValue(i, "vbatLatest") / 100.0;
                if (v < 1) continue;
                if (t < 1150) { sumLow += v; nLow++; }
                else if (t > 1500) { sumHigh += v; nHigh++; if (v < minLoad) minLoad = v; }
            }
            if (nLow > 0) rest = sumLow / nLow;
            if (nHigh > 0) {
                sagPerCell = (rest - minLoad) / cells;
                minV = minLoad;
            }
        }
        double perCellMin = minV / cells;
        double sev = 0;
        if (sagPerCell > 1.0 || perCellMin < 3.2) sev = 0.8;
        else if (sagPerCell > 0.6 || perCellMin < 3.5) sev = 0.4;
        findings.add(String.format("Τάση: %dS, ανά cell min %.2fV · sag υπό φορτίο %.2fV/cell (rest %.2fV) — %s",
                cells, perCellMin, sagPerCell, rest / cells,
                sev == 0 ? "καλή" : sev < 0.5 ? "μέτρια πτώση" : "έντονη πτώση (έλεγξε μπαταρία/βύσματα)"));
        if (sev > 0) issues.add("battery sag");

        if (f.hasMainField("vbatLatest")) {
            double[] y = new double[(f.mainCount + step - 1) / step];
            for (int k = 0; k < y.length; k++) {
                int idx = Math.min(f.mainCount - 1, k * step);
                y[k] = f.mainValue(idx, "vbatLatest") / 100.0;
            }
            charts.add(new Chart("vbat", "time", "Τάση μπαταρίας (V)", "ms", "V",
                    0, tdx, 3.35 * cells, y, new double[]{}, new String[]{}, "Vbat"));
        }
        return sev * 20;
    }

    private double gyroNoise(FlightData f, double scale, List<String> findings, List<String> issues,
                             List<Chart> charts, int step, double tdx) {
        if (!f.hasMainField("gyroADC[0]")) return 0;
        int n = f.mainCount;
        // high-pass via difference from 8-sample moving average, rolling RMS in 0.05s windows
        int win = Math.max(16, (int) Math.round(f.frameRateHz() * 0.05));
        double[] rms = new double[(n + step - 1) / step];
        double globalSum = 0;
        int cnt = 0;
        double[][] buf = new double[3][8];
        double[] acc = new double[3];
        for (int i = 0; i < n; i++) {
            double e = 0;
            for (int a = 0; a < 3; a++) {
                String nm = "gyroADC[" + a + "]";
                double v = f.hasMainField(nm) ? f.mainValue(i, nm) * scale : 0;
                int p = i % 8;
                acc[a] += v - buf[a][p];
                buf[a][p] = v;
                double ma = acc[a] / 8.0;
                double hp = v - ma;
                e += hp * hp;
            }
            globalSum += e;
            cnt++;
            rms[i / step] = Math.sqrt(e / 3.0);
        }
        double globalRms = Math.sqrt(globalSum / (3.0 * Math.max(1, cnt)));
        double[] sorted = rms.clone();
        Arrays.sort(sorted);
        double median = sorted[sorted.length / 2];
        double peak = sorted[sorted.length - 1];
        double ratio = median > 1e-6 ? peak / median : 1;
        double sev = globalRms < 3 ? 0 : globalRms < 8 ? 0.4 : 0.8;
        boolean spike = peak > 30 && ratio > 6;
        if (spike && sev < 0.4) sev = 0.4;
        String nv = sev == 0 ? "ομαλό" : sev < 0.5
                ? (spike ? "κάποιες αιχμές θορύβου (peak " + Math.round(peak) + ")" : "ελαφρώς αυξημένος θόρυβος")
                : "υψηλός θόρυβος gyro (θερμοί κινητήρες / κακό φιλτράρισμα)";
        findings.add(String.format("Gyro noise (HP RMS): global %.1f deg/s, peak %.1f, peak/median %.1f — %s",
                globalRms, peak, ratio, nv));
        if (sev > 0) issues.add("gyro noise spikes");
        charts.add(new Chart("noise", "time", "Gyro noise (HP RMS)", "ms", "deg/s",
                0, tdx, Double.NaN, rms, new double[]{}, new String[]{}, "noise"));
        return sev * 20;
    }

    private double motorImbalance(FlightData f, List<String> findings, List<String> issues) {
        int motors = countNumbered(f, "motor");
        if (motors < 2) return 0;
        double[] sum = new double[motors];
        int cnt = 0;
        boolean thr = f.hasMainField("rcCommand[3]");
        for (int i = 0; i < f.mainCount; i++) {
            if (thr) {
                double t = f.mainValue(i, "rcCommand[3]");
                if (t < 1200 || t > 1700) continue;
            }
            for (int m = 0; m < motors; m++) sum[m] += f.mainValue(i, "motor[" + m + "]");
            cnt++;
        }
        if (cnt < 100) return 0;
        double mean = 0;
        for (int m = 0; m < motors; m++) { sum[m] /= cnt; mean += sum[m]; }
        mean /= motors;
        double dev = 0;
        int worst = 0;
        double worstD = 0;
        for (int m = 0; m < motors; m++) {
            double d = Math.abs(sum[m] - mean) / mean * 100;
            dev += d;
            if (d > worstD) { worstD = d; worst = m; }
        }
        dev /= motors;
        double sev = worstD > 30 ? 0.8 : worstD > 15 ? 0.4 : 0;
        findings.add(String.format("Motor balance (mid-throttle): μέση απόκλιση %.1f%%, χειρότερο M%d %.1f%% — %s",
                dev, worst + 1, worstD, sev == 0 ? "ισορροπημένα" : sev < 0.5 ? "μικρή απόκλιση" : "έντονη απόκλιση (balance/στραβή έλικα)"));
        if (sev > 0) issues.add("motor imbalance");
        return sev * 15;
    }

    private double desync(FlightData f, List<String> findings, List<String> issues) {
        int motors = countNumbered(f, "eRPM");
        if (motors == 0) return 0;
        boolean any = false;
        for (int m = 0; m < motors; m++) {
            if (f.hasMainField("eRPM[" + m + "]")) { any = true; break; }
        }
        if (!any) return 0;
        int badRuns = 0;
        int run = 0;
        for (int i = 0; i < f.mainCount; i++) {
            boolean bad = false;
            for (int m = 0; m < motors; m++) {
                String mn = "motor[" + m + "]";
                String en = "eRPM[" + m + "]";
                if (!f.hasMainField(mn) || !f.hasMainField(en)) continue;
                long erpm = f.mainValueUnsigned(i, en);
                int cmd = f.mainValue(i, mn);
                if (cmd > 1500 && erpm == 0) bad = true;
            }
            if (bad) {
                run++;
                if (run == 3) badRuns++;
            } else {
                run = 0;
            }
        }
        if (badRuns > 0) {
            findings.add(String.format("Desync / motor stall: %d επεισόδια (εντολή>1500 με eRPM=0) — ΕΛΕΓΞΕ!",
                    badRuns));
            issues.add("desync/motor stall");
            return 25;
        }
        findings.add("Desync: δεν εντοπίστηκε (eRPM ακολουθεί τις εντολές).");
        return 0;
    }

    private double iTermWindup(FlightData f, List<String> findings, List<String> issues) {
        int axes = countNumbered(f, "axisI");
        if (axes == 0) return 0;
        double maxLow = 0, sumActive = 0, sumLow = 0;
        int nActive = 0, nLow = 0;
        for (int i = 0; i < f.mainCount; i++) {
            double act = 0;
            for (int a = 0; a < axes; a++) {
                if (f.hasMainField("setpoint[" + a + "]")) {
                    act += Math.abs(f.mainValue(i, "setpoint[" + a + "]"));
                }
            }
            double ai = 0;
            for (int a = 0; a < axes; a++) ai += Math.abs(f.mainValue(i, "axisI[" + a + "]"));
            if (act < 25) {
                sumLow += ai;
                nLow++;
                if (ai > maxLow) maxLow = ai;
            } else {
                sumActive += ai;
                nActive++;
            }
        }
        double meanLow = nLow > 0 ? sumLow / nLow : 0;
        double meanActive = nActive > 0 ? sumActive / nActive : 0;
        double sev = (meanLow > 1.5 * meanActive && meanLow > 300) ? 0.6 : 0;
        findings.add(String.format("I-Term: μέσο |I| σε αδράνεια %.0f vs ενεργό %.0f (max idle %.0f) — %s",
                meanLow, meanActive, maxLow, sev == 0 ? "φυσιολογικό" : "πιθανό windup"));
        if (sev > 0) issues.add("I-Term windup");
        return sev * 10;
    }

    // ---- helpers ----

    private static double gyroScale(FlightData f) {
        double s = f.header.gyroScale();
        if (!(Float.NaN != s && s > 1e-6 && s <= 1.0)) {
            s = 2000.0 / 32768.0;
        }
        return s;
    }

    private static int countNumbered(FlightData f, String base) {
        int n = 0;
        while (f.hasMainField(base + "[" + n + "]")) n++;
        return n;
    }
}
