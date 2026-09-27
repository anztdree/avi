package com.avi.assistant;

import android.content.Context;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;

/**
 * KunciSapa — kumpulan template DTW untuk FRASA SAPAAN pemilik
 * ("Hai AVI"), direkam 3 kali saat kalibrasi.
 *
 * Kecocokan = jarak DTW terbaik antara ucapan berjalan dan salah satu
 * template (semakin KECIL semakin mirip). Ambang sengaja longgar:
 * peran utama penjagaan ada di ProfilSuara; DTW memastikan yang
 * diucapkan memang sapaan kunci, bukan sekadar omongan apa saja.
 *
 * Disimpan biner filesDir/kunci_sapa.avi — lokal, tanpa unggah.
 */
public final class KunciSapa {

    /** Ambang DTW mode Lembut (OR dengan skor pembicara).
     *  Dikalibrasi ulang lewat uji JVM: pemilik 3,8-4,4 • asing 7,5-8,2
     *  (skala lama 0,40 terbukti salah skala sepuluh kali lipat). */
    public static final double BATAS_LEMBUT = 5.4;
    /** Ambang DTW mode Ketat (AND dengan skor pembicara). */
    public static final double BATAS_KETAT = 4.7;

    public final double[][][] template;   // [take][frame][13]

    private static final String AJI = "AVIK";
    private static final int VERSI = 1;

    private KunciSapa(double[][][] t) { template = t; }

    public static File berkas(Context c) {
        return new File(c.getFilesDir(), "kunci_sapa.avi");
    }

    public static boolean ada(Context c) {
        File f = berkas(c);
        return f.isFile() && f.length() > 100;
    }

    // ============================= bangun ===============================

    /** Bangun dari daftar take PCM 16 kHz (diharapkan 3 take). */
    public static KunciSapa bangun(ArrayList<short[]> take) {
        ArrayList<double[][]> mf = new ArrayList<>();
        for (short[] ucap : take) {
            if (ucap == null || ucap.length < Dsp.FRAME + Dsp.HOP * 5) continue;
            double[][] m = Dsp.mfcc(ucap, 0, ucap.length);
            if (m.length >= 6) mf.add(m);
        }
        if (mf.isEmpty()) return null;
        double[][][] t = mf.toArray(new double[mf.size()][][]);
        return new KunciSapa(t);
    }

    // =========================== pencocokan =============================

    /** Jarak DTW terbaik (kecil = mirip) antara potongan PCM dan template. */
    public double dtwTerbaik(short[] pcm, int dari, int panjang) {
        if (pcm == null || panjang < Dsp.FRAME) return 9.9;
        double[][] q = Dsp.mfcc(pcm, dari, panjang);
        if (q.length < 6) return 9.9;
        double terbaik = 9.9;
        for (double[][] t : template) {
            double d = Dsp.dtw(q, t);
            if (d < terbaik) terbaik = d;
        }
        return terbaik;
    }

    // ============================= biner ================================

    public boolean simpan(Context c) {
        try (DataOutputStream o = new DataOutputStream(new FileOutputStream(berkas(c)))) {
            o.writeBytes(AJI);
            o.writeInt(VERSI);
            o.writeInt(template.length);
            for (double[][] t : template) {
                o.writeInt(t.length);
                for (double[] frame : t) {
                    for (int k = 0; k < Dsp.NMCC; k++) o.writeFloat((float) frame[k]);
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static KunciSapa muat(Context c) {
        if (!ada(c)) return null;
        try (DataInputStream in = new DataInputStream(new FileInputStream(berkas(c)))) {
            byte[] m = new byte[4];
            in.readFully(m);
            if (!new String(m, "US-ASCII").equals(AJI)) return null;
            if (in.readInt() != VERSI) return null;
            int n = in.readInt();
            if (n <= 0 || n > 8) return null;
            double[][][] t = new double[n][][];
            for (int i = 0; i < n; i++) {
                int nFrame = in.readInt();
                if (nFrame <= 0 || nFrame > 2000) return null;
                t[i] = new double[nFrame][Dsp.NMCC];
                for (int f = 0; f < nFrame; f++) {
                    for (int k = 0; k < Dsp.NMCC; k++) t[i][f][k] = in.readFloat();
                }
            }
            return new KunciSapa(t);
        } catch (Exception e) {
            return null;
        }
    }
}
