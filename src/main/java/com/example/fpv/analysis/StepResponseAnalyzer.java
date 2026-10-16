package com.example.fpv.analysis;

import com.example.fpv.blackbox.FlightData;
import com.example.fpv.blackbox.Header;

import java.util.ArrayList;
import java.util.List;

public final class StepResponseAnalyzer implements FlightAnalyzer {
    private static final String[] AXIS_NAMES = {"Roll", "Pitch", "Yaw"};

    @Override
    public String id() {
        return "pid";
    }

    @Override
    public String title() {
        return "Step response (gyro vs setpoint)";
    }

    @Override
    public String instructions() {
        return "Κάθε γραμμή είναι η step response της πτήσης, υπολογισμένη με system identification στο πεδίο "
                + "της συχνότητας (Wiener deconvolution setpoint→gyro ανά παράθυρο 1s, με robust mode averaging). "
                + "Είναι κανονικοποιημένη ώστε το ιδανικό = 1.0. Ιδανικά: φτάνει γρήγορα στο 1, χωρίς overshoot, "
                + "χωρίς ταλάντωση και με settle κοντά στο 1 (το gyro ακολουθεί ακριβώς το setpoint). Δες Peak "
                + "(όσο πιο κοντά στο 1), Latency (όσο πιο μικρό), xcorr (καθυστέρηση setpoint→gyro) και Settle "
                + "(όσο πιο κοντά στο 1). Λίγο D => overshoot (Peak>1). Πολύ D => αργή απόκριση (μεγάλο Latency) "
                + "χωρίς overshoot. Settle<1 => μικρό I (η γωνία πέφτει).";
    }

    @Override
    public AnalysisResult analyze(FlightData f) {
        List<String> findings = new ArrayList<>();
        int axes = countNumbered(f, "setpoint");
        if (axes == 0 || !f.hasMainField("gyroADC[0]")) {
            return new AnalysisResult(0, "Δεν υπάρχουν πεδία setpoint / gyroADC",
                    List.of("ενεργοποίησε setpoint logging (debug_mode = GYRO_SCALED)"));
        }
        double fs = f.frameRateHz();
        if (fs < 200 || f.mainCount < 512) {
            return new AnalysisResult(0, "Το log είναι πολύ μικρό ή αργό για step response",
                    List.of(String.format("rate %.0f Hz, %d frames", fs, f.mainCount)));
        }
        double scale = gyroScale(f);

        List<Chart> charts = new ArrayList<>();
        List<Double> scores = new ArrayList<>();
        List<Step> axisSteps = new ArrayList<>();
        List<String> axisNames = new ArrayList<>();
        int[] todo = axes >= 2 ? new int[]{0, 1} : new int[]{0};
        for (int a : todo) {
            double[] sp = new double[f.mainCount];
            double[] gy = new double[f.mainCount];
            for (int i = 0; i < f.mainCount; i++) {
                sp[i] = f.mainValue(i, "setpoint[" + a + "]");
                gy[i] = f.mainValue(i, "gyroADC[" + a + "]") * scale;
            }
            Step st = computeStep(sp, gy, fs);
            String axisName = a < AXIS_NAMES.length ? AXIS_NAMES[a] : ("Axis " + a);
            if (st == null) {
                findings.add(axisName + ": δεν υπάρχουν αρκετά δεδομένα — πέταξε μεγαλύτερο log με κίνηση stick.");
                continue;
            }
            st.xcorrMs = xcorrLatencyMs(sp, gy, fs, 200);
            double[] curve = downsample(st.mean, 320);
            double span = (st.len - 1) * (1000.0 / fs);
            double dx = curve.length > 1 ? span / (curve.length - 1) : 1;
            charts.add(new Chart("step" + a, "step", axisName, "ms", "response",
                    st.x0, dx, 1.0, curve,
                    new double[]{st.peak, st.latencyMs, st.settle, st.xcorrMs},
                    new String[]{"peak", "latency(ms)", "settle", "xcorr(ms)"}));
            findings.add(String.format(
                    "%s: peak %.2f | latency %.0f ms | settle %.2f | oscillation %.2f | xcorr %.0f ms | %d windows (%s)",
                    axisName, st.peak, st.latencyMs, st.settle, st.ripple, st.xcorrMs, st.used,
                    confidence(st.used)));
            findings.add("   " + axisName + " → " + diagnose(st));
            scores.add(st.score);
            axisSteps.add(st);
            axisNames.add(axisName);
        }
        if (charts.isEmpty()) {
            return new AnalysisResult(0, "Δεν βρέθηκαν αρκετά δεδομένα για step response", findings);
        }
        double total = 0;
        for (double s : scores) total += s;
        double score = total / scores.size();
        if (axisSteps.size() >= 2) {
            Step r = axisSteps.get(0), p = axisSteps.get(1);
            if (Math.abs(r.score - p.score) >= 15) {
                boolean rollBetter = r.score > p.score;
                findings.add("Η κλίση roll/pitch δεν είναι ίδια: κάνε ξεχωριστό tuning — καλύτερο "
                        + (rollBetter ? "roll (ρύθμισε πρώτα roll)" : "pitch (ρύθμισε πρώτα pitch)") + ".");
            }
        }
        StringBuilder adv = new StringBuilder();
        for (int i = 0; i < axisSteps.size(); i++) {
            if (i > 0) adv.append('\n');
            adv.append(axisNames.get(i)).append(": ").append(diagnose(axisSteps.get(i)));
        }
        adv.append("\nΑν έκανες sweep με διαφορετικό slider/PID σε κάθε πτήση, κράτα την τιμή της ")
                .append("καλύτερης πτήσης και ξαναπέτα για επιβεβαίωση.");
        return new AnalysisResult(score, verdict(score), findings, charts, adv.toString());
    }

    @Override
    public List<String> settings(FlightData f) {
        Header h = f.header;
        List<String> out = new ArrayList<>();
        addPid(out, h, "rollPID", "Roll P,I,D");
        addPid(out, h, "pitchPID", "Pitch P,I,D");
        addPid(out, h, "yawPID", "Yaw P,I,D");
        Integer dm = h.intHeader("d_max", null);
        if (dm != null) out.add("D-Max base: " + dm + ", gain " + h.intHeader("d_max_gain", 0));
        Integer ff = h.intHeader("ff_weight", null);
        if (ff != null) out.add("FF weight: " + ff);
        int mode = h.intHeader("simplified_pids_mode", 0);
        if (mode > 0) {
            out.add("Simplified PID ON — Master " + h.intHeader("simplified_master_multiplier", 0)
                    + ", Damping " + h.intHeader("simplified_d_gain", 0)
                    + ", Tracking " + h.intHeader("simplified_pi_gain", 0)
                    + ", I " + h.intHeader("simplified_i_gain", 0)
                    + ", FF " + h.intHeader("simplified_feedforward_gain", 0)
                    + ", DynDamp " + h.intHeader("simplified_d_max_gain", 0));
        }
        return out;
    }

    private static void addPid(List<String> out, Header h, String key, String label) {
        String v = h.raw.get(key);
        if (v != null && !v.isBlank()) out.add(label + ": " + v);
    }

    private static String confidence(int windows) {
        if (windows >= 100) return "υψηλή εμπιστοσύνη";
        if (windows >= 30) return "μέτρια εμπιστοσύνη";
        return "χαμηλή εμπιστοσύνη — πέτα μεγαλύτερο log";
    }

    private static String verdict(double score) {
        if (score >= 80) {
            return "Καθαρή step response — γρήγορη και χωρίς overshoot.";
        } else if (score >= 60) {
            return "Αποδεκτή απόκριση, αλλά με λίγο overshoot ή καθυστέρηση. Ρύθμισε D / FF.";
        } else {
            return "Έντονο overshoot/oscillation ή μεγάλη καθυστέρηση — δες το γράφημα και άλλαξε P/D.";
        }
    }

    /** Symptom → fix hints aligned with the tuning guides. */
    private static String diagnose(Step st) {
        List<String> tips = new ArrayList<>();
        if (st.peak > 1.20) {
            tips.add("έντονο overshoot → λίγο D ή μείωσε P/FF");
        } else if (st.peak > 1.08) {
            tips.add("ελαφρύ overshoot (Peak>1.08) → λίγο περισσότερο D");
        }
        if (st.ripple > 0.10) {
            tips.add("ταλάντωση (oscillation>0.10) → πολύ P ή I, ή θόρυβος — δες φίλτρα");
        }
        if (st.settle < 0.85) {
            tips.add("πέφτει η γωνία στο τέλος (settle<0.85) → ανέβασε λίγο I");
        } else if (st.settle > 1.15) {
            tips.add("υπερ-διόρθωση στο τέλος (settle>1.15) → πολύ I");
        }
        if (st.latencyMs > 40 || st.xcorrMs > 40) {
            tips.add("αργή απόκριση → πολύ D ή πολύ φιλτράρισμα");
        } else if (st.latencyMs < 8 && st.peak < 0.95) {
            tips.add("πολύ γρήγορο αλλά δεν φτάνει στο 1 → ανέβασε P/FF");
        }
        return tips.isEmpty() ? "ισορροπημένη — καθαρή ταλάντωση-free απόκριση." : String.join(" · ", tips) + ".";
    }

    private static double xcorrLatencyMs(double[] x, double[] y, double fs, int maxLag) {
        int n = x.length;
        if (n < 64) return Double.NaN;
        double mx = 0, my = 0;
        for (int i = 0; i < n; i++) {
            mx += x[i];
            my += y[i];
        }
        mx /= n;
        my /= n;
        double best = -Double.MAX_VALUE;
        int bestLag = 0;
        int lim = Math.min(maxLag, n / 4);
        for (int lag = 0; lag <= lim; lag++) {
            double num = 0, dx = 0, dy = 0;
            for (int i = 0; i + lag < n; i++) {
                double a = x[i] - mx;
                double b = y[i + lag] - my;
                num += a * b;
                dx += a * a;
                dy += b * b;
            }
            double den = Math.sqrt(dx * dy);
            double c = den > 0 ? num / den : 0;
            if (c > best) {
                best = c;
                bestLag = lag;
            }
        }
        return bestLag * 1000.0 / fs;
    }

    private static Step computeStep(double[] sp, double[] gy, double fs) {
        PidStepResponse r = PidStepResponse.compute(sp, gy, fs);
        if (r == null || r.low == null) return null;
        double[] mean = r.low;
        int n = mean.length;

        double peak = -1e18;
        int peakIdx = 0;
        for (int i = 0; i < n; i++) {
            if (mean[i] > peak) {
                peak = mean[i];
                peakIdx = i;
            }
        }
        double dxMs = r.dtMs;
        int latIdx = -1;
        for (int i = 0; i < n; i++) {
            if (mean[i] >= 0.90) {
                latIdx = i;
                break;
            }
        }
        double latencyMs = latIdx < 0 ? peakIdx * dxMs : latIdx * dxMs;

        int tailStart = Math.min(n - 1, (int) (0.20 * fs));
        double ts = 0, tsq = 0;
        int tn = 0;
        for (int i = tailStart; i < n; i++) {
            ts += mean[i];
            tsq += mean[i] * mean[i];
            tn++;
        }
        double tailMean = tn > 0 ? ts / tn : 0;
        double ripple = tn > 1 ? Math.sqrt(Math.max(0, tsq / tn - tailMean * tailMean)) : 0;

        double over = Math.max(0, peak - 1.0);
        double settleErr = Math.abs(tailMean - 1.0);
        double score = 100;
        score -= Stats.clamp(over / 0.5, 0, 1) * 35;
        score -= Stats.clamp(Math.max(0, latencyMs - 15) / 60.0, 0, 1) * 25;
        score -= Stats.clamp(settleErr / 0.30, 0, 1) * 25;
        score -= Stats.clamp(ripple / 0.25, 0, 1) * 15;

        Step st = new Step();
        st.mean = mean;
        st.len = n;
        st.x0 = 0;
        st.peak = peak;
        st.latencyMs = latencyMs;
        st.ripple = ripple;
        st.settle = tailMean;
        st.score = Stats.clamp(score, 0, 100);
        st.used = r.lowWindows;
        return st;
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

    private static double gyroScale(FlightData f) {
        Header h = f.header;
        double s = h.gyroScale();
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

    private static final class Step {
        double[] mean;
        int len;
        double x0;
        double peak;
        double latencyMs;
        double xcorrMs;
        double ripple;
        double settle;
        double score;
        int used;
    }
}
