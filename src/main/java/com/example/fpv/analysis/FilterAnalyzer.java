package com.example.fpv.analysis;

import com.example.fpv.blackbox.FlightData;
import com.example.fpv.blackbox.Header;

import java.util.ArrayList;
import java.util.List;

public final class FilterAnalyzer implements FlightAnalyzer {
    private static final String[] AXIS_NAMES = {"Roll", "Pitch", "Yaw"};

    @Override
    public String id() {
        return "2";
    }

    @Override
    public String title() {
        return "Φίλτρα & θόρυβος (δονήσεις)";
    }

    @Override
    public String instructions() {
        return "Πρόγραμμα πτήσης για αυτό το βήμα: hover ~15-20s, μετά ένα τίναγμα (flick) σε κάθε άξονα "
                + "και ένα γρήγορο κύκλωμα με σταθερό γκάζι. Μετράμε πού βγάζει θόρυβο το log (ένταση και "
                + "συχνότητα) και λέμε: α) φίλτρα καθάρισε σήμα ώστε να ανεβάσεις αργότερα P, ή β) το πρόβλημα "
                + "είναι μηχανικό (έλικες/μοτέρ/mount) και δεν διορθώνεται με φίλτρα. Σύγκρινε πτήσεις: "
                + "καλύτερη = ό,τι έχει πιο χαμηλό θόρυβο χωρίς κορυφή στο φάσμα. Πριν το recording "
                + "βεβαιώσου ότι οι έλικες είναι ζυγοσταθμισμένες και δεν υπάρχει χαλαρό εξάρτημα.";
    }

    @Override
    public AnalysisResult analyze(FlightData f) {
        List<String> findings = new ArrayList<>();
        if (f.mainCount < 50) {
            return new AnalysisResult(0, "Πολύ λίγα frame για ανάλυση", findings);
        }
        Header h = f.header;
        double gyroScale = h.gyroScale();
        if (!(Float.NaN != gyroScale && gyroScale > 1e-6 && gyroScale <= 1.0)) {
            gyroScale = 2000.0 / 32768.0;
        }
        int present = countNumbered(f, "gyroADC");
        if (present == 0) {
            return new AnalysisResult(0, "Το log δεν περιέχει gyroADC", List.of("δεν υπάρχει πεδίο gyroADC"));
        }

        int[] dIdx = new int[present];
        boolean hasD = f.hasMainField("axisD[0]");
        for (int a = 0; a < present; a++) {
            dIdx[a] = f.mainDef.index.containsKey("axisD[" + a + "]") && f.hasMainField("axisD[" + a + "]")
                    ? f.mainDef.index.get("axisD[" + a + "]") : -1;
        }

        int k = 8;
        double[] hfRms = new double[present];
        double[] lowRms = new double[present];
        double[] dRms = new double[present];
        int[] spikes = new int[present];
        for (int a = 0; a < present; a++) {
            double sq1 = 0, sqLow = 0, dSq = 0;
            long s1 = 0, sl = 0, ds = 0;
            for (int i = 1; i < f.mainCount; i++) {
                double g0 = f.mainValue(i - 1, "gyroADC[" + a + "]") * gyroScale;
                double g1 = f.mainValue(i, "gyroADC[" + a + "]") * gyroScale;
                double d = g1 - g0;
                sq1 += d * d;
                s1++;
                if (i >= k) {
                    double gk = f.mainValue(i - k, "gyroADC[" + a + "]") * gyroScale;
                    double step = g1 - gk;
                    sqLow += step * step;
                    sl++;
                    if (Math.abs(step) > 600) spikes[a]++;
                }
                if (dIdx[a] >= 0) {
                    double d0 = f.mainValue(i - 1, "axisD[" + a + "]");
                    double d1 = f.mainValue(i, "axisD[" + a + "]");
                    double dd = d1 - d0;
                    dSq += dd * dd;
                    ds++;
                }
            }
            hfRms[a] = Math.sqrt(s1 > 0 ? sq1 / s1 : 0);
            lowRms[a] = Math.sqrt(sl > 0 ? sqLow / sl : 0);
            dRms[a] = Math.sqrt(ds > 0 ? dSq / ds : 0);
        }

        double frameHz = f.frameRateHz();
        for (int a = 0; a < present; a++) {
            String severity = lowRms[a] < 45 ? "καλό" : lowRms[a] < 90 ? "μέτριο" : "υψηλό";
            findings.add(String.format("%s: θόρυβος HF %.1f deg/s | δόνηση (υπο-200Hz) %.1f deg/s [%s]%s",
                    AXIS_NAMES[a], hfRms[a], lowRms[a], severity,
                    hasD ? String.format(" | θόρυβος D-term %.1f", dRms[a]) : ""));
        }

        SpectrumInfo spectrum = analyzeSpectrum(f, present, frameHz, gyroScale, hfRms);
        if (spectrum.found) {
            findings.add(String.format("Φάσμα: κυρίαρχο peak ~%d Hz (%dx η μεσαία στάθμη) στον %s.",
                    (int) Math.round(spectrum.peakHz), (int) Math.round(spectrum.ratio), AXIS_NAMES[spectrum.axis]));
        } else {
            findings.add("Φάσμα: κανένα ισχυρό peak θορύβου πάνω από ~100 Hz — καθαρό υπόβαθρο.");
        }

        int clipCount = 0;
        for (int i = 0; i < f.mainCount; i++) {
            for (int a = 0; a < present; a++) {
                if (Math.abs(f.mainValue(i, "gyroADC[" + a + "]")) >= 32000) clipCount++;
            }
        }
        if (clipCount > 0) findings.add("Gyro samples στο όριο (clipping): " + clipCount);
        int spikeTotal = 0;
        for (int s : spikes) spikeTotal += s;
        if (spikeTotal > 0) findings.add("Αιχμές (spikes) gyro: " + spikeTotal);

        findings.addAll(prescription(present, hfRms, lowRms, dRms, spectrum, hasD));

        List<Chart> charts = new ArrayList<>();
        for (int a = 0; a < Math.min(present, 2); a++) {
            Chart c = spectrumChart(f, a, frameHz, gyroScale);
            if (c != null) charts.add(c);
        }

        double score = 100;
        for (int a = 0; a < present; a++) {
            score -= Stats.clamp((lowRms[a] - 20) / 40.0, 0, 1) * 25;
            score -= Stats.clamp((hfRms[a] - 6) / 10.0, 0, 1) * 20;
            if (hasD) score -= Stats.clamp((dRms[a] - 3) / 8.0, 0, 1) * 12;
        }
        score -= Math.min(10, spikeTotal * 2);
        if (clipCount > 0) score -= 5;
        if (spectrum.found && spectrum.peakHz >= 100 && spectrum.peakHz <= 400) score -= 12;
        score = Stats.clamp(score, 0, 100);

        int worst = 0;
        for (int a = 1; a < present; a++) if (lowRms[a] > lowRms[worst]) worst = a;
        boolean hasPeak = spectrum.found && spectrum.peakHz >= 100 && spectrum.peakHz <= 400;
        String verdict;
        if (score >= 80 && hasPeak) {
            verdict = "Καθαρό σήμα με διακριτό peak στα ~" + (int) Math.round(spectrum.peakHz)
                    + " Hz — σιγουρέψου ότι το dynamic notch το πιάνει, μετά PID.";
        } else if (score >= 80) {
            verdict = "Καθαρό σήμα — μπορείς να χαλαρώσεις φίλτρα (λιγότερη καθυστέρηση) και να πας στο PID.";
        } else if (score >= 60) {
            verdict = "Μέτριος θόρυβος (" + AXIS_NAMES[worst] + " ο πιο ταραγμένος) — κράτα stock φίλτρα, δες τα ευρήματα.";
        } else {
            verdict = "Υψηλός θόρυβος στον " + AXIS_NAMES[worst] + " — μη δώσεις φίλτρα· φτιάξε μηχανικά πρώτα.";
        }
        String advice = nextStep(h, hfRms, lowRms, dRms, spectrum, hasD);
        return new AnalysisResult(score, verdict, findings, charts, advice);
    }

    @Override
    public List<String> settings(FlightData f) {
        Header h = f.header;
        List<String> out = new ArrayList<>();
        addHz(out, h, "gyro_lpf1_static_hz", "Gyro LPF1");
        addHz(out, h, "gyro_lpf2_static_hz", "Gyro LPF2");
        addHz(out, h, "dterm_lpf1_static_hz", "D-term LPF1");
        addHz(out, h, "dterm_lpf2_static_hz", "D-term LPF2");
        Integer nc = h.intHeader("dyn_notch_count", null);
        if (nc != null) {
            out.add("Dyn Notch: count " + nc + ", Q " + h.intHeader("dyn_notch_q", 0)
                    + ", " + h.intHeader("dyn_notch_min_hz", 0) + "-" + h.intHeader("dyn_notch_max_hz", 0) + " Hz");
        }
        Integer rq = h.intHeader("rpm_filter_q", null);
        if (rq != null) {
            out.add("RPM: harmonics " + h.intHeader("rpm_filter_harmonics", 0)
                    + ", Q " + rq + ", min " + h.intHeader("rpm_filter_min_hz", 0)
                    + ", fade " + h.intHeader("rpm_filter_fade_range_hz", 0) + " Hz");
        } else {
            out.add("RPM filter: OFF");
        }
        return out;
    }

    private static void addHz(List<String> out, Header h, String key, String label) {
        Integer v = h.intHeader(key, null);
        if (v != null) out.add(label + ": " + (v == 0 ? "OFF" : v + " Hz"));
    }

    /** Concrete "what to change and re-fly" guidance referencing the current header settings. */
    private static String nextStep(Header h, double[] hf, double[] low, double[] dRms,
                                   SpectrumInfo sp, boolean hasD) {
        double hfMax = 0, lowMax = 0, dMax = 0;
        for (double v : hf) hfMax = Math.max(hfMax, v);
        for (double v : low) lowMax = Math.max(lowMax, v);
        for (double v : dRms) dMax = Math.max(dMax, v);

        Integer g1 = h.intHeader("gyro_lpf1_static_hz", null);
        Integer d1 = h.intHeader("dterm_lpf1_static_hz", null);
        Integer d2 = h.intHeader("dterm_lpf2_static_hz", null);
        Integer rpmQ = h.intHeader("rpm_filter_q", null);
        Integer nc = h.intHeader("dyn_notch_count", null);
        Integer nmin = h.intHeader("dyn_notch_min_hz", null);
        Integer nmax = h.intHeader("dyn_notch_max_hz", null);
        boolean rpmOn = rpmQ != null;

        boolean lowRes = lowMax >= 90 || (sp.found && sp.peakHz < 100);
        boolean peak = sp.found && sp.peakHz >= 100 && sp.peakHz <= 400;
        boolean clean = hfMax <= 3 && lowMax < 45 && !lowRes && !peak;
        boolean loud = hfMax > 6 || dMax > 8;

        StringBuilder b = new StringBuilder();
        if (lowRes) {
            b.append("ΤΙ ΝΑ ΚΑΝΕΙΣ: ΜΗΝ προσθέσεις άλλο φιλτράρισμα. Ο θόρυβος είναι χαμηλής συχνότητας ")
                    .append("(μηχανικός). Έλεγξε έλικες/ζυγοστάθμιση, μοτέρ, χαλαρές βίδες, μαλακό mount. ")
                    .append("Μετά ξαναπέτα ΞΑΝΑ και ξανατρέξε αυτή την ανάλυση.");
        } else if (loud) {
            b.append("ΤΙ ΝΑ ΚΑΝΕΙΣ: Χρειάζεσαι ΠΕΡΙΣΣΟΤΕΡΟ φιλτράρισμα. Δοκίμασε:\n");
            if (d1 != null && d1 > 0) {
                b.append("  • set dterm_lpf1_static_hz = ").append(Math.max(60, (int) Math.round(d1 * 0.8))).append("\n");
            }
            if (rpmOn) {
                b.append("  • set rpm_filter_q = ").append(Math.min(700, (int) Math.round(rpmQ * 1.15)))
                        .append("   (πιο στενό notch)\n");
            } else {
                b.append("  • Ενεργοποίησε RPM filter (bidirectional DShot) αν γίνεται.\n");
            }
            if (nc != null) {
                b.append("  • Dynamic notch: count ").append(nc)
                        .append(", δοκίμασε λίγο πιο κάτω dyn_notch_min_hz ώστε να πιάσει τη χαμηλή αιχμή.\n");
            }
            b.append("  Πέτα ΞΑΝΑ και σύγκρινε — μείωσε ΜΟΝΟ όσο χρειάζεται (κάθε φίλτρο = lag).");
        } else if (clean) {
            b.append("ΤΙ ΝΑ ΚΑΝΕΙΣ: Τα φίλτρα καθαρίζουν ΚΑΛΑ — μπορείς να τα κάνεις ΠΙΟ ΕΠΙΘΕΤΙΚΑ ")
                    .append("(λιγότερο φιλτράρισμα = λιγότερη καθυστέρηση). Δοκίμασε ΕΝΑ τη φορά:\n");
            if (g1 != null) {
                if (g1 == 0) {
                    b.append("  • Gyro LPF1 ήδη OFF — καλά.\n");
                } else {
                    b.append("  • set gyro_lpf1_static_hz = ").append((int) Math.round(g1 * 1.3))
                            .append("   (αν έχεις RPM filter, δοκίμασε 0)\n");
                }
            }
            if (d1 != null && d1 > 0) {
                b.append("  • set dterm_lpf1_static_hz = ").append((int) Math.round(d1 * 1.25)).append("\n");
            }
            if (d2 != null && d2 > 0) {
                b.append("  • set dterm_lpf2_static_hz = ").append((int) Math.round(d2 * 1.25)).append("\n");
            }
            if (!rpmOn) {
                b.append("  • Σκέψου RPM filter (bidirectional DShot) — καθαρίζει τους κινητήρες.\n");
            }
            b.append("  Ανέβασε ένα πράγμα τη φορά (+10-20%), πέτα ΙΔΙΟ throttle sweep, έλεγξε θερμοκρασίες, ξανατρέξε.");
        } else if (peak) {
            int f0 = (int) Math.round(sp.peakHz);
            b.append("ΤΙ ΝΑ ΚΑΝΕΙΣ: Υπάρχει συγκεκριμένη αιχμή ~").append(f0)
                    .append(" Hz. Πρώτα φρόντισε να την πιάνει το dynamic notch");
            if (nmin != null && nmax != null) {
                boolean covered = nmin < f0 && f0 < nmax;
                b.append(" (dyn_notch_min_hz < ").append(f0).append(" < dyn_notch_max_hz — έχεις ")
                        .append(nmin).append("-").append(nmax).append(" Hz: ")
                        .append(covered ? "καλύπτεται ✓).\n" : "ΔΕΝ καλύπτεται ✗ → ρύθμισε τη περιοχή).\n");
            } else {
                b.append(" (ρύθμισε dyn_notch_min_hz/max_hz να την περιέχουν).\n");
            }
            if (d1 != null && d1 > 0) {
                b.append("  • Αν το φάσμα πάνω από 100 Hz είναι μετά καθαρό, δοκίμασε set dterm_lpf1_static_hz = ")
                        .append((int) Math.round(d1 * 1.2)).append(".\n");
            }
            b.append("  Ξαναπέτα και ξανατρέξε — σύγκρινε το score με/χωρίς την αλλαγή.");
        } else {
            b.append("ΤΙ ΝΑ ΚΑΝΕΙΣ: Τα φίλτρα είναι εντάξει/ισορροπημένα. Αν θέλεις πιο επιθετικά, δοκίμασε ")
                    .append("+10% στα cutoffs και έλεγξε θερμοκρασίες· αλλιώς προχώρα στο PID. Ξανατρέξε μετά από κάθε αλλαγή.");
        }
        return b.toString();
    }

    private Chart spectrumChart(FlightData f, int axis, double frameHz, double gyroScale) {
        if (frameHz < 200 || f.mainCount < 2048) return null;
        int n = 1024;
        while (n < frameHz) n <<= 1;
        if (n > f.mainCount) return null;
        double[] window = Spectrum.window(n);
        int half = n / 2;
        double[] acc = new double[half];
        int blocks = 0;
        int start = f.mainCount / 4;
        int step = n / 2;
        double[] block = new double[n];
        while (start + n <= f.mainCount && blocks < 8) {
            for (int j = 0; j < n; j++) {
                block[j] = f.mainValue(start + j, "gyroADC[" + axis + "]") * gyroScale;
            }
            double[] p = Spectrum.power(block, window);
            for (int b = 0; b < half; b++) acc[b] += p[b];
            blocks++;
            start += step;
        }
        if (blocks == 0) return null;
        for (int b = 0; b < half; b++) acc[b] /= blocks;
        double max = 0;
        for (double v : acc) if (v > max) max = v;
        if (max <= 0) return null;

        int binMax = Math.min(half - 1, (int) Math.floor(500.0 * n / frameHz));
        if (binMax < 8) return null;
        double binHz = frameHz / n;
        double[] db = new double[binMax + 1];
        int peakBin = 0;
        for (int b = 0; b <= binMax; b++) {
            db[b] = 10 * Math.log10(Math.max(acc[b], max * 1e-9) / max);
            if (acc[b] > acc[peakBin]) peakBin = b;
        }
        double[] curve = downsample(db, 256);
        double span = binMax * binHz;
        double dx = curve.length > 1 ? span / (curve.length - 1) : binHz;
        double floor = noiseFloor(db, binHz, 100, 500);
        return new Chart("spectrum" + axis, "spectrum", AXIS_NAMES[axis], "Hz", "dB",
                0, dx, -30.0, curve,
                new double[]{floor, peakBin * binHz},
                new String[]{"floor dB", "peak Hz"});
    }

    private static double noiseFloor(double[] db, double binHz, double fLo, double fHi) {
        List<Double> vals = new ArrayList<>();
        for (int b = 0; b < db.length; b++) {
            double f = b * binHz;
            if (f >= fLo && f <= fHi) vals.add(db[b]);
        }
        if (vals.isEmpty()) return 0;
        vals.sort(null);
        return vals.get(vals.size() / 2);
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

    private List<String> prescription(int present, double[] hf, double[] low, double[] dRms,
                                      SpectrumInfo sp, boolean hasD) {
        List<String> out = new ArrayList<>();
        double hfMax = 0;
        for (double v : hf) hfMax = Math.max(hfMax, v);
        double lowMax = 0;
        for (double v : low) lowMax = Math.max(lowMax, v);
        double dMax = 0;
        for (double v : dRms) dMax = Math.max(dMax, v);

        if (lowMax >= 90 || (sp.found && sp.peakHz < 100)) {
            out.add("⚠ Χαμηλής συχνότητας δόνηση — πρόβλημα μηχανικό (έλικες / μοτέρ / μαλακό mount / πλαίσιο). "
                    + "Δεν φιλτράρεται με notch: το notch θα έκοβε και την ακρίβεια. Ελέγξε μηχανικά ΠΡΙΝ πας στα φίλτρα.");
        }
        if (sp.found && sp.peakHz >= 100 && sp.peakHz <= 400) {
            int f0 = (int) Math.round(sp.peakHz);
            out.add("Δυνατό peak στα ~" + f0 + " Hz: ρύθμισε το dynamic notch (width/center) ώστε να το σκεπάζει, "
                    + "ή κράτα τα gyro/dterm φίλτρα να κόβουν κάτω από αυτό. Αν δεν υπάρχει dynamic notch, βάλε το.");
        }
        if (hasD && dMax > 8) {
            out.add("Ο θόρυβος D-term είναι υψηλός (" + String.format("%.0f", dMax) + "): "
                    + "χαμήλωσε το dterm_lowpass_hz (και το dterm_lowpass2) όσο χρειάζεται να πέσει ο θόρυβος, "
                    + "χωρίς να θαμπώσει το tracking.");
        }
        boolean hasPeak = sp.found && sp.peakHz >= 100 && sp.peakHz <= 400;
        if (hfMax <= 3) {
            if (hasPeak) {
                out.add("Λίγος broadband θόρυβος, αλλά υπάρχει διακριτό peak στα ~" + (int) Math.round(sp.peakHz)
                        + " Hz: βεβαιώσου ότι το dynamic notch το πιάνει πριν χαλαρώσεις τα υπόλοιπα φίλτρα.");
            } else {
                out.add("Σήμα πολύ καθαρό: μπορείς να ανεβάσεις gyro_lowpass_hz / dterm_lowpass_hz για λιγότερη "
                        + "καθυστέρηση, ή να προχωρήσεις κατευθείαν στο PID με stock φίλτρα.");
            }
        } else if (hfMax <= 6) {
            out.add("Μέτριο υπόβαθρο: κράτα τα stock φίλτρα (και dynamic notch αν είναι ενεργό) και προχώρα στο PID.");
        } else {
            out.add("Υψηλό υπόβαθρο θορύβου: επιβεβαίωσε πρώτα ότι δεν είναι μηχανικό· μετά δοκίμασε δυνατότερο "
                    + "φιλτράρισμα (δεντρικά dynamic notch + λίγο πιο κάτω dterm_lowpass_hz).");
        }
        out.add("Σημείωση: όλα τα φίλτρα φέρνουν καθυστέρηση — θέλεις ΟΣΟ λιγότερο φιλτράρισμα χρειάζεται "
                + "για καθαρό σήμα, όχι το μέγιστο.");
        out.add("Οδηγίες: τα βήματα στο πρόγραμμα αλλάζουν συχνότητες στο Betaflight (gyro_lowpass_hz, dterm_lowpass_hz, "
                + "dynamic notch) και ξαναλύνεις με την ίδια πτήση για να συγκρίνουμε.");
        return out;
    }

    private SpectrumInfo analyzeSpectrum(FlightData f, int present, double frameHz, double gyroScale, double[] hfRms) {
        SpectrumInfo out = new SpectrumInfo();
        if (frameHz < 200 || f.mainCount < 2048) return out;

        int axis = 0;
        for (int a = 1; a < present; a++) if (hfRms[a] > hfRms[axis]) axis = a;

        int n = 1024;
        while (n < frameHz) n <<= 1;
        if (n > f.mainCount) return out;
        double[] window = Spectrum.window(n);
        int half = n / 2;
        double[] acc = new double[half];
        int blocks = 0;
        int start = f.mainCount / 4;
        int step = n / 2;
        int maxBlocks = 8;
        double[] block = new double[n];
        while (start + n <= f.mainCount && blocks < maxBlocks) {
            for (int j = 0; j < n; j++) {
                block[j] = f.mainValue(start + j, "gyroADC[" + axis + "]") * gyroScale;
            }
            double[] p = Spectrum.power(block, window);
            for (int b = 0; b < half; b++) acc[b] += p[b];
            blocks++;
            start += step;
        }
        if (blocks == 0) return out;
        for (int b = 0; b < half; b++) acc[b] /= blocks;

        int binMin = (int) Math.ceil(100.0 * n / frameHz);
        int binMax = half - 1;
        if (binMin + 2 >= binMax) return out;

        double maxPow = 0;
        int maxBin = -1;
        for (int b = binMin + 1; b <= binMax - 1; b++) {
            boolean localMax = acc[b] >= acc[b - 1] && acc[b] >= acc[b + 1];
            if (localMax && acc[b] > maxPow) {
                maxPow = acc[b];
                maxBin = b;
            }
        }
        if (maxBin < 0) return out;
        double[] band = new double[binMax - binMin + 1];
        for (int b = binMin; b <= binMax; b++) band[b - binMin] = acc[b];
        double median = Stats.median(band);
        if (median <= 0) return out;
        double ratio = maxPow / median;
        if (ratio < 3) return out;

        out.found = true;
        out.axis = axis;
        out.peakHz = maxBin * frameHz / n;
        out.ratio = ratio;
        return out;
    }

    private static final class SpectrumInfo {
        boolean found = false;
        int axis = 0;
        double peakHz = 0;
        double ratio = 0;
    }

    private static int countNumbered(FlightData f, String base) {
        int n = 0;
        while (f.hasMainField(base + "[" + n + "]")) n++;
        return n;
    }
}