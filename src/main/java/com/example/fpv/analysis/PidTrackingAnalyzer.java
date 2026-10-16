package com.example.fpv.analysis;

import com.example.fpv.blackbox.FlightData;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class PidTrackingAnalyzer implements FlightAnalyzer {
    private static final String[] AXIS_NAMES = {"Roll", "Pitch", "Yaw"};

    @Override
    public String id() {
        return "3";
    }

    @Override
    public String title() {
        return "Ποιότητα tracking PID (setpoint vs gyro)";
    }

    @Override
    public String instructions() {
        return "Εδώ συγκρίνουμε πόσο καλά ακολουθεί το drone την εντολή του pilot (setpoint) "
                + "στην πραγματική περιστροφή (gyro). Θέλεις petites πτήσεις με τις ίδιες κινήσεις: μερικά "
                + "γρήγορα flicks και μια πτήση με σταθερό rate. Όσο μικρότερο το σφάλμα και το overshoot, "
                + "τόσο καλύτερο το PID tuning. Να θυμάσαι: D πάνω = περισσότερo μαγνήτισμα αλλά και θόρυβος· "
                + "P πάνω = πιο ευκρινές αλλά κίνδυνος oscillation.";
    }

    @Override
    public AnalysisResult analyze(FlightData f) {
        List<String> findings = new ArrayList<>();
        if (f.mainCount < 100) {
            return new AnalysisResult(0, "Πολύ λίγα frame", findings);
        }
        int axes = countNumbered(f, "setpoint");
        if (axes == 0 || !f.hasMainField("gyroADC[0]")) {
            return new AnalysisResult(0, "Δεν υπάρχουν πεδία setpoint/gyroADC για σύγκριση",
                    List.of("ενεργοποίησε setpoint logging στο Blackbox config"));
        }

        double frameHz = f.frameRateHz();
        double[] axisScores = new double[axes];
        List<String> axisLines = new ArrayList<>();

        for (int a = 0; a < axes; a++) {
            String sp = "setpoint[" + a + "]";
            String gy = "gyroADC[" + a + "]";
            String p = "axisP[" + a + "]";
            if (!f.hasMainField(gy) || !f.hasMainField(p)) {
                axisScores[a] = 50;
                continue;
            }

            long active = 0;
            double errAbsSum = 0, errSq = 0;
            long overshootBoth = 0;
            long overshootSamples = 0;
            double[] activeErrors = new double[f.mainCount];
            int prevP1 = Integer.MIN_VALUE;
            int prevP2 = Integer.MIN_VALUE;
            double prevSpv = 0;
            long steadyOsc = 0;
            long steadySamples = 0;
            double dSq = 0;
            long dn = 0;

            for (int i = 0; i < f.mainCount; i++) {
                double spv = f.mainValue(i, sp);
                double gyv = f.mainValue(i, gy);
                double pv = f.mainValue(i, p);
                double err = spv - gyv;

                boolean isActive = Math.abs(spv) > 30;
                if (isActive) {
                    activeErrors[(int) active] = Math.abs(err);
                    active++;
                    errAbsSum += Math.abs(err);
                    errSq += err * err;
                    if (Math.abs(spv) > 90) {
                        overshootSamples++;
                        boolean exceeds = (spv > 0 && gyv > spv) || (spv < 0 && gyv < spv);
                        if (exceeds) overshootBoth++;
                    }
                }
                if (i > 0) {
                    double d1 = f.mainValue(i, "axisD[" + a + "]");
                    double d0 = f.mainValue(i - 1, "axisD[" + a + "]");
                    double dd = d1 - d0;
                    dSq += dd * dd;
                    dn++;

                    boolean steady = Math.abs(spv) < 10 && Math.abs(prevSpv) < 10;
                    if (steady) {
                        steadySamples++;
                        if (prevP1 != Integer.MIN_VALUE && prevP2 != Integer.MIN_VALUE) {
                            int d1p = Integer.signum((int) pv - prevP1);
                            int d0p = Integer.signum(prevP1 - prevP2);
                            if (d1p != 0 && d0p != 0 && d1p != d0p) steadyOsc++;
                        }
                    }
                }
                prevSpv = spv;
                prevP2 = prevP1;
                prevP1 = (int) pv;
            }
            if (dn > 0) {
                double dRms = Math.sqrt(dSq / dn);
                axisLines.add(String.format("%s: RMS σφάλμα %.1f deg/s (active) | μέσο |err| %.1f | P99 %.1f | D-noise %.1f",
                        AXIS_NAMES[a], Math.sqrt(active > 0 ? errSq / active : 0),
                        active > 0 ? errAbsSum / active : 0,
                        active > 0 ? Stats.percentile(activePrefix(activeErrors, active), 99) : 0,
                        dRms));
            }

            double rmsErr = Math.sqrt(active > 0 ? errSq / active : 0);
            double p99 = active > 0 ? Stats.percentile(activePrefix(activeErrors, active), 99) : 0;
            double overshootRatio = overshootSamples > 0 ? overshootBoth / (double) overshootSamples : 0;
            double oscPerSec = steadySamples > 0 ? steadyOsc / (double) steadySamples * frameHz : 0;

            double score = 100;
            score -= Stats.clamp((rmsErr - 8) / 30.0, 0, 1) * 40;
            score -= Stats.clamp((p99 - 40) / 200.0, 0, 1) * 20;
            score -= overshootRatio * 100 * 0.25;
            score -= Stats.clamp((oscPerSec - 10) / 20.0, 0, 1) * 10;
            if (oscPerSec > 20) {
                axisLines.add(String.format("   (ταλάντωση P σε σταθερή εντολή: %.0f reversals/s — πιθανόν πολύ P)", oscPerSec));
            }
            axisScores[a] = Stats.clamp(score, 0, 100);
        }

        double total = 0;
        for (double s : axisScores) total += s;
        double score = Stats.clamp(total / Math.max(1, axes), 0, 100);

        for (String line : axisLines) findings.add(line);
        String verdict;
        if (score >= 80) verdict = "Το drone ακολουθεί τις εντολές ακριβώς χωρίς μεγάλο overshoot.";
        else if (score >= 60) verdict = "Αποδεκτό tracking, αλλά υπάρχει καθυστέρηση ή overshoot — δοκίμασε λίγο παραπάνω P ή αύξησε το FF.";
        else verdict = "Το tracking υστερεί ή ταλαντώνεται — κοιτά Ρ/D/FF και φίλτρα πριν πετάξεις με αυτό το tune.";
        return new AnalysisResult(score, verdict, findings);
    }

    private static double[] activePrefix(double[] values, long count) {
    return Arrays.copyOfRange(values, 0, (int) count);
}

    private static int countNumbered(FlightData f, String base) {
        int n = 0;
        while (f.hasMainField(base + "[" + n + "]")) n++;
        return n;
    }
}