package com.example.fpv.analysis;

import com.example.fpv.blackbox.Event;
import com.example.fpv.blackbox.FlightData;
import com.example.fpv.blackbox.Header;

import java.util.ArrayList;
import java.util.List;

public final class FlightSummaryAnalyzer implements FlightAnalyzer {
    public static final double GYRO_CLIP_RAW = 32000;
    public static final double CRASH_G = 6.0;
    public static final double CRASH_GYRO_DPS = 900.0;
    public static final double CELL_MIN_V = 3.30;
    public static final double MOTOR_SATURATION = 0.97;
    public static final int SATURATION_MS = 50;
    public static final double DROPOUT_PCT = 1.0;
    public static final double RSSI_LOW = 0.20;
    public static final int RSSI_LOW_MS = 100;
    public static final double MOTOR_IMBALANCE = 0.12;
    public static final double RSSI_SCALE = 1023.0;

    @Override
    public String id() {
        return "1";
    }

    @Override
    public String title() {
        return "Σύνοψη πτήσης & τεχνική υγεία";
    }

    @Override
    public String instructions() {
        return "Εδώ βλέπεις μία γενική εικόνα κάθε πτήσης: διάρκεια, μπαταρία, κινητήρες, GPS, "
                + "ασύρματο και τυχόν ανωμαλίες. Χρησιμεύει για να αποκλείσεις προβλήματα (sag μπαταρίας, "
                + "κράτημα κινητήρων, απώλεια σήματος) πριν περάσεις στο tuning.";
    }

    @Override
    public AnalysisResult analyze(FlightData f) {
        List<String> findings = new ArrayList<>();
        List<String> anomalies = new ArrayList<>();
        List<String> anomalyCodes = new ArrayList<>();
        Header h = f.header;

        double durationS = f.mainCount > 1
                ? (f.timeAt(f.mainCount - 1) - f.timeAt(0)) / 1_000_000.0 : 0;
        if (durationS <= 0) {
            return new AnalysisResult(0, "Δεν υπάρχουν έγκυρα frame δεδομένων", List.of("δεν αποκωδικοποιήθηκε κανένα main frame"));
        }
        if (f.mainCount < 2) {
            return new AnalysisResult(0, "Η πτήση είναι πολύ μικρή", findings);
        }

        double frameHz = f.frameRateHz();
        double gyroScale = h.gyroScale();
        if (!(Float.NaN != gyroScale && gyroScale > 1e-6 && gyroScale <= 1.0)) {
            gyroScale = 2000.0 / 32768.0;
        }

        int[] motorRange = h.motorOutputRange();
        long motorLow = motorRange[0] != Integer.MIN_VALUE ? motorRange[0] : h.minThrottle();
        long motorHigh = motorRange[1] != Integer.MIN_VALUE ? motorRange[1] : h.maxThrottle();
        long motorSpan = Math.max(1, motorHigh - motorLow);
        int motorCount = countNumbered(f, "motor");

        boolean hasVbat = f.hasMainField("vbatLatest");
        boolean hasAmps = f.hasMainField("amperageLatest");
        boolean hasRssi = f.hasMainField("rssi");
        boolean hasGyro = countNumbered(f, "gyroADC") > 0;
        boolean hasAcc = countNumbered(f, "accSmooth") > 0;

        double[] throttleSum = new double[Math.max(1, motorCount)];
        double[] motorMaxFrac = new double[Math.max(1, motorCount)];
        long saturationRunUs = 0;
        double throttleMax = 0;
        int saturatedSamples = 0;

        double vStart = 0, vMin = Double.MAX_VALUE, vEnd = 0;
        double ampsMax = 0;
        double ampSeconds = 0;
        double rssiMin = Double.MAX_VALUE;
        double lowRssiRunUs = 0;
        for (int i = 0; i < motorCount; i++) {
            motorMaxFrac[i] = 0;
        }

        double[] gyroSq = new double[Math.max(1, countNumbered(f, "gyroADC"))];
        double[] gyroPeak = new double[gyroSq.length];
        double accPeakG = 0;
        int gyroClipped = 0;

        long startTimeUs = f.timeAt(0);
        long prevTimeUs = -1;
        int expectedFrames = 0;
        long firstIteration = -1, lastIteration = -1;

        for (int i = 0; i < f.mainCount; i++) {
            long t = f.timeAt(i);
            long iteration = f.mainValueUnsigned(i, "loopIteration");
            if (firstIteration < 0) firstIteration = iteration;
            lastIteration = iteration;

            long dt = prevTimeUs < 0 ? 0 : Math.max(0, t - prevTimeUs);
            prevTimeUs = t;

            boolean saturated = true;
            double throttle = 0;
            for (int m = 0; m < motorCount; m++) {
                double frac = (f.mainValue(i, "motor[" + m + "]") - motorLow) / (double) motorSpan;
                frac = Stats.clamp(frac, 0, 1.5);
                throttleSum[m] += frac;
                if (frac > motorMaxFrac[m]) motorMaxFrac[m] = frac;
                if (frac < MOTOR_SATURATION) saturated = false;
            }
            if (motorCount > 0) {
                throttle = throttleSum.length > 0 ? 0 : 0;
                for (int m = 0; m < motorCount; m++) {
                    throttle += (f.mainValue(i, "motor[" + m + "]") - motorLow) / (double) (motorCount * motorSpan);
                }
                if (throttle > throttleMax) throttleMax = throttle;
            }
            if (saturated && motorCount > 0) {
                saturatedSamples++;
                saturationRunUs += dt;
                if (saturationRunUs >= SATURATION_MS * 1000L) {
                    flagOnce(anomalyCodes, anomalies, "motor-saturation",
                            "Όλοι οι κινητήρες πάνω από " + (int) (MOTOR_SATURATION * 100) + "% για "
                                    + (saturationRunUs / 1000) + " ms στο t=" + String.format("%.2f", (t - startTimeUs) / 1e6) + "s");
                }
            } else {
                saturationRunUs = 0;
            }

            if (hasVbat) {
                double volts = f.mainValue(i, "vbatLatest") / 100.0;
                if (vStart == 0) vStart = volts;
                vEnd = volts;
                if (volts < vMin) vMin = volts;
            }
            if (hasAmps) {
                double amps = f.mainValue(i, "amperageLatest") / 100.0;
                if (amps > ampsMax) ampsMax = amps;
                ampSeconds += amps * dt / 1_000_000.0;
            }
            if (hasRssi) {
                double rssi = f.mainValue(i, "rssi");
                if (rssi < rssiMin) rssiMin = rssi;
                if (rssi < RSSI_SCALE * RSSI_LOW) {
                    lowRssiRunUs += dt;
                    if (lowRssiRunUs >= RSSI_LOW_MS * 1000L) {
                        flagOnce(anomalyCodes, anomalies, "low-rssi",
                                "RSSI έπεσε στο " + (int) rssi + "/1023 για πάνω από " + RSSI_LOW_MS + " ms στο t="
                                        + String.format("%.2f", (t - startTimeUs) / 1e6) + "s");
                    }
                } else {
                    lowRssiRunUs = 0;
                }
            }

            double peakDps = 0;
            for (int g = 0; g < gyroSq.length; g++) {
                double raw = f.mainValue(i, "gyroADC[" + g + "]");
                if (Math.abs(raw) >= GYRO_CLIP_RAW) gyroClipped++;
                double dps = Math.abs(raw * gyroScale);
                gyroSq[g] += dps * dps;
                if (dps > gyroPeak[g]) gyroPeak[g] = dps;
                if (dps > peakDps) peakDps = dps;
            }

            if (hasAcc) {
                double sum = 0;
                for (int a = 0; a < countNumbered(f, "accSmooth"); a++) {
                    double v = f.mainValue(i, "accSmooth[" + a + "]");
                    sum += v * v;
                }
                double mag = Math.sqrt(sum) / h.acc1G();
                if (mag > accPeakG) accPeakG = mag;
                if (mag >= CRASH_G && peakDps >= CRASH_GYRO_DPS) {
                    flagOnce(anomalyCodes, anomalies, "impact",
                            String.format("%.1f", mag) + " g με " + String.format("%.0f", peakDps)
                                    + " deg/s στο t=" + String.format("%.2f", (t - startTimeUs) / 1e6) + "s — μοιάζει με crash/χτύπημα");
                }
            }
            expectedFrames++;
        }

        long spanIterations = lastIteration - firstIteration + 1;
        Header.Ratio ratio = h.pInterval();
        int num = ratio.num, den = ratio.den;
        long expectedIterations = den > 1 && num >= 1 && num <= den
                ? Math.max(1, spanIterations * num / den) : Math.max(1, spanIterations);
        long dropped = Math.max(0, expectedIterations - f.mainCount);
        double dropoutPct = 100.0 * dropped / (double) expectedIterations;

        int cells = detectCells(h);
        double vPerCellMin = cells > 0 && vMin != Double.MAX_VALUE && vMin > 0 ? vMin / cells : 0;
        double mah = ampSeconds / 3.6;

        findings.add(String.format("Διάρκεια: %.1f s   (rate %.0f Hz, %d main frames)", durationS, frameHz, f.mainCount));
        if (motorCount > 0) {
            double throttleMean = 0;
            for (double s : throttleSum) throttleMean += s;
            throttleMean /= Math.max(1, f.mainCount * motorCount) * 1.0;
            findings.add(String.format("Κινητήρ.: μέση %d%% | max %.0f%% | saturation (όλοι>97%%): %d%% του χρόνου",
                    (int) Math.round(throttleMean * 100), throttleMax * 100.0, (int) (saturatedSamples * 100.0 / f.mainCount)));
            StringBuilder sb = new StringBuilder("   ανά κινητήρα (υψηλ/μέση): ");
            for (int m = 0; m < motorCount; m++) {
                if (m > 0) sb.append("  ");
                sb.append(String.format("M%d: %.0f%%/%.0f%%", m + 1, motorMaxFrac[m] * 100, throttleSum[m] / f.mainCount * 100));
            }
            findings.add(sb.toString());
        }
        if (hasVbat) {
            String cellsStr = cells > 0 ? cells + "S" : "?";
            findings.add(String.format("Μπαταρία: start %.2f V | min %.2f V (%.2f V/κυψέλη) | end %.2f V [%s]",
                    vStart, vMin, vPerCellMin, vEnd, cellsStr));
        }
        if (hasAmps) {
            findings.add(String.format("Περίληψη: max %.1f A | κατανάλωση %.0f mAh", ampsMax, mah));
        }
        if (hasRssi) {
            findings.add(String.format("RSSI min: %d/1023", (int) rssiMin));
        }
        if (hasGyro) {
            StringBuilder gs = new StringBuilder("Gyro (deg/s) peak→  R/P/Y: ");
            for (int g = 0; g < gyroSq.length; g++) {
                if (g > 0) gs.append("  ");
                gs.append(String.format("%.0f", gyroPeak[g]));
            }
            gs.append("   RMS: ");
            for (int g = 0; g < gyroSq.length; g++) {
                if (g > 0) gs.append("  ");
                gs.append(String.format("%.1f", Math.sqrt(gyroSq[g] / f.mainCount)));
            }
            findings.add(gs.toString());
        }
        if (hasAcc) {
            findings.add(String.format("Μέγιστη επιτάχυνση: %.2f g", accPeakG));
        }

        addGps(f, findings);

        List<String> disarmReasons = new ArrayList<>();
        List<String> modes = new ArrayList<>();
        boolean logEnded = f.reachedLogEnd;
        for (Event ev : f.events) {
            if ("disarm".equals(ev.name)) {
                Object r = ev.get("reasonName");
                if (r != null) disarmReasons.add(r.toString());
            } else if ("flightMode".equals(ev.name)) {
                Object flags = ev.get("flags");
                if (flags instanceof Long l) modes.addAll(Stats.decodeModeFlags(l));
            }
        }
        if (!disarmReasons.isEmpty()) {
            findings.add("Disarm: " + String.join(", ", disarmReasons));
        }
        if (!modes.isEmpty()) {
            findings.add("Modes: " + String.join(", ", modes));
        }

        if (dropoutPct > DROPOUT_PCT) {
            flagOnce(anomalyCodes, anomalies, "logging-dropouts",
                    (f.stats.corruptFrames > 0
                            ? dropped + " από " + expectedIterations + " iteration χάθηκαν (" + String.format("%.1f", dropoutPct) + "%) λόγω corruption"
                            : dropped + " iteration δεν προλάβανε να γραφτούν (" + String.format("%.1f", dropoutPct) + "%) — η συσκευή καθυστερούσε"));
        }
        if (f.stats.corruptFrames > 0) {
            flagOnce(anomalyCodes, anomalies, "corruption",
                    f.stats.corruptFrames + " unreadable frames, " + f.stats.corruptBytes + " bytes, " + f.stats.resyncs + " resyncs");
        }
        if (vPerCellMin > 0 && vPerCellMin < CELL_MIN_V) {
            flagOnce(anomalyCodes, anomalies, "voltage-sag",
                    "Η μπαταρία βούλιαξε στα " + String.format("%.2f", vPerCellMin) + " V/κυψέλη (" + String.format("%.2f", vMin) + " V, " + cells + "S), κάτω από το όριο " + CELL_MIN_V + " V");
        }
        if (!anomalyCodes.contains("impact")) {
            double motorImbalance = 0;
            if (motorCount > 1) {
                double minMean = Double.MAX_VALUE, maxMean = 0;
                for (int m = 0; m < motorCount; m++) {
                    double mean = throttleSum[m] / f.mainCount;
                    if (mean < minMean) minMean = mean;
                    if (mean > maxMean) maxMean = mean;
                }
                motorImbalance = maxMean - minMean;
                if (motorImbalance > MOTOR_IMBALANCE) {
                    int worst = 0;
                    for (int m = 1; m < motorCount; m++) {
                        if (throttleSum[m] > throttleSum[worst]) worst = m;
                    }
                    flagOnce(anomalyCodes, anomalies, "motor-imbalance",
                            "Ο κινητήρας " + (worst + 1) + " μεσοσταθμικά στο " + String.format("%.0f", maxMean * 100)
                                    + "% ενώ ο πιο ήσυχος στο " + String.format("%.0f", minMean * 100)
                                    + "% — έλεγξε ζυγοστάθμιση έλικας, λυγισμένο άξονα ή μαλακή βάση");
                }
            }
        }
        if (gyroClipped > 0) {
            flagOnce(anomalyCodes, anomalies, "gyro-clipping", gyroClipped + " δείγματα gyro χτύπησαν το όριο του αισθητήρα");
        }
        for (Event ev : f.events) {
            if ("disarm".equals(ev.name)) {
                Object reason = ev.get("reasonName");
                if (reason != null && !List.of("SWITCH", "STICKS", "ARMING_DISABLED").contains(reason.toString())) {
                    flagOnce(anomalyCodes, anomalies, "unexpected-disarm", "Ο flight controller αυτο-απενεργοποιήθηκε: " + reason);
                }
            }
        }
        if (!logEnded && !f.stats.reachedLogEnd) {
            flagOnce(anomalyCodes, anomalies, "truncated", "Το log δεν έχει marker τέλους — κόπηκε το ρεύμα ή βγήκε η κάρτα νωρίς");
        }

        if (!anomalies.isEmpty()) {
            findings.add("--- Ανώμαλες εντολές σε αυτή την πτήση ---");
            for (String a : anomalies) findings.add("  [i] " + a);
        }

        double score = 100;
        if (durationS < 20) score -= 15;
        score -= Stats.clamp(dropoutPct, 0, 30) * 0.6;
        score -= f.stats.corruptFrames > 0 ? Math.min(15, f.stats.corruptFrames * 0.05) : 0;
        score -= f.stats.resyncs > 0 ? Math.min(10, f.stats.resyncs) : 0;
        if (vPerCellMin > 0 && vPerCellMin < CELL_MIN_V) score -= 15;
        if (anomalyCodes.contains("impact")) score -= 10;
        if (anomalyCodes.contains("low-rssi")) score -= 10;
        if (anomalyCodes.contains("unexpected-disarm")) score -= 15;
        if (gyroClipped > 0) score -= 5;
        if (!f.stats.reachedLogEnd) score -= 5;
        score = Stats.clamp(score, 0, 100);

        String verdict;
        if (score >= 85) verdict = "Υγιής πτήση χωρίς σοβαρές εντολές.";
        else if (score >= 65) verdict = "Πετάει, αλλά υπάρχουν θέματα να κοιτάξεις στα ευρήματα.";
        else verdict = "Πρόβλημα σε αυτή την πτήση — διάβασε τα ευρήματα κάτω.";

        return new AnalysisResult(score, verdict, findings);
    }

    private static void addGps(FlightData f, List<String> findings) {
        if (!f.hasGpsField("GPS_coord[0]")) return;
        double maxDist = 0;
        double maxSpeed = 0;
        long maxAlt = Long.MIN_VALUE;
        long maxSats = 0;
        for (int i = 0; i < f.gpsCount; i++) {
            long speed = f.gpsValue(i, "GPS_speed");
            double kmh = speed * 0.036;
            if (kmh > maxSpeed) maxSpeed = kmh;
            long alt = f.gpsValue(i, "GPS_altitude");
            if (alt != Long.MIN_VALUE && alt > maxAlt) maxAlt = alt;
            long sats = f.gpsValue(i, "GPS_numSat");
            if (sats > maxSats) maxSats = sats;
            if (f.hasHome) {
                double lat = f.gpsValue(i, "GPS_coord[0]") / 1e7;
                double lon = f.gpsValue(i, "GPS_coord[1]") / 1e7;
                double homeLat = Integer.toUnsignedLong(f.gpsHome[0]) / 1e7;
                double homeLon = Integer.toUnsignedLong(f.gpsHome[1]) / 1e7;
                double d = Stats.haversineMeters(homeLat, homeLon, lat, lon);
                if (d > maxDist) maxDist = d;
            }
        }
        findings.add(String.format("GPS: max %.1f km/h | max υψόμετρο %d m | max απόσταση %.0f m | max %d sats",
                maxSpeed, maxAlt == Long.MIN_VALUE ? 0 : maxAlt, maxDist, maxSats));
    }

    private static int detectCells(Header h) {
        int[] limits = h.cellVoltageLimits();
        int maxCell = limits != null ? limits[2] : 430;
        int ref = h.vbatRef();
        if (ref > 0) {
            return Math.min(8, ref / maxCell + 1);
        }
        return 0;
    }

    private static void flagOnce(List<String> codes, List<String> messages, String code, String message) {
        if (codes.contains(code)) return;
        codes.add(code);
        messages.add(message);
    }

    static int countNumbered(FlightData f, String base) {
        int n = 0;
        while (f.hasMainField(base + "[" + n + "]")) n++;
        return n;
    }
}