package com.avi.assistant;

import android.content.Context;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;

/**
 * ProfilSuara — SIDIK JARI AKUSTIK pemilik AVI (kalibrasi v2).
 *
 * Isinya supervector berdimensi 28:
 *   [ rata-rata MFCC bersuara (13) , deviasi MFCC (13, bobot 0,5),
 *     nada dasar ternormalisasi (2) ]
 * Identitas pembicara dibandingkan dengan KEMIRIPAN KOSINUS antar
 * supervector — murah, deterministik, dan cukup memisahkan suara
 * pemilik dari anggota keluarga serumah (bukan alat forensik).
 *
 * Disimpan biner filesDir/profil_suara.avi — lokal di ponsel, tanpa
 * unggah, tanpa server (aturan privasi proyek).
 */
public final class ProfilSuara {

    public static final int NDIM = 28;   // 13 rata + 13 deviasi + 2 nada

    public final float[] v = new float[NDIM];
    public float pitchMedian = 0f, pitchRendah = 0f, pitchTinggi = 0f;
    public int frameSuara = 0;
    public float bicaraDb = -60f;

    private static final String AJI = "AVIP";
    private static final int VERSI = 2;   // v2: metrik skor gabungan (Dsp mfcc diperbaiki)

    public static File berkas(Context c) {
        return new File(c.getFilesDir(), "profil_suara.avi");
    }

    public static boolean ada(Context c) {
        File f = berkas(c);
        return f.isFile() && f.length() > 80;
    }

    // =========================== bangun profil ===========================

    /**
     * Bangun profil dari potongan PCM 16 kHz. 'lantaiDb' = dBFS ruangan
     * hasil kalibrasi (frame di bawah lantai+8 dB dianggap senyap).
     * Mengembalikan null bila ucapan terlalu pendek / terlalu pelan.
     */
    public static ProfilSuara bangun(short[] pcm, int dari, int panjang, float lantaiDb) {
        if (pcm == null || panjang < Dsp.FRAME + Dsp.HOP) return null;
        double[][] mf = Dsp.mfcc(pcm, dari, panjang);
        if (mf.length < 12) return null;

        // pilih frame bersuara (energi > lantai + 8 dB)
        ArrayList<Integer> idx = new ArrayList<>();
        ArrayList<Double> dbs = new ArrayList<>();
        for (int f = 0; f < mf.length; f++) {
            int awal = dari + f * Dsp.HOP;
            int n = Math.min(Dsp.FRAME, dari + panjang - awal);
            if (n <= 0) continue;
            double db = Dsp.db(pcm, awal, n);
            if (db > lantaiDb + 8.0) {
                idx.add(f);
                dbs.add(db);
            }
        }
        if (idx.size() < 12) return null;

        ProfilSuara p = new ProfilSuara();
        p.frameSuara = idx.size();

        // rata-rata & deviasi MFCC hanya pada frame bersuara
        for (int k = 0; k < Dsp.NMCC; k++) {
            double mu = 0;
            for (int f : idx) mu += mf[f][k];
            mu /= idx.size();
            double va = 0;
            for (int f : idx) { double e = mf[f][k] - mu; va += e * e; }
            va = Math.sqrt(va / idx.size());
            p.v[k] = (float) mu;
            p.v[Dsp.NMCC + k] = (float) (va * 0.5);   // deviasi diberi bobot 0,5
        }

        // nada dasar — cukup 4 detik pertama (hemat tenaga, hasil sama)
        double[][] pit = Dsp.pitch(pcm, dari, Math.min(panjang, 64000));
        ArrayList<Double> hz = new ArrayList<>();
        for (int f = 0; f < pit.length; f++) {
            if (pit[f][0] <= 0) continue;   // frame tak bersuara
            int awal = dari + f * Dsp.P_HOP;
            int n = Math.min(Dsp.P_FRAME, dari + panjang - awal);
            if (n <= 0) continue;
            if (Dsp.db(pcm, awal, n) > lantaiDb + 8.0) hz.add(pit[f][0]);
        }
        if (hz.size() >= 6) {
            Collections.sort(hz);
            p.pitchMedian = (float) (double) hz.get(hz.size() / 2);
            p.pitchRendah = (float) (double) hz.get(Math.max(0, (int) (hz.size() * 0.10)));
            p.pitchTinggi = (float) (double) hz.get(Math.min(hz.size() - 1, (int) (hz.size() * 0.90)));
            // dua dimensi nada dalam supervector (diberi penguat 2x)
            p.v[26] = (float) ((p.pitchMedian - 140.0) / 70.0 * 2.0);
            p.v[27] = (float) ((p.pitchTinggi - p.pitchRendah) / 120.0 * 2.0);
        }

        // kenyaringan bicara = persentil-85 (anti outlier)
        if (!dbs.isEmpty()) {
            Collections.sort(dbs);
            int i = (int) Math.floor(dbs.size() * 0.85);
            if (i >= dbs.size()) i = dbs.size() - 1;
            p.bicaraDb = (float) (double) dbs.get(i);
        }
        return p;
    }

    // ============================ pencocokan =============================

    /** Kemiripan kosinus supervector 0..~1 antara profil ini dan 'u'. */
    public double kosinusDengan(ProfilSuara u) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < NDIM; i++) {
            dot += v[i] * u.v[i];
            na += v[i] * v[i];
            nb += u.v[i] * u.v[i];
        }
        if (na < 1e-12 || nb < 1e-12) return 0;
        return dot / Math.sqrt(na * nb);
    }

    /** Kosinus rentang dimensi [dari, ke) — tanpa pergeseran. */
    private double kosinusRentang(ProfilSuara u, int dari, int ke) {
        double dot = 0, na = 0, nb = 0;
        for (int i = dari; i < ke; i++) {
            dot += v[i] * u.v[i]; na += v[i] * v[i]; nb += u.v[i] * u.v[i];
        }
        if (na < 1e-12 || nb < 1e-12) return 0;
        return dot / Math.sqrt(na * nb);
    }

    /** Kosinis 13 dimensi rata-MFCC yang dimidalirisasi (bisa negatif —
     *  pembeda bentuk spektrum yang jujur, tanpa gelembung "semua positif"). */
    private double kosinusSpektrum(ProfilSuara u) {
        double ma = 0, mb = 0;
        for (int i = 0; i < Dsp.NMCC; i++) { ma += v[i]; mb += u.v[i]; }
        ma /= Dsp.NMCC; mb /= Dsp.NMCC;
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < Dsp.NMCC; i++) {
            double x = v[i] - ma, y = u.v[i] - mb;
            dot += x * y; na += x * x; nb += y * y;
        }
        if (na < 1e-12 || nb < 1e-12) return 0;
        return dot / Math.sqrt(na * nb);
    }

    /** Kemiripan nada dasar 0..1 — 1 = nada sama; 0 bila beda ≥ 2,2 oktaf.
     *  Nada dasar adalah pembeda terkuat antar anggota keluarga (terukur). */
    private double simNada(ProfilSuara u) {
        if (u.pitchMedian <= 0 || pitchMedian <= 0) return 0.75;   // netral
        double rasio = Math.abs(Math.log(u.pitchMedian / pitchMedian))
                / Math.log(2.2);
        return Dsp.klem01(1.0 - rasio);
    }

    /**
     * SKOR GABUNGAN 0..1 — hasil kalibrasi uji JVM (12 pasang pembicara):
     * pemilik 0,97-0,99 • asing 0,71-0,74. Bobot: bentuk spektrum 50%,
     * nada dasar 35% (terkuat), dinamika bicara 15%.
     */
    public double skor(ProfilSuara u) {
        return 0.50 * kosinusSpektrum(u) + 0.35 * simNada(u)
                + 0.15 * kosinusRentang(u, Dsp.NMCC, 2 * Dsp.NMCC);
    }

    /** Keputusan kecocokan pembicara (dipakai GerbangSapa).
     *  Ambang dari uji: pemilik ≥ 0,97 saat tenang; 0,84 memberi ruang
     *  untuk letih/jarak mic; 0,90 untuk mode ketat. */
    public boolean cocok(ProfilSuara u, boolean ketat) {
        return skor(u) >= (ketat ? 0.90 : 0.84);
    }

    /** Persen kenyamanan 0..100 untuk ditampilkan ke pemilik. */
    public int persen(ProfilSuara u) {
        return (int) Math.round(Dsp.klem01((skor(u) - 0.50) / 0.48) * 100);
    }

    // ============================= biner =================================

    public boolean simpan(Context c) {
        try (DataOutputStream o = new DataOutputStream(new FileOutputStream(berkas(c)))) {
            o.writeBytes(AJI);
            o.writeInt(VERSI);
            o.writeInt(NDIM);
            for (int i = 0; i < NDIM; i++) o.writeFloat(v[i]);
            o.writeFloat(pitchMedian);
            o.writeFloat(pitchRendah);
            o.writeFloat(pitchTinggi);
            o.writeInt(frameSuara);
            o.writeFloat(bicaraDb);
            o.writeLong(System.currentTimeMillis());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static ProfilSuara muat(Context c) {
        if (!ada(c)) return null;
        try (DataInputStream in = new DataInputStream(new FileInputStream(berkas(c)))) {
            byte[] m = new byte[4];
            in.readFully(m);
            if (!new String(m, "US-ASCII").equals(AJI)) return null;
            if (in.readInt() != VERSI) return null;
            if (in.readInt() != NDIM) return null;
            ProfilSuara p = new ProfilSuara();
            for (int i = 0; i < NDIM; i++) p.v[i] = in.readFloat();
            p.pitchMedian = in.readFloat();
            p.pitchRendah = in.readFloat();
            p.pitchTinggi = in.readFloat();
            p.frameSuara = in.readInt();
            p.bicaraDb = in.readFloat();
            in.readLong();   // waktu pendaftaran (disimpan juga di pref)
            return p;
        } catch (Exception e) {
            return null;
        }
    }
}
