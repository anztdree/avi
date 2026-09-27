package com.avi.assistant;

/**
 * Dsp — kotak perangkat pengolahan sinyal suara milik AVI.
 * Murni Java, tanpa dependensi sedikit pun (hukum proyek): FFT radix-2
 * buatan sendiri, jendela Hamming, filterbank mel, DCT-II (MFCC),
 * pelacak nada dasar via autokorelasi, normalisasi cepaan (CMN), dan
 * DTW pita Sakoe-Chiba.
 *
 * Dipakai ProfilSuara (sidik jari suara pemilik), KunciSapa (template
 * frasa sapaan), GerbangSapa (penjaga pintu Mode Live) dan
 * KalibrasiActivity (pendaftaran suara pemilik).
 */
public final class Dsp {

    private Dsp() {}

    public static final int SR = 16000;    // laju sampel kerja (Hz)
    public static final int FRAME = 400;   // 25 ms
    public static final int HOP = 160;     // 10 ms
    public static final int FFT = 512;     // >= FRAME, pangkat dua
    public static final int NMEL = 26;     // pita mel
    public static final int NMCC = 13;     // koefisien MFCC (c1..c13)

    private static double[] hamming;
    private static float[][] mel;          // [NMEL][FFT/2+1]
    private static double[][] dct;         // [NMCC][NMEL]

    // =============================== FFT ===============================

    /** FFT radix-2 di tempat — panjang array harus pangkat dua. */
    public static void fft(double[] re, double[] im) {
        int n = re.length;
        // penataan ulang bit
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j |= bit;
            if (i < j) {
                double t = re[i]; re[i] = re[j]; re[j] = t;
                t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2.0 * Math.PI / len;
            double wRe = Math.cos(ang), wIm = Math.sin(ang);
            int setengah = len >> 1;
            for (int i = 0; i < n; i += len) {
                double curRe = 1.0, curIm = 0.0;
                for (int k = 0; k < setengah; k++) {
                    int a = i + k, b = a + setengah;
                    double ure = re[b] * curRe - im[b] * curIm;
                    double uim = re[b] * curIm + im[b] * curRe;
                    re[b] = re[a] - ure; im[b] = im[a] - uim;
                    re[a] += ure;        im[a] += uim;
                    double nRe = curRe * wRe - curIm * wIm;
                    curIm = curRe * wIm + curIm * wRe;
                    curRe = nRe;
                }
            }
        }
    }

    // ============================== MFCC ===============================

    private static synchronized void siapTabel() {
        if (hamming != null) return;
        hamming = new double[FRAME];
        for (int i = 0; i < FRAME; i++) {
            hamming[i] = 0.54 - 0.46 * Math.cos(2.0 * Math.PI * i / (FRAME - 1));
        }
        // filterbank mel: segitiga 60 Hz .. 7600 Hz pada skala mel
        double melMin = keMel(60.0), melMax = keMel(7600.0);
        int nBin = FFT / 2 + 1;
        double[] titik = new double[NMEL + 2];
        for (int i = 0; i < NMEL + 2; i++) {
            titik[i] = keHz(melMin + (melMax - melMin) * i / (NMEL + 1));
        }
        mel = new float[NMEL][nBin];
        for (int m = 0; m < NMEL; m++) {
            double lo = titik[m], mid = titik[m + 1], hi = titik[m + 2];
            for (int b = 0; b < nBin; b++) {
                double f = (double) b * SR / FFT;
                double w = 0;
                if (f >= lo && f <= hi) {
                    w = (f <= mid) ? (f - lo) / (mid - lo + 1e-9)
                                   : (hi - f) / (hi - mid + 1e-9);
                }
                mel[m][b] = (float) w;
            }
        }
        // tabel DCT-II (c1..c13) — dihitung sekali seumur proses
        dct = new double[NMCC][NMEL];
        for (int k = 0; k < NMCC; k++) {
            for (int n = 0; n < NMEL; n++) {
                dct[k][n] = Math.cos(Math.PI * (k + 1) * (n + 0.5) / NMEL);
            }
        }
    }

    private static double keMel(double f) { return 2595.0 * Math.log10(1.0 + f / 700.0); }
    private static double keHz(double m)  { return 700.0 * (Math.pow(10.0, m / 2595.0) - 1.0); }

    /**
     * MFCC dari potongan PCM 16 kHz → [jumlahFrame][13], SUDAH dinormalisasi
     * cepaan (CMN: rata-rata per koefisien dikurangkan) supaya tahan beda
     * kenyaringan & beda ruangan. c0 sengaja dibuang karena hanya membawa
     * level — identitas pembicara ada pada bentuk spektrumnya.
     */
    public static double[][] mfcc(short[] pcm, int dari, int panjang) {
        siapTabel();
        int jmlFrame = Math.max(0, (panjang - FRAME) / HOP + 1);
        double[][] keluar = new double[jmlFrame][NMCC];
        if (jmlFrame == 0) return keluar;
        double[] re = new double[FFT], im = new double[FFT];
        double[] pow = new double[FFT / 2 + 1];
        double[] logMel = new double[NMEL];
        int nBin = FFT / 2 + 1;
        int akhir = dari + panjang;
        for (int f = 0; f < jmlFrame; f++) {
            int awal = dari + f * HOP;
            double sblm = 0;
            for (int i = 0; i < FFT; i++) {
                double v = (i < FRAME && awal + i < akhir) ? pcm[awal + i] / 32768.0 : 0.0;
                double pe = v - 0.97 * sblm;   // pra-penekanan (pre-emphasis)
                sblm = v;
                // jendela Hamming panjang FRAME; sisanya nol pengisi sampai FFT
                re[i] = (i < FRAME) ? pe * hamming[i] : 0.0;
                im[i] = 0.0;
            }
            fft(re, im);
            for (int b = 0; b < nBin; b++) pow[b] = re[b] * re[b] + im[b] * im[b];
            for (int m = 0; m < NMEL; m++) {
                double e = 0;
                float[] baris = mel[m];
                for (int b = 0; b < nBin; b++) if (baris[b] > 0f) e += baris[b] * pow[b];
                logMel[m] = Math.log(e + 1e-10);
            }
            double[] bar = keluar[f];
            for (int k = 0; k < NMCC; k++) {
                double s = 0;
                double[] dctBar = dct[k];
                for (int n = 0; n < NMEL; n++) s += logMel[n] * dctBar[n];
                bar[k] = s;
            }
        }
        // normalisasi cepaan (CMN) per koefisien
        for (int k = 0; k < NMCC; k++) {
            double mu = 0;
            for (int f = 0; f < jmlFrame; f++) mu += keluar[f][k];
            mu /= jmlFrame;
            for (int f = 0; f < jmlFrame; f++) keluar[f][k] -= mu;
        }
        return keluar;
    }

    // ============================== PITCH ==============================

    public static final int P_FRAME = 480;   // 30 ms
    public static final int P_HOP = 160;
    private static final int LAG_MIN = 40;    // 400 Hz
    private static final int LAG_MAX = 266;   // ±60 Hz

    /**
     * Nada dasar (F0) per frame via autokorelasi ternormalisasi.
     * Keluaran [frame][2]: [0] = Hz (0 bila tidak bersuara),
     * [1] = kejelasan (clarity) 0..1.
     */
    public static double[][] pitch(short[] pcm, int dari, int panjang) {
        int jml = Math.max(0, (panjang - P_FRAME) / P_HOP + 1);
        double[][] keluar = new double[jml][2];
        double[] buf = new double[P_FRAME];
        int akhir = dari + panjang;
        for (int f = 0; f < jml; f++) {
            int awal = dari + f * P_HOP;
            double mu = 0;
            for (int i = 0; i < P_FRAME; i++) {
                double v = (awal + i < akhir) ? pcm[awal + i] / 32768.0 : 0.0;
                buf[i] = v;
                mu += v;
            }
            mu /= P_FRAME;
            double r0 = 0;
            for (int i = 0; i < P_FRAME; i++) {
                buf[i] -= mu;
                r0 += buf[i] * buf[i];
            }
            if (r0 < 1e-7) continue;
            double terbaik = 0;
            int lagTerbaik = 0;
            for (int lag = LAG_MIN; lag <= LAG_MAX && lag < P_FRAME; lag++) {
                double s = 0;
                for (int i = 0; i < P_FRAME - lag; i++) s += buf[i] * buf[i + lag];
                double nr = s / r0;
                if (nr > terbaik) { terbaik = nr; lagTerbaik = lag; }
            }
            if (lagTerbaik > 0 && terbaik > 0.45) {
                keluar[f][0] = (double) SR / lagTerbaik;
                keluar[f][1] = terbaik;
            }
        }
        return keluar;
    }

    // =============================== DTW ===============================

    /**
     * Jarak DTW ternormalisasi antar urutan fitur MFCC (pita Sakoe-Chiba
     * ±25%). Semakin KECIL semakin mirip frasa.
     */
    public static double dtw(double[][] a, double[][] b) {
        int n = a.length, m = b.length;
        if (n == 0 || m == 0) return 9.9;
        int pita = Math.max(12, Math.max(n, m) / 4);
        double[][] d = new double[n + 1][m + 1];
        for (int i = 0; i <= n; i++) java.util.Arrays.fill(d[i], Double.POSITIVE_INFINITY);
        d[0][0] = 0;
        for (int i = 1; i <= n; i++) {
            int lo = Math.max(1, i - pita), hi = Math.min(m, i + pita);
            for (int j = lo; j <= hi; j++) {
                double c = 0;
                for (int k = 0; k < NMCC; k++) {
                    double e = a[i - 1][k] - b[j - 1][k];
                    c += e * e;
                }
                c = Math.sqrt(c);
                double min = d[i - 1][j];
                if (d[i][j - 1] < min) min = d[i][j - 1];
                if (d[i - 1][j - 1] < min) min = d[i - 1][j - 1];
                d[i][j] = c + min;
            }
        }
        if (Double.isInfinite(d[n][m])) return 9.9;
        return d[n][m] / (n + m);
    }

    // ============================== util ===============================

    /** dBFS sebuah blok sampel (rata-rata kuadrat). */
    public static double db(short[] blok, int dari, int n) {
        double j = 0;
        for (int i = 0; i < n; i++) {
            double v = blok[dari + i] / 32768.0;
            j += v * v;
        }
        return 20.0 * Math.log10(Math.sqrt(j / Math.max(1, n)) + 1e-9);
    }

    /** Klem ke rentang 0..1. */
    public static double klem01(double v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }
}
