package com.example.fpv.analysis;

import com.example.fpv.blackbox.Event;
import com.example.fpv.blackbox.FlightData;

import java.util.ArrayList;
import java.util.List;

/**
 * Computes the extended flight metrics that the AI report needs:
 * battery, loop rates, motor telemetry, per-regime breakdown,
 * frequency-domain noise bands and filter effectiveness.
 */
public final class ReportMetrics {
    public double fs;
    public double gyroScale = 1;

    // battery
    public boolean hasVbat;
    public int cells;
    public double vStart, vEnd, vMin, restPerCell, minPerCell, sagPerCell;

    // motors
    public boolean hasMotor;
    public double[] motorPct = new double[4];
    public double motorImbalancePct;
    public boolean hasErpm;
    public double[] rpmAvg = new double[4];
    public double rpmMin, rpmMax, rotationHz, bladePassHz;
    public int motors;

    // per-regime (low<30, mid 30-70, high>70 throttle %)
    public boolean hasThrottle;
    public double avgThrottlePct;
    public double[] noiseByThr = new double[3];
    public double[] trkErrByThr = new double[3];
    public double[] thrByThr = new double[3];

    // frequency-domain bands per axis: [propwash 0-50, motor 50-150, frame 150-350, high 350+]
    public boolean hasBands;
    public double[][] bands = new double[3][4];

    // filter effectiveness
    public boolean hasFilter;
    public double noiseRawRms, noiseFiltRms, filterReductionPct;

    // context
    public double logCompletePct = 100;
    public int corruptFrames;
    public boolean reachedLogEnd;
    public String disarmReasons = "-";

    public static ReportMetrics compute(FlightData f, int blades) {
        ReportMetrics m = new ReportMetrics();
        m.fs = f.frameRateHz();
        m.gyroScale = gyroScale(f);
        int n = f.mainCount;
        if (n < 100 || m.fs < 100) return m;

        m.cells = 1;
        computeBattery(f, m, n);
        m.hasThrottle = f.hasMainField("rcCommand[3]");
        computeMotors(f, m, n, blades);
        computeRegimes(f, m, n);
        computeBands(f, m, n);
        computeFilter(f, m, n);
        computeContext(f, m, n);
        return m;
    }

    private static void computeBattery(FlightData f, ReportMetrics m, int n) {
        if (!f.hasMainField("vbatLatest")) return;
        double maxV = 0, minV = Double.MAX_VALUE, first = 0, last = 0;
        boolean gotFirst = false;
        for (int i = 0; i < n; i++) {
            double v = f.mainValue(i, "vbatLatest") / 100.0;
            if (v < 1) continue;
            if (!gotFirst) { first = v; gotFirst = true; }
            last = v;
            if (v > maxV) maxV = v;
            if (v < minV) minV = v;
        }
        if (!gotFirst) return;
        m.hasVbat = true;
        m.cells = (int) Math.max(1, Math.round(maxV / 4.2));
        m.vStart = first;
        m.vEnd = last;
        m.vMin = minV;
        m.restPerCell = maxV / m.cells;
        m.minPerCell = minV / m.cells;
        if (m.hasThrottle) {
            double restSum = 0; int restN = 0;
            for (int i = 0; i < n; i++) {
                double t = f.mainValue(i, "rcCommand[3]");
                double v = f.mainValue(i, "vbatLatest") / 100.0;
                if (v < 1) continue;
                if (t < 1150) { restSum += v; restN++; }
            }
            if (restN > 0) m.restPerCell = restSum / restN / m.cells;
        }
        m.sagPerCell = Math.max(0, m.restPerCell - m.minPerCell);
    }

    private static void computeMotors(FlightData f, ReportMetrics m, int n, int blades) {
        m.motors = countNumbered(f, "motor");
        if (m.motors == 0) return;
        m.hasMotor = true;
        double[] sum = new double[m.motors];
        int cnt = 0;
        for (int i = 0; i < n; i++) {
            if (m.hasThrottle) {
                double t = f.mainValue(i, "rcCommand[3]");
                if (t < 1200 || t > 1700) continue;
            }
            for (int k = 0; k < m.motors; k++) sum[k] += f.mainValue(i, "motor[" + k + "]");
            cnt++;
        }
        if (cnt == 0) cnt = 1;
        double mean = 0;
        for (int k = 0; k < m.motors; k++) { sum[k] /= cnt; mean += sum[k]; }
        mean /= m.motors;
        double worst = 0;
        for (int k = 0; k < m.motors; k++) {
            m.motorPct[k] = 100.0 * (sum[k] - 48) / (2047 - 48);
            worst = Math.max(worst, Math.abs(sum[k] - mean) / Math.max(1, mean) * 100);
        }
        m.motorImbalancePct = worst;

        int poles = 14;
        try {
            poles = Integer.parseInt(f.header.raw.getOrDefault("motor_poles", "14").trim());
        } catch (Exception ignored) {
        }
        int erpmN = countNumbered(f, "eRPM");
        if (erpmN > 0) {
            double[] esum = new double[erpmN];
            long[] ecnt = new long[erpmN];
            for (int i = 0; i < n; i++) {
                for (int k = 0; k < erpmN; k++) {
                    long e = f.mainValueUnsigned(i, "eRPM[" + k + "]");
                    if (e > 0) { esum[k] += e; ecnt[k]++; }
                }
            }
            double minR = Double.MAX_VALUE, maxR = 0, all = 0; int allN = 0;
            for (int k = 0; k < erpmN; k++) {
                double e = ecnt[k] > 0 ? esum[k] / ecnt[k] : 0;
                double rpm = e * 200.0 / poles;
                m.rpmAvg[k] = rpm;
                if (e > 0) { minR = Math.min(minR, rpm); maxR = Math.max(maxR, rpm); all += rpm; allN++; }
            }
            if (allN > 0) {
                m.hasErpm = true;
                m.rpmMin = minR;
                m.rpmMax = maxR;
                double meanRpm = all / allN;
                m.rotationHz = meanRpm / 60.0;
                m.bladePassHz = m.rotationHz * Math.max(1, blades);
            }
        }
    }

    private static void computeRegimes(FlightData f, ReportMetrics m, int n) {
        if (!m.hasThrottle || !f.hasMainField("gyroADC[0]")) return;
        double[] buf = new double[3 * 8];
        double[] acc = new double[3];
        double[] noise = new double[3];
        long[] cnt = new long[3];
        double[] thrSumBin = new double[3];
        double thrSum = 0;
        for (int i = 0; i < n; i++) {
            double thr = (f.mainValue(i, "rcCommand[3]") - 1000.0) / 10.0;
            double thrClamped = Math.max(0, Math.min(100, thr));
            thrSum += thrClamped;
            int bin = thr < 30 ? 0 : thr < 70 ? 1 : 2;
            thrSumBin[bin] += thrClamped;
            double e = 0;
            for (int a = 0; a < 3; a++) {
                String nm = "gyroADC[" + a + "]";
                double v = f.hasMainField(nm) ? f.mainValue(i, nm) * m.gyroScale : 0;
                int p = a * 8 + (i % 8);
                acc[a] += v - buf[p];
                buf[p] = v;
                double hp = v - acc[a] / 8.0;
                e += hp * hp;
            }
            noise[bin] += Math.sqrt(e / 3.0);
            cnt[bin]++;
        }
        m.avgThrottlePct = thrSum / n;
        for (int b = 0; b < 3; b++) {
            m.noiseByThr[b] = cnt[b] > 0 ? noise[b] / cnt[b] : 0;
            m.thrByThr[b] = cnt[b] > 0 ? thrSumBin[b] / cnt[b] : 0;
        }

        double[] terr = new double[3];
        long[] tcnt = new long[3];
        for (int i = 0; i < n; i++) {
            double thr = (f.mainValue(i, "rcCommand[3]") - 1000.0) / 10.0;
            int bin = thr < 30 ? 0 : thr < 70 ? 1 : 2;
            double e = 0;
            for (int a = 0; a < 3; a++) {
                if (!f.hasMainField("gyroADC[" + a + "]") || !f.hasMainField("setpoint[" + a + "]")) continue;
                double sp = f.mainValue(i, "setpoint[" + a + "]");
                double g = f.mainValue(i, "gyroADC[" + a + "]") * m.gyroScale;
                e += (sp - g) * (sp - g);
            }
            terr[bin] += Math.sqrt(e);
            tcnt[bin]++;
        }
        for (int b = 0; b < 3; b++) m.trkErrByThr[b] = tcnt[b] > 0 ? terr[b] / tcnt[b] : 0;
    }

    private static void computeBands(FlightData f, ReportMetrics m, int n) {
        if (!f.hasMainField("gyroADC[0]")) return;
        int win = 256;
        if (win > n) return;
        double[] w = Spectrum.window(win);
        int half = win / 2;
        double[][] accP = new double[3][half];
        int windows = 0;
        for (int start = 0; start + win <= n; start += win) {
            for (int a = 0; a < 3; a++) {
                String nm = "gyroADC[" + a + "]";
                if (!f.hasMainField(nm)) continue;
                double[] x = new double[win];
                for (int i = 0; i < win; i++) x[i] = f.mainValue(start + i, nm) * m.gyroScale;
                double[] p = Spectrum.power(x, w);
                for (int k = 0; k < half; k++) accP[a][k] += p[k];
            }
            windows++;
        }
        if (windows == 0) return;
        m.hasBands = true;
        double binHz = m.fs / win;
        double meanW2 = 0;
        for (int i = 0; i < win; i++) meanW2 += w[i] * w[i];
        meanW2 /= win;
        double norm = 2.0 / (win * (double) win * meanW2);
        double[][] edges = {{0, 50}, {50, 150}, {150, 350}, {350, m.fs / 2}};
        for (int a = 0; a < 3; a++) {
            for (int b = 0; b < 4; b++) {
                int k0 = Math.max(0, (int) Math.floor(edges[b][0] / binHz));
                int k1 = Math.min(half - 1, (int) Math.ceil(edges[b][1] / binHz));
                double sum = 0;
                for (int k = k0; k <= k1; k++) sum += accP[a][k] / windows;
                m.bands[a][b] = Math.sqrt(Math.max(0, sum * norm));
            }
        }
    }

    private static void computeFilter(FlightData f, ReportMetrics m, int n) {
        if (!f.hasMainField("gyroADC[0]") || !f.hasMainField("gyroUnfilt[0]")) return;
        double raw = hpRms(f, "gyroUnfilt", m.gyroScale, n);
        double filt = hpRms(f, "gyroADC", m.gyroScale, n);
        if (raw <= 1e-9) return;
        m.hasFilter = true;
        m.noiseRawRms = raw;
        m.noiseFiltRms = filt;
        m.filterReductionPct = (1.0 - filt / raw) * 100.0;
    }

    private static double hpRms(FlightData f, String base, double scale, int n) {
        double[] buf = new double[3 * 8];
        double[] acc = new double[3];
        double sum = 0;
        for (int i = 0; i < n; i++) {
            double e = 0;
            for (int a = 0; a < 3; a++) {
                String nm = base + "[" + a + "]";
                double v = f.hasMainField(nm) ? f.mainValue(i, nm) * scale : 0;
                int p = a * 8 + (i % 8);
                acc[a] += v - buf[p];
                buf[p] = v;
                double hp = v - acc[a] / 8.0;
                e += hp * hp;
            }
            sum += e;
        }
        return Math.sqrt(sum / (3.0 * n));
    }

    private static void computeContext(FlightData f, ReportMetrics m, int n) {
        m.reachedLogEnd = f.stats.reachedLogEnd;
        m.corruptFrames = f.stats.corruptFrames;
        int total = f.stats.totalFrames();
        if (total > 0) m.logCompletePct = 100.0 * (1.0 - (double) m.corruptFrames / total);
        List<String> reasons = new ArrayList<>();
        for (Event e : f.events) {
            if (e.code == 15) {
                Object r = e.get("reasonName");
                String s = r == null ? "unknown" : r.toString();
                if (!reasons.contains(s)) reasons.add(s);
            }
        }
        if (!reasons.isEmpty()) m.disarmReasons = String.join(", ", reasons);
    }

    private static double gyroScale(FlightData f) {
        double s = f.header.gyroScale();
        if (!(Float.NaN != s && s > 1e-6 && s <= 1.0)) s = 2000.0 / 32768.0;
        return s;
    }

    private static int countNumbered(FlightData f, String base) {
        int c = 0;
        while (f.hasMainField(base + "[" + c + "]")) c++;
        return c;
    }
}
