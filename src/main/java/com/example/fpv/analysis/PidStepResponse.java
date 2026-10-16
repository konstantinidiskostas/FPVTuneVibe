package com.example.fpv.analysis;

/**
 * Step-response estimation from setpoint -> gyro using the frequency-domain
 * method popularised by Plasmatree PID-Analyzer / PIDtoolbox (and used by the
 * fpv-drone.info tuning guide).
 *
 * <p>Per 1&nbsp;s Hann window the plant transfer function is recovered with a
 * Wiener deconvolution (regularised at the high end), the impulse response is
 * integrated to a step, and the per-window steps are combined with a robust
 * "soft mode" (histogram) average instead of a plain mean. Windows where the
 * input is too small are ignored. This produces the characteristic fast rise
 * with a small overshoot settling near 1.0.</p>
 */
public final class PidStepResponse {

    public double[] low;
    public double[] high;
    public double dtMs;
    public int lowWindows;
    public int highWindows;
    public int totalWindows;

    private static final double FRAMELEN = 1.0;
    private static final double RESPLEN = 0.5;
    private static final double CUTFREQ = 25.0;
    private static final int SUPERPOS = 16;
    private static final double THRESHOLD = 500.0;
    private static final double MIN_INPUT = 20.0;

    private PidStepResponse() {
    }

    public static PidStepResponse compute(double[] sp, double[] gy, double fs) {
        if (sp == null || gy == null || sp.length != gy.length) return null;
        int flen = (int) (FRAMELEN * fs);
        int rlen = (int) (RESPLEN * fs);
        if (flen < 8 || rlen < 8 || sp.length < flen * 2) return null;
        int shift = Math.max(1, flen / SUPERPOS);
        int wins = sp.length / shift - SUPERPOS;
        if (wins < 1) return null;

        double[] win = Spectrum.window(flen);
        int pad = 1024 - (flen % 1024);
        if (pad == 1024) pad = 0;
        int fftlen = flen + pad;

        double[] sn = buildSn(fftlen, fs);

        double[][] steps = new double[wins][];
        double[] maxIn = new double[wins];
        double[] xr = new double[fftlen];
        double[] xi = new double[fftlen];
        double[] yr = new double[fftlen];
        double[] yi = new double[fftlen];
        double[] dr = new double[fftlen];
        double[] di = new double[fftlen];

        for (int w = 0; w < wins; w++) {
            int base = w * shift;
            java.util.Arrays.fill(xr, 0);
            java.util.Arrays.fill(xi, 0);
            java.util.Arrays.fill(yr, 0);
            java.util.Arrays.fill(yi, 0);
            double m = 0;
            for (int i = 0; i < flen; i++) {
                double v = sp[base + i] * win[i];
                xr[i] = v;
                double av = Math.abs(v);
                if (av > m) m = av;
                yr[i] = gy[base + i] * win[i];
            }
            maxIn[w] = m;
            Spectrum.fftForward(xr, xi);
            Spectrum.fftForward(yr, yi);
            for (int k = 0; k < fftlen; k++) {
                double hr = xr[k], hi = xi[k], gr = yr[k], gi = yi[k];
                double nr = gr * hr + gi * hi;
                double ni = gi * hr - gr * hi;
                double den = hr * hr + hi * hi + 1.0 / sn[k];
                dr[k] = nr / den;
                di[k] = ni / den;
            }
            Spectrum.ifft(dr, di);
            double[] st = new double[rlen];
            double acc = 0;
            for (int i = 0; i < rlen; i++) {
                acc += dr[i];
                st[i] = acc;
            }
            steps[w] = st;
        }

        boolean[] toolow = new boolean[wins];
        boolean[] lowMask = new boolean[wins];
        boolean[] highMask = new boolean[wins];
        int lowCount = 0, highCount = 0, allCount = 0;
        for (int w = 0; w < wins; w++) {
            toolow[w] = maxIn[w] > MIN_INPUT;
            if (toolow[w]) {
                allCount++;
                if (maxIn[w] <= THRESHOLD) {
                    lowMask[w] = true;
                    lowCount++;
                } else {
                    highMask[w] = true;
                    highCount++;
                }
            }
        }

        PidStepResponse res = new PidStepResponse();
        res.dtMs = 1000.0 / fs;
        res.totalWindows = allCount;
        res.highWindows = highCount;

        if (lowCount >= 10) {
            res.low = softMode(steps, lowMask, rlen);
            res.lowWindows = lowCount;
        } else {
            res.low = softMode(steps, toolow, rlen);
            res.lowWindows = allCount;
        }
        if (highCount >= 10) {
            res.high = softMode(steps, highMask, rlen);
        }
        if (res.low == null && res.high == null) return null;
        return res;
    }

    /** Robust soft-mode histogram average of per-window step responses. */
    private static double[] softMode(double[][] steps, boolean[] mask, int rlen) {
        double lo = -1.5, hi = 3.5;
        int bins = 1000;
        double binw = (hi - lo) / bins;
        double[][] hist = new double[bins][rlen];
        int used = 0;
        for (int w = 0; w < steps.length; w++) {
            if (!mask[w]) continue;
            used++;
            double[] st = steps[w];
            for (int t = 0; t < rlen; t++) {
                double v = st[t];
                if (v < lo || v > hi) continue;
                int b = (int) ((v - lo) / binw);
                if (b < 0) b = 0;
                if (b >= bins) b = bins - 1;
                hist[b][t] += 1.0;
            }
        }
        if (used == 0) return null;
        double[][] sm = gaussAxis0(hist, 7.0, bins, rlen);
        for (int t = 0; t < rlen; t++) {
            double mx = 0;
            for (int b = 0; b < bins; b++) if (sm[b][t] > mx) mx = sm[b][t];
            if (mx > 0) for (int b = 0; b < bins; b++) sm[b][t] /= mx;
        }
        double[] out = new double[rlen];
        double stepY = (hi - lo) / (bins - 1.0);
        for (int t = 0; t < rlen; t++) {
            double num = 0, den = 0;
            for (int b = 0; b < bins; b++) {
                double ww = sm[b][t] * sm[b][t];
                num += (lo + b * stepY) * ww;
                den += ww;
            }
            out[t] = den > 0 ? num / den : 0;
        }
        return out;
    }

    private static double[] buildSn(int n, double fs) {
        double[] sn = new double[n];
        double dt = 1.0 / fs;
        for (int k = 0; k < n; k++) {
            int kk = k <= n / 2 ? k : k - n;
            double f = Math.abs(kk) / (n * dt);
            sn[k] = Math.max(CUTFREQ - 1e-9, Math.min(f, CUTFREQ));
        }
        toMask(sn);
        double lenLpf = 0;
        for (int k = 0; k < n; k++) lenLpf += 1.0 - sn[k];
        double sigma = lenLpf / 6.0;
        sn = gauss1dReflect(sn, sigma);
        toMask(sn);
        for (int k = 0; k < n; k++) sn[k] = 10.0 * (-sn[k] + 1.0 + 1e-9);
        return sn;
    }

    private static void toMask(double[] a) {
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        for (double v : a) {
            if (v < min) min = v;
            if (v > max) max = v;
        }
        double range = max - min;
        if (range <= 0) {
            java.util.Arrays.fill(a, 0);
            return;
        }
        for (int i = 0; i < a.length; i++) a[i] = (a[i] - min) / range;
    }

    private static double[] gauss1dReflect(double[] x, double sigma) {
        int n = x.length;
        if (sigma <= 0) return x.clone();
        int lw = (int) (4.0 * sigma + 0.5);
        if (lw < 1) return x.clone();
        double[] k = new double[2 * lw + 1];
        double sum = 0;
        for (int i = -lw; i <= lw; i++) {
            double v = Math.exp(-0.5 * (i / sigma) * (i / sigma));
            k[i + lw] = v;
            sum += v;
        }
        for (int i = 0; i < k.length; i++) k[i] /= sum;
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            double acc = 0;
            for (int j = -lw; j <= lw; j++) {
                int si = i + j;
                if (si < 0) si = -si;
                else if (si >= n) si = 2 * (n - 1) - si;
                if (si < 0 || si >= n) continue;
                acc += x[si] * k[j + lw];
            }
            out[i] = acc;
        }
        return out;
    }

    private static double[][] gaussAxis0(double[][] h, double sigma, int rows, int cols) {
        int lw = (int) (4.0 * sigma + 0.5);
        double[] k = new double[2 * lw + 1];
        double sum = 0;
        for (int i = -lw; i <= lw; i++) {
            double v = Math.exp(-0.5 * (i / sigma) * (i / sigma));
            k[i + lw] = v;
            sum += v;
        }
        for (int i = 0; i < k.length; i++) k[i] /= sum;
        double[][] out = new double[rows][cols];
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                double acc = 0;
                for (int j = -lw; j <= lw; j++) {
                    int sr = r + j;
                    if (sr < 0 || sr >= rows) continue;
                    acc += h[sr][c] * k[j + lw];
                }
                out[r][c] = acc;
            }
        }
        return out;
    }
}
