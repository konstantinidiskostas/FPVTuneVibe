package com.example.fpv.analysis;

public final class Spectrum {

    private Spectrum() {
    }

    public static double[] window(int n) {
        double[] w = new double[n];
        for (int i = 0; i < n; i++) {
            w[i] = 0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / (n - 1.0));
        }
        return w;
    }

    public static double[] power(double[] x, double[] window) {
        int n = x.length;
        double[] re = new double[n];
        double[] im = new double[n];
        double mean = 0;
        for (double v : x) mean += v;
        mean /= n;
        for (int i = 0; i < n; i++) {
            re[i] = (x[i] - mean) * window[i];
        }
        fft(re, im);
        int half = n / 2;
        double[] p = new double[half];
        for (int k = 0; k < half; k++) {
            p[k] = re[k] * re[k] + im[k] * im[k];
        }
        return p;
    }

    private static void fft(double[] re, double[] im) {
        fftForward(re, im);
    }

    public static void ifft(double[] re, double[] im) {
        int n = re.length;
        for (int i = 0; i < n; i++) im[i] = -im[i];
        fftForward(re, im);
        double inv = 1.0 / n;
        for (int i = 0; i < n; i++) {
            re[i] *= inv;
            im[i] = -im[i] * inv;
        }
    }

    /**
     * Step-response estimation moved to {@link PidStepResponse}, which mirrors
     * the PID-Analyzer/PIDtoolbox algorithm (per-window Wiener deconvolution and
     * robust soft-mode averaging).
     */

    public static void fftForward(double[] re, double[] im) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                double tr = re[i];
                re[i] = re[j];
                re[j] = tr;
                tr = im[i];
                im[i] = im[j];
                im[j] = tr;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2.0 * Math.PI / len;
            double wRe = Math.cos(ang);
            double wIm = Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                double cRe = 1.0, cIm = 0.0;
                int half = len >> 1;
                for (int j = 0; j < half; j++) {
                    double uRe = re[i + j];
                    double uIm = im[i + j];
                    double vRe = re[i + j + half] * cRe - im[i + j + half] * cIm;
                    double vIm = re[i + j + half] * cIm + im[i + j + half] * cRe;
                    re[i + j] = uRe + vRe;
                    im[i + j] = uIm + vIm;
                    re[i + j + half] = uRe - vRe;
                    im[i + j + half] = uIm - vIm;
                    double nr = cRe * wRe - cIm * wIm;
                    cIm = cRe * wIm + cIm * wRe;
                    cRe = nr;
                }
            }
        }
    }
}