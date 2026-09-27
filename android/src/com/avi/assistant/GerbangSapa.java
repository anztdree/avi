package com.avi.assistant;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * GerbangSapa — penjaga pintu sesi Mode Live ala "Voice Match".
 *
 * Kapan dipakai: hanya saat SESI DIBUKA (LiveEngine.mulai), SEBELUM
 * SpeechRecognizer dibuka — mikrofon hanya boleh dimiliki satu pihak;
 * gerbang SELALU melepas AudioRecord sebelum recognizer menyala
 * (pelajaran dari KalibrasiActivity b11).
 *
 * Alur per percobaan: tunggu onset suara (VAD berbasis ambang ruangan
 * hasil kalibrasi) → potong ucapan saat hening sejenak (±0,5 dtk — b15:
 * dipendekkan dari 0,75 dtk agar verifikasi terasa instan) atau batas
 * 3,5 dtk → hitung skor pembicara (ProfilSuara) + skor frasa
 * (KunciSapa/DTW) → putuskan.
 *
 * Mode (pref "kal_mode"):
 *   lembut (bawaan) — lolos bila pembicara cocok ATAU frasa sangat mirip;
 *                     gagal 3x tetap melayani dengan catatan.
 *   ketat           — harus KEDUANYA cocok; gagal 3x = AVI menolak & tidur.
 *   mati            — gerbang tidak pernah menyala.
 *
 * Batas jujur: SpeechRecognizer Android tidak membagikan audio mentah,
 * sehingga verifikasi akustik terjadi di PINTU (sapaan) — pola yang sama
 * dengan "Voice Match" milik Google (cek di kata bangun, bukan tiap kata).
 */
public final class GerbangSapa {

    /** Peristiwa gerbang — semua dipanggil di thread utama. */
    public interface Panggilan {
        void menunggu(int kes, int maks);   // "ucapkan Hai AVI" ke-N
        void hasil(boolean lolos, int persen, String pesan);
    }

    public static final int MAKS_COBA = 3;

    private static final int BLOK = 512;               // 32 ms @16 kHz
    private static final long TUNGGU_ONSET_MS = 8000;
    private static final long MAKS_UCAP_MS = 3500;
    private static final long HENING_TUTUP_MS = 500;   // b15: 750→500 (instan)
    private static final long POST_DELAY_MS = 120;     // pastikan mic sudah lepas

    private final Context ctx;
    private final Panggilan p;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean jalan = false;
    private Thread t;

    public GerbangSapa(Context context, Panggilan panggilan) {
        ctx = context.getApplicationContext();
        p = panggilan;
    }

    /** Gerbang menyala hanya bila mode ≠ mati dan data kalibrasi lengkap. */
    public static boolean aktif(Context c) {
        return !"mati".equals(mode(c)) && ProfilSuara.ada(c) && KunciSapa.ada(c);
    }

    /** Mode gerbang: "mati" / "lembut" (bawaan) / "ketat". */
    public static String mode(Context c) {
        return AviBrain.pref(c).getString("kal_mode", "lembut");
    }

    // ============================ siklus hidup ============================

    public synchronized void mulai() {
        if (jalan) return;
        jalan = true;
        t = new Thread(this::loop, "avi-gerbang");
        t.start();
    }

    public synchronized void hentikan() {
        jalan = false;
        if (t != null) t.interrupt();
        t = null;
    }

    public boolean hidup() { return jalan; }

    // ============================ buka mikrofon ============================

    /**
     * Buka AudioRecord 16 kHz secara TAHAN BANTING: dua sumber dicoba
     * (VOICE_RECOGNITION → MIC), tiap sumber dua kali dengan jeda —
     * mikrofon yang sempat dipegang layanan hotword/aplikasi lain sempat
     * membutuhkan waktu lepas. Null = benar-benar gagal (pesan ke UI).
     */
    public static AudioRecord bukaMikrofon() {
        int[] sumber = { MediaRecorder.AudioSource.VOICE_RECOGNITION,
                         MediaRecorder.AudioSource.MIC };
        for (int coba = 0; coba < 4; coba++) {
            try {
                int minBuf = AudioRecord.getMinBufferSize(Dsp.SR,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                if (minBuf <= 0) minBuf = 8192;
                AudioRecord ar = new AudioRecord(sumber[coba % sumber.length],
                        Dsp.SR, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, Math.max(minBuf, 8192));
                if (ar.getState() == AudioRecord.STATE_INITIALIZED) return ar;
                try { ar.release(); } catch (Exception ignored) {}
            } catch (Throwable ignored) { }
            try { Thread.sleep(450); } catch (InterruptedException e) { return null; }
        }
        return null;
    }

    // ================================ loop ================================

    private void loop() {
        AudioRecord ar = null;
        boolean selesaiKirim = false;
        try {
            ar = bukaMikrofon();
            if (ar == null) {
                kirimHasil(false, 0, "Mikrofon tidak bisa dibuka — tutup "
                        + "aplikasi lain yang memakai mikrofon, lalu coba lagi.");
                selesaiKirim = true;
                return;
            }
            ar.startRecording();
            float lantai = AviBrain.pref(ctx).getFloat("kal_lantai_db", -55f);
            double ambang = lantai + 8.0;
            short[] blok = new short[BLOK];
            int persenAkhir = 0;

            for (int kes = 1; kes <= MAKS_COBA && jalan; kes++) {
                final int kesIni = kes;
                ui.post(() -> { if (jalan) p.menunggu(kesIni, MAKS_COBA); });

                short[] ucap = rekamUcapan(ar, blok, ambang);
                if (!jalan) return;

                ProfilSuara prof = ProfilSuara.muat(ctx);
                KunciSapa kunci = KunciSapa.muat(ctx);
                if (prof == null || kunci == null) {
                    kirimHasil(false, 0, "Data kalibrasi tidak terbaca — daftar ulang di Pengaturan.");
                    selesaiKirim = true;
                    return;
                }

                ProfilSuara u = ProfilSuara.bangun(ucap, 0, ucap.length, lantai);
                if (u == null) continue;   // tak terdengar / terlalu pelan → coba lagi

                int persen = prof.persen(u);
                persenAkhir = persen;
                double d = kunci.dtwTerbaik(ucap, 0, ucap.length);
                boolean ketat = "ketat".equals(mode(ctx));
                boolean lolos;
                String pesan;
                if (ketat) {
                    lolos = prof.cocok(u, true) && d <= KunciSapa.BATAS_KETAT;
                    pesan = lolos ? "Pemilik terverifikasi" : "Verifikasi gagal";
                } else {
                    lolos = prof.cocok(u, false) || d <= KunciSapa.BATAS_LEMBUT;
                    pesan = lolos ? "Suara dikenali" : "Suara kurang cocok";
                }
                if (lolos || kes >= MAKS_COBA) {
                    kirimHasil(lolos, persen, pesan);
                    selesaiKirim = true;
                    return;
                }
            }
            if (jalan && !selesaiKirim) {
                kirimHasil(false, persenAkhir, "Tidak terdengar sapaan yang jelas.");
            }
        } catch (Throwable e) {
            kirimHasil(false, 0, "Gerbang terganggu: " + e.getClass().getSimpleName());
        } finally {
            if (ar != null) {
                try { ar.stop(); } catch (Exception ignored) {}
                try { ar.release(); } catch (Exception ignored) {}
            }
        }
    }

    // ============================ rekam ucapan ============================

    /**
     * Tunggu onset suara (maks 8 dtk) → kumpulkan sampai hening sejenak
     * / batas maksimum. Kembalikan PCM 16 kHz (kosong bila tak ada suara).
     * Thread pemanggil kelihatan tetap memegang AudioRecord — pemanggil
     * loop() melepasnya di finally SEBELUM hasil diproses UI.
     */
    private short[] rekamUcapan(AudioRecord ar, short[] blok, double ambang) throws Exception {
        long t0 = System.currentTimeMillis();
        int keras = 0;
        while (jalan && System.currentTimeMillis() - t0 < TUNGGU_ONSET_MS) {
            int n = ar.read(blok, 0, blok.length);
            if (n <= 0) continue;
            if (Dsp.db(blok, 0, n) > ambang) {
                if (++keras >= 3) break;
            } else {
                keras = 0;
            }
        }
        if (!jalan || keras < 3) return new short[0];

        ByteArrayOutputStream buf = new ByteArrayOutputStream(BLOK * 2 * 128);
        long mulai = System.currentTimeMillis();
        long suaraTerakhir = System.currentTimeMillis();
        while (jalan && System.currentTimeMillis() - mulai < MAKS_UCAP_MS) {
            int n = ar.read(blok, 0, blok.length);
            if (n <= 0) continue;
            for (int i = 0; i < n; i++) {
                int s = blok[i];
                buf.write(s & 0xFF);
                buf.write((s >> 8) & 0xFF);
            }
            long kini = System.currentTimeMillis();
            if (Dsp.db(blok, 0, n) > ambang - 2.0) suaraTerakhir = kini;
            if (kini - mulai > 900 && kini - suaraTerakhir > HENING_TUTUP_MS) break;
        }
        byte[] b = buf.toByteArray();
        short[] pcm = new short[b.length / 2];
        ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm);
        return pcm;
    }

    // ============================== kirim hasil ===========================

    private void kirimHasil(final boolean lolos, final int persen, final String pesan) {
        // sengaja diberi jeda: AudioRecord pasti sudah dilepas di finally
        // sebelum LiveEngine membuka SpeechRecognizer.
        ui.postDelayed(() -> p.hasil(lolos, persen, pesan), POST_DELAY_MS);
    }
}
