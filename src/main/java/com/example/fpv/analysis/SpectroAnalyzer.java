package com.example.fpv.analysis;

import com.example.fpv.blackbox.FlightData;

import java.util.ArrayList;
import java.util.List;

public final class SpectroAnalyzer implements FlightAnalyzer {
    private static final int BINS = 25;
    private static final double BIN_W = 100.0 / BINS;

    @Override
    public String id() {
        return "spectro";
    }

    @Override
    public String title() {
        return "Θόρυβος ανά throttle (noise-by-throttle)";
    }

    @Override
    public String instructions() {
        return "Εδώ βλέπεις πώς αλλάζει ο θόρυβος και η έξοδος των κινητήρων με το throttle. "
                + "Ιδανικά ο θόρυβος μένει σχετικά επίπεδος. Αν εκτοξεύεται σε συγκεκριμένο εύρος throttle, "
                + "εκεί χρειάζεται καλύτερο φιλτράρισμα (dynamic notch / RPM filter) ή πιο ήρεμο PID. "
                + "Οι διαγώνιες αιχμές που ανεβαίνουν με το throttle είναι harmonics των κινητήρων.";
    }

    @Override
    public AnalysisResult analyze(FlightData f) {
        List<String> findings = new ArrayList<>();
        List<Chart> charts = new ArrayList<>();
        if (f.mainCount < 200 || !f.hasMainField("rcCommand[3]") || !f.hasMainField("gyroADC[0]")) {
            return new AnalysisResult(0, "Χρειάζονται πεδία throttle και gyroADC", findings);
        }
        double fs = f.frameRateHz();
        if (fs < 200) {
            return new AnalysisResult(0, "Πολύ αργό rate για φάσμα", findings);
        }
        double scale = gyroScale(f);
        double[] noiseSum = new double[BINS];
        double[] motorSum = new double[BINS];
        int[] noiseN = new int[BINS];
        int[] motorN = new int[BINS];
        int motors = countNumbered(f, "motor");
        double[] crossSum = new double[BINS];
        int[] crossN = new int[BINS];

        double[][] hist = new double[3][8];
        double[] ma = new double[3];
        double[] prevHp = new double[]{Double.NaN, Double.NaN, Double.NaN};
        for (int i = 0; i < f.mainCount; i++) {
            double thr = (f.mainValue(i, "rcCommand[3]") - 1000.0) / 10.0;
            if (thr < 0) thr = 0;
            if (thr > 100) thr = 100;
            int bin = Math.min(BINS - 1, (int) (thr / BIN_W));
            double e = 0;
            int crossings = 0;
            for (int a = 0; a < 3; a++) {
                String nm = "gyroADC[" + a + "]";
                double v = f.hasMainField(nm) ? f.mainValue(i, nm) * scale : 0;
                int p = i % 8;
                ma[a] += v - hist[a][p];
                hist[a][p] = v;
                double avg = ma[a] / 8.0;
                double hp = v - avg;
                e += hp * hp;
                if (!Double.isNaN(prevHp[a]) && (hp >= 0) != (prevHp[a] >= 0)) crossings++;
                prevHp[a] = hp;
            }
            noiseSum[bin] += Math.sqrt(e / 3.0);
            noiseN[bin]++;
            crossSum[bin] += crossings / 3.0;
            crossN[bin]++;
            if (motors > 0) {
                double ms = 0;
                for (int m = 0; m < motors; m++) ms += f.mainValue(i, "motor[" + m + "]");
                motorSum[bin] += ms / motors;
                motorN[bin]++;
            }
        }
        double[] noise = new double[BINS];
        double[] motorPct = new double[BINS];
        double[] freq = new double[BINS];
        for (int b = 0; b < BINS; b++) {
            noise[b] = noiseN[b] > 0 ? noiseSum[b] / noiseN[b] : Double.NaN;
            motorPct[b] = motorN[b] > 0 ? 100.0 * (motorSum[b] / motorN[b] - 48) / (2047 - 48) : Double.NaN;
            double sec = crossN[b] / fs;
            freq[b] = sec > 1e-6 ? crossSum[b] / (2.0 * sec) : Double.NaN;
        }
        interpolate(noise);
        interpolate(motorPct);
        interpolate(freq);

        // low vs high throttle noise ratio
        double lo = 0; int nlo = 0; double hi = 0; int nhi = 0;
        for (int b = 0; b < BINS; b++) {
            if (b < (int) (BINS * 0.4)) { lo += noise[b]; nlo++; }
            else if (b > (int) (BINS * 0.6)) { hi += noise[b]; nhi++; }
        }
        double loM = nlo > 0 ? lo / nlo : 0, hiM = nhi > 0 ? hi / nhi : 0;
        double ratio = loM > 1e-6 ? hiM / loM : 1;
        double sev = ratio > 3 ? 0.8 : ratio > 1.8 ? 0.4 : 0;
        findings.add(String.format("Θόρυβος: χαμηλό throttle %.1f, υψηλό %.1f deg/s (λόγος %.2f) — %s",
                loM, hiM, ratio, sev == 0 ? "ομαλός" : sev < 0.5
                        ? "αυξάνεται με το throttle (έλεγξε RPM/notch)" : "πολύ υψηλός στο γκάζι"));

        int peakBin = 0;
        for (int b = 0; b < BINS; b++) if (noise[b] > noise[peakBin]) peakBin = b;
        findings.add(String.format("Μέγιστος θόρυβος γύρω στο %.0f%% throttle (%.1f deg/s).",
                peakBin * BIN_W + BIN_W / 2, noise[peakBin]));

        double fLo = 0; int nf = 0;
        for (int b = 0; b < (int) (BINS * 0.4); b++) { fLo += freq[b]; nf++; }
        double fHi = 0; int ng = 0;
        for (int b = (int) (BINS * 0.6); b < BINS; b++) { fHi += freq[b]; ng++; }
        double fLoM = nf > 0 ? fLo / nf : 0, fHiM = ng > 0 ? fHi / ng : 0;
        findings.add(String.format("Επικρατούσα συχνότητα θορύβου: %.0f Hz (χαμηλό) → %.0f Hz (υψηλό throttle) — %s",
                fLoM, fHiM, fHiM > fLoM * 1.3 ? "ανεβαίνει με το γκάζι = harmonics κινητήρων" : "σχετικά σταθερή"));

        String label = f.sourceName + " S#" + (f.sessionIndex + 1);
        charts.add(new Chart("noiseThr", "spectro", "Θόρυβος vs throttle", "throttle %", "deg/s",
                0, BIN_W, Double.NaN, noise, new double[]{}, new String[]{}, label + " noise"));
        charts.add(new Chart("motorThr", "spectro", "Έξοδος κινητήρων vs throttle", "throttle %", "%",
                0, BIN_W, Double.NaN, motorPct, new double[]{}, new String[]{}, label + " motor%"));
        charts.add(new Chart("freqThr", "spectro", "Συχνότητα θορύβου vs throttle", "throttle %", "Hz",
                0, BIN_W, Double.NaN, freq, new double[]{}, new String[]{}, label + " freq"));

        double score = Stats.clamp(100 - sev * 60, 0, 100);
        String verdict;
        if (sev == 0) verdict = "Θόρυβος σχετικά επίπεδος σε όλο το εύρος throttle — καλά φιλτραρισμένο.";
        else if (sev < 0.5) verdict = "Ο θόρυβος αυξάνεται με το throttle — έλεγξε RPM filter / dynamic notch.";
        else verdict = "Έντονη αύξηση θορύβου στο γκάζι — πιθανά harmonics κινητήρων ή κακό φιλτράρισμα.";
        return new AnalysisResult(score, verdict, findings, charts);
    }

    private static void interpolate(double[] a) {
        int n = a.length;
        int first = -1, last = -1;
        for (int i = 0; i < n; i++) if (!Double.isNaN(a[i])) { if (first < 0) first = i; last = i; }
        if (first < 0) { for (int i = 0; i < n; i++) a[i] = 0; return; }
        for (int i = 0; i < first; i++) a[i] = a[first];
        for (int i = last + 1; i < n; i++) a[i] = a[last];
        int i = first;
        while (i <= last) {
            if (Double.isNaN(a[i])) {
                int j = i;
                while (j <= last && Double.isNaN(a[j])) j++;
                double v0 = a[i - 1], v1 = a[j];
                for (int k = i; k < j; k++) {
                    double t = (double) (k - (i - 1)) / (j - (i - 1));
                    a[k] = v0 + (v1 - v0) * t;
                }
                i = j;
            } else i++;
        }
    }

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
