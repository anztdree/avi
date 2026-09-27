package com.avi.assistant;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;

import java.util.ArrayList;
import java.util.Locale;

/**
 * Mesin Mode Live AVI — inti percakapan bebas mengalir ala Gemini Live.
 * Bicara → jawab → bicara lagi, tanpa menekan apa pun.
 *
 * Loop: MENDENGARKAN (STT + transkrip real-time) → BERPIKIR (AI streaming)
 * → BICARA (teks muncul bertahap + TTS per kalimat) → jeda ±300 ms
 * → MENDENGARKAN lagi.
 *
 * POLA IDLE SIRI/GA (koreksi pemilik): setelah beberapa detik tanpa
 * aktivitas, AVI TIDUR — sesi ditutup dengan sopan, bukan terus-menerus
 * mendengarkan. Hening ±5 detik (batas internal recognizer) setelah
 * giliran terakhir = sinyal tidur. Sebelum tidur AVI pamit singkat bila
 * sempat terjadi percakapan; kalau belum ada ucapan sama sekali, langsung
 * tidur tanpa suara.
 *
 * Anti-echo (riset Coval 2026, arsitektur client-only): mikrofon TIDAK
 * PERNAH menyala saat TTS berbicara; dibuka lagi ±300 ms setelah kalimat
 * terakhir selesai agar gema meluruh dulu.
 * Barge-in pragmatis: sentuh orb saat AVI bicara = langsung diam & mendengar.
 * Seluruh ucapan & jawaban tersimpan sebagai transkrip di riwayat chat
 * (AviBrain.tanyaStream yang menyimpan).
 *
 * GERBANG SAPA (kalibrasi v2): bila profil suara pemilik terdaftar dan
 * gerbang aktif (Pengaturan → Kalibrasi suara), sesi DIBUKA lewat
 * GerbangSapa dulu — pemilik mengucapkan "Hai AVI" dan suaranya
 * diverifikasi (MFCC+DTW, murni Java) SEBELUM SpeechRecognizer dibuka;
 * mikrofon dipastikan lepas dulu. Berlaku otomatis untuk kedua jalur
 * (AviSession overlay & OrbitAssistActivity/LiveActivity) karena semua
 * memakai mesin ini. Menyentuh orb saat gerbang = pintu dibuka pemilik.
 *
 * Mesin ini netral UI: dipakai LiveActivity (layar penuh) dan AviSession
 * (overlay transparan asisten perangkat). Host hanya menerima peristiwa
 * lewat Pendengar — semua callback datang di thread utama.
 */
public class LiveEngine {

    /** Peristiwa mesin untuk host UI — semua dipanggil di thread utama. */
    public interface Pendengar {
        void keadaan(int keadaanOrb);          // lihat OrbView.SIAP dst.
        void status(String teks);              // baris status kecil
        void transkripAnda(String teks);       // ucapan user (parsial/final)
        void teksAvi(String teks);             // jawaban AVI (mengalir)
        void rms(float rmsdb);                 // kerasnya suara (animasi orb)
        void tetidur();                        // idle → host menutup sesi
        /** Giliran tanya-jawab SELESAI & tersimpan di riwayat bersama —
         *  host menggambar ulang papan pesan (aturan papan tunggal). */
        default void giliranBeres() {}
    }

    private static final String ID_PAMIT = "pamit";
    private static final String TEKS_PAMIT =
            "Baik, AVI pamit dulu. Panggil AVI lagi kapan saja.";

    // ==== TTS hangat lintas sesi (perbaikan kelambatan b14) ====
 // Memuat TextToSpeech dari nol butuh 1-2 detik di HP low-RAM — dulunya
    // SETIAP lembar dibuka membangunnya lagi. Sekarang satu instance
    // dipegang proses: dipanaskan sejak orb muncul, dipakai bareng semua
    // sesi, TIDAK dimatikan saat lembar ditutup (mikrofon tetap ikut siklus).
    private static final Object KUNCI_TTS = new Object();
    private static TextToSpeech ttsBersama;
    private static boolean ttsBersamaSiap = false;
    // b16 anti-suara-hilang: instance TTS yang rusak (proses mesin suara
    // dibunuh sistem di HP low-RAM, atau init gagal) TIDAK BOLEH dipakai
    // terus — dulu jadi "zombie" sunyi: speak() dipanggil, tidak ada suara,
    // tidak ada pesan. Kini ditandai rusak lalu dibangun ulang otomatis.
    private static boolean ttsBersamaRusak = false;
    private static int gagalBangun = 0;      // batas percobaan bangun ulang

    // ==== cache verifikasi gerbang sapa (b14, DIPANGKAS di b15) ====
    // b14: cache 15 menit demi kecepatan - tapi pemilik melapor kalibrasi
    // terasa "pajangan" karena gerbang nyaris tidak pernah menyala. b15:
    // dipangkas jadi 3 menit - hampir SETIAP bangun diverifikasi (pola
    // Google: tiap wake dicek), obrolan lanjutan dalam 3 menit tetap instan.
    private static volatile long lolosSampaiMs = 0L;
    private static final long USIA_VERIFIKASI_MS = 3L * 60L * 1000L;

    private final Context ctx;
    private final Pendengar p;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private SpeechRecognizer pengenal;
    private TextToSpeech tts;
    private GerbangSapa gerbang;
    private boolean ttsSiap = false;
    private boolean bahasaDiberiTahu = false;  // pesan "suara Indonesia belum ada" sekali per sesi
    private boolean hidup = false;
    private boolean sudahTidur = false;
    private boolean punyaPercakapan = false;
    private int salahDengar = 0;
    private int hitungHening = 0;         // berapa laporan hening berturut-turut
    private int keadaan = OrbView.SIAP;

    // sinkronisasi loop: dua jalur harus beres (stream AI + antrean TTS)
    private int sesi = 0;                 // naik tiap giliran baru / barge-in
    private boolean streamSelesai = false;
    private boolean ttsSelesai = true;
    private int nomorUcap = 0;
    private String idUcapTerakhir = null;

    private final StringBuilder aliran = new StringBuilder(); // teks bersih sejauh ini
    private int sudahDiucap = 0;                              // batas index terucap

    public LiveEngine(Context context, Pendengar pendengar) {
        ctx = context.getApplicationContext();
        p = pendengar;
    }

    public boolean izinMicAda() {
        return ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    // ============================ siklus hidup ============================

    public void mulai() {
        hidup = true;
        siapkanTts();
        siapkanPengenal();
        if (!izinMicAda()) {
            p.status("Izin mikrofon belum ada — berikan lewat Pengaturan ponsel.");
            return;
        }
        if (GerbangSapa.aktif(ctx)
                && System.currentTimeMillis() - lolosSampaiMs > USIA_VERIFIKASI_MS) {
            jalankanGerbang();
            return;
        }
        jadwalMendengarkan(200);   // sudah terverifikasi / tanpa gerbang → cepat
    }

    public void hentikan() {
        hidup = false;
        handler.removeCallbacksAndMessages(null);
        if (gerbang != null) { gerbang.hentikan(); gerbang = null; }
        if (tts != null) {
            try { tts.stop(); } catch (Exception ignored) {}   // TTS hangat:
            tts = null;                                        // JANGAN shutdown
        }
        if (pengenal != null) {
            try { pengenal.destroy(); } catch (Exception ignored) {}
            pengenal = null;
        }
    }

    public int getKeadaan() { return keadaan; }

    private void setKeadaan(int k) {
        keadaan = k;
        p.keadaan(k);
    }

    // ============================ TTS (bicara) ============================

    private void siapkanTts() {
        synchronized (KUNCI_TTS) {
            // b16: instance rusak DIGANTI BARU, bukan dipangkuk terus
            if (ttsBersama != null && ttsBersamaRusak) {
                try { ttsBersama.shutdown(); } catch (Exception ignored) {}
                ttsBersama = null;
                ttsBersamaSiap = false;
                ttsBersamaRusak = false;
            }
            if (ttsBersama != null) {
                // reuse TTS hangat — tinggal pasang suara & pendengar giliran ini
                tts = ttsBersama;
                ttsSiap = ttsBersamaSiap;
                aturSuaraTts();
                if (!ttsSiap) tungguTtsHangat(0);
                return;
            }
            tts = new TextToSpeech(ctx, ok -> {
                boolean siap = ok == TextToSpeech.SUCCESS;
                synchronized (KUNCI_TTS) {
                    // simpan untuk SEMUA sesi berikutnya (tidak di-shutdown)
                    ttsBersama = tts;
                    ttsBersamaSiap = siap;
                    if (siap) gagalBangun = 0;
                    else ttsBersamaRusak = true;   // b16: jangan dipakai zombie
                }
                if (!siap) {
                    cobaBangunUlangTts();   // b16: dulu diam selamanya
                    return;
                }
                if (hidup) aturSuaraTts();
            });
        }
    }

    /** b16: init gagal → bangun ulang otomatis maks 3x dengan jeda;
     *  bila tetap gagal, pemilik DIBERITAHU (mode teks), bukan didiamkan. */
    private void cobaBangunUlangTts() {
        if (!hidup) return;
        if (gagalBangun >= 3) {
            p.status("Mesin suara gagal menyala — jawaban tampil sebagai teks, "
                    + AviBrain.namaPemilik(ctx) + ".");
            return;
        }
        gagalBangun++;
        handler.postDelayed(() -> { if (hidup) siapkanTts(); }, 1500);
    }

    /** TTS hangat masih init — tunggu sampai siap lalu pasang suara sesi ini. */
    private void tungguTtsHangat(final int putaran) {
        if (putaran > 12) {
            // b16: dulu menyerah DALAM DIAM — sekarang pemilik diberi tahu
            if (hidup) p.status("Mesin suara lambat menyala — jawaban tampil "
                    + "sebagai teks dulu, " + AviBrain.namaPemilik(ctx) + ".");
            return;
        }
        handler.postDelayed(() -> {
            if (!hidup || tts == null || tts != ttsBersama) return;
            if (ttsBersamaSiap) {
                ttsSiap = true;
                aturSuaraTts();
            } else {
                tungguTtsHangat(putaran + 1);
            }
        }, 800);
    }

    /** Terapkan bahasa/suara/laju + pendengar giliran milik mesin INI. */
    private void aturSuaraTts() {
        if (tts == null) return;
        // b16: hasil setLanguage TIDAK diabaikan lagi — tanpa data suara
        // Indonesia (umum di HP itel/Transsion) dulu sintesis gagal senyap.
        boolean bahasaOke = false;
        try {
            bahasaOke = tts.setLanguage(new Locale("id", "ID"))
                    >= TextToSpeech.LANG_AVAILABLE;
        } catch (Exception ignored) {}
        if (!bahasaOke) {
            try {
                bahasaOke = tts.setLanguage(new Locale("id"))
                        >= TextToSpeech.LANG_AVAILABLE;
            } catch (Exception ignored) {}
        }
        if (!bahasaOke && !bahasaDiberiTahu) {
            bahasaDiberiTahu = true;
            p.status("Data suara Indonesia belum terpasang — AVI memakai "
                    + "suara bawaan ponsel.");
        }
        try {
            String namaSuara = AviBrain.pref(ctx).getString("tts_suara", "");
            if (!namaSuara.isEmpty() && tts.getVoices() != null) {
                for (Voice v : tts.getVoices()) {
                    if (namaSuara.equals(v.getName())) { tts.setVoice(v); break; }
                }
            }
        } catch (Exception ignored) {}
        try { tts.setSpeechRate(AviBrain.pref(ctx).getInt("tts_rate", 100) / 100f); }
        catch (Exception ignored) {}
        pasangPendengarTts();
    }

    /** b16: pendengar giliran WAJIB dipasang ulang oleh mesin yang sedang
     *  bicara — instance TTS dibagi lintas sesi, dan listener sesi lama
     *  bisa mencuri onDone sehingga sesi baru membeku setelah bicara. */
    private void pasangPendengarTts() {
        if (tts == null) return;
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) {}
            @Override public void onDone(String id) { ucapBeres(id); }
            @Override public void onError(String id) { ucapBeres(id); }
            @Override public void onError(String id, int kode) { ucapBeres(id); }
        });
    }

    /** Bangun TTS lebih awal — dipanggil saat panel/sesi asisten muncul
     *  supaya ketuk gelembung tidak menunggu inisialisasi mesin suara. */
    public static void panaskanTts(Context context) {
        synchronized (KUNCI_TTS) {
            if (ttsBersama != null && !ttsBersamaRusak) return;
            if (ttsBersama != null) {   // b16: ganti instance rusak
                try { ttsBersama.shutdown(); } catch (Exception ignored) {}
                ttsBersama = null;
                ttsBersamaSiap = false;
                ttsBersamaRusak = false;
            }
            Context app = context.getApplicationContext();
            final TextToSpeech[] wadah = new TextToSpeech[1];
            wadah[0] = new TextToSpeech(app, ok -> {
                synchronized (KUNCI_TTS) {
                    if (ttsBersama == wadah[0]) {
                        ttsBersamaSiap = ok == TextToSpeech.SUCCESS;
                    }
                }
            });
            ttsBersama = wadah[0];
            ttsBersamaSiap = false;
        }
    }

    private void ucapkan(String potongan) {
        String s = potongan.trim();
        if (s.isEmpty() || tts == null) return;
        if (!ttsSiap) {
            // b16: init mungkin BARU selesai setelah penantian — ambil keadaan
            // terkini; bila memang belum siap, jangan buang ucapan diam-diam
            // tanpa kabar (dulu: suara tidak keluar tanpa pesan apa pun).
            if (ttsBersamaSiap && tts == ttsBersama) {
                ttsSiap = true;
                aturSuaraTts();
            } else {
                return;
            }
        }
        nomorUcap++;
        idUcapTerakhir = "live" + sesi + "_" + nomorUcap;
        ttsSelesai = false;
        pasangPendengarTts();   // b16: pastikan giliran milik mesin ini
        int antre;
        try { antre = tts.speak(s, TextToSpeech.QUEUE_ADD, null, idUcapTerakhir); }
        catch (Exception e) { antre = -1; }
        if (antre < 0) {
            // b16: mesin suara mati di tengah jalan (proses TTS dibunuh
            // sistem) — dulu zombie sunyi sampai aplikasi ditutup; kini
            // ditandai rusak & dibangun ulang untuk kalimat berikutnya.
            synchronized (KUNCI_TTS) { ttsBersamaRusak = true; }
            ttsSiap = false;
            cobaBangunUlangTts();
        }
    }

    private void ucapBeres(String id) {
        handler.post(() -> {
            if (!hidup) return;
            if (ID_PAMIT.equals(id)) { tidurSekarang(); return; }
            if (id == null || !id.equals(idUcapTerakhir)) return;
            ttsSelesai = true;
            cobaLanjutDengar();
        });
    }

    /** Kedua jalur (stream AI + TTS) sudah beres → buka mikrofon lagi. */
    private void cobaLanjutDengar() {
        if (!hidup || sudahTidur) return;
        if (streamSelesai && ttsSelesai && keadaan == OrbView.BICARA) {
            jadwalMendengarkan(300);   // beri jeda agar gema TTS meluruh
        }
    }

    /** Barge-in: host menyentuh orb saat AVI bicara. */
    public void potongTts() {
        if (!hidup || sudahTidur) return;
        sesi++;                        // buang seluruh callback giliran lama
        if (tts != null) { try { tts.stop(); } catch (Exception ignored) {} }
        streamSelesai = true;
        ttsSelesai = true;
        p.transkripAnda("");
        p.teksAvi("");
        p.status("Dipotong — silakan bicara, " + AviBrain.namaPemilik(ctx));
        jadwalMendengarkan(120);
    }

    /** Lanjut mendengarkan bila sebelumnya dijeda (orb disentuh saat SIAP).
     *  Sentuhan pemilik di tengah gerbang = pintu langsung dibuka (dan
     *  dihitung sebagai verifikasi — pemilik jelas ada di depan layar). */
    public void dengarkanLagi() {
        if (!hidup || sudahTidur || keadaan != OrbView.SIAP) return;
        if (gerbang != null) { gerbang.hentikan(); gerbang = null; }
        lolosSampaiMs = System.currentTimeMillis();
        mulaiMendengarkan();
    }

    /** Jalur ketik (b19): pill "Tanya AVI…" pada panel asisten — setara
     *  hasil recognizer final, dipakai juga bila mic tidak tersedia.
     *  pengenal.cancel() TANPA callback (beda dgn stopListening yang
     *  memicu onResults kosong → bisa salah masuk jalur hening). */
    public void teksManual(String teks) {
        String t = teks == null ? "" : teks.trim();
        if (t.isEmpty() || !hidup || sudahTidur) return;
        if (pengenal != null) {
            try { pengenal.cancel(); } catch (Exception ignored) {}
        }
        hitungHening = 0;
        p.transkripAnda(t);
        ajukanKeAi(t);
    }

    // ==================== gerbang sapa (kalibrasi v2) ====================

    private void jalankanGerbang() {
        setKeadaan(OrbView.SIAP);
        p.status("Verifikasi suara — ucapkan \u201CHai AVI\u201D (sentuh titik = lewat)");
        p.transkripAnda("");
        p.teksAvi("");
        if (gerbang != null) gerbang.hentikan();
        gerbang = new GerbangSapa(ctx, new GerbangSapa.Panggilan() {
            @Override public void menunggu(int kes, int maks) {
                if (!hidup || sudahTidur) return;
                p.status("Verifikasi (coba " + kes + "/" + maks
                        + ") — ucapkan \u201CHai AVI\u201D");
            }
            @Override public void hasil(boolean lolos, int persen, String pesan) {
                if (!hidup || sudahTidur) { gerbang = null; return; }
                gerbang = null;
                if (lolos) {
                    lolosSampaiMs = System.currentTimeMillis();   // cache 15 menit
                    p.status("Dikenali, " + AviBrain.namaPemilik(ctx)
                            + " ✓ (" + persen + "%) — silakan bicara.");
                    jadwalMendengarkan(250);
                    return;
                }
                if ("ketat".equals(GerbangSapa.mode(ctx))) {
                    tolakAkses();
                    return;
                }
                // lembut: tetap melayani, tapi diberitahu
                p.status("Suara belum cocok penuh (" + persen + "%) — tetap "
                        + "saya layani, " + AviBrain.namaPemilik(ctx) + ".");
                jadwalMendengarkan(250);
            }
        });
        gerbang.mulai();
    }

    /** Mode ketat, 3× gagal: AVI menolak sopan lalu tidur. */
    private void tolakAkses() {
        setKeadaan(OrbView.SIAP);
        p.status("Maaf, saya hanya melayani " + AviBrain.namaPemilik(ctx) + ".");
        if (ttsSiap && tts != null) {
            tts.speak("Maaf, saya hanya melayani " + AviBrain.namaPemilik(ctx)
                    + ". AVI pamit dulu.", TextToSpeech.QUEUE_ADD, null, ID_PAMIT);
        }
        handler.postDelayed(this::tidurSekarang, 4500);
    }

    // ====================== streaming AI (berpikir) ======================

    private void ajukanKeAi(String teks) {
        punyaPercakapan = true;
        sesi++;
        final int sesiIni = sesi;
        streamSelesai = false;
        ttsSelesai = true;
        nomorUcap = 0;
        idUcapTerakhir = null;
        aliran.setLength(0);
        sudahDiucap = 0;

        setKeadaan(OrbView.BERPIKIR);
        p.status("AVI sedang berpikir…");
        p.teksAvi("");

        AviBrain.tanyaStream(ctx, teks, new AviBrain.StreamBalas() {
            @Override public void token(String teksSejauhIni) {
                if (!hidup || sesi != sesiIni) return;
                terimaToken(teksSejauhIni);
            }
            @Override public void selesai(String teksAkhir) {
                if (!hidup || sesi != sesiIni) return;
                streamBeres(teksAkhir);
            }
        });
    }

    private void terimaToken(String teksPenuh) {
        String bersih = AviBrain.buangTagAksi(teksPenuh);
        aliran.setLength(0);
        aliran.append(bersih);
        p.teksAvi(bersih);

        if (keadaan != OrbView.BICARA) {
            setKeadaan(OrbView.BICARA);
            p.status("AVI berbicara — sentuh orb untuk memotong");
        }

        // TTS per kalimat: potong di tanda baca akhir (min 40 karakter antar potong)
        while (true) {
            int potong = cariPotongan(aliran, sudahDiucap);
            if (potong < 0) break;
            String pot = aliran.substring(sudahDiucap, potong).trim();
            if (!pot.isEmpty()) ucapkan(pot);
            sudahDiucap = potong;
        }
    }

    private void streamBeres(String teksAkhir) {
        streamSelesai = true;
        String tampil = teksAkhir == null ? "" : teksAkhir.trim();
        if (tampil.isEmpty()) {
            tampil = "(balasan kosong dari model — coba ulangi, "
                    + AviBrain.namaPemilik(ctx) + ".)";
        }
        p.teksAvi(tampil);

        if (!ttsSiap) {                 // tanpa mesin TTS: lanjut langsung
            jadwalMendengarkan(250);
            p.giliranBeres();           // pasangan sudah masuk papan bersama
            return;
        }
        String sisa = aliran.length() > sudahDiucap
                ? aliran.substring(sudahDiucap).trim() : "";
        sudahDiucap = aliran.length();
        if (sisa.isEmpty()) {
            if (ttsSelesai) jadwalMendengarkan(300);   // antrean TTS sudah licin
            // else: tunggu onDone kalimat terakhir → cobaLanjutDengar()
        } else {
            ucapkan(sisa);             // onDone-nya akan memicu lanjut dengar
        }
        p.giliranBeres();              // riwayat bersama diperbarui → gambar ulang
    }

    /** Cari akhir kalimat setelah minimal 40 karakter dari posisi 'dari'. */
    private static int cariPotongan(StringBuilder s, int dari) {
        int mulaiCari = Math.max(dari, 0) + 40;
        for (int i = mulaiCari; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '.' || c == '!' || c == '?' || c == '\n') {
                if (i + 1 >= s.length() || Character.isWhitespace(s.charAt(i + 1))) {
                    return i + 1;
                }
            }
        }
        return -1;
    }

    // ==================== STT (mendengarkan) ====================

    private void siapkanPengenal() {
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
            p.status("Pengenalan suara tidak tersedia di ponsel ini.");
            return;
        }
        pengenal = SpeechRecognizer.createSpeechRecognizer(ctx);
        pengenal.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) { salahDengar = 0; }
            @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float rmsdB) { if (hidup) p.rms(rmsdB); }
            @Override public void onBufferReceived(byte[] buffer) {}
            @Override public void onEndOfSpeech() {}
            @Override public void onEvent(int eventType, Bundle params) {}

            @Override public void onPartialResults(Bundle parsial) {
                if (!hidup || keadaan != OrbView.MENDENGARKAN) return;
                ArrayList<String> daftar = parsial
                        .getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (daftar != null && !daftar.isEmpty()
                        && !daftar.get(0).trim().isEmpty()) {
                    p.transkripAnda(daftar.get(0));    // transkrip real-time
                }
            }

            @Override public void onResults(Bundle hasil) {
                if (!hidup) return;
                ArrayList<String> daftar = hasil
                        .getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                String teks = (daftar == null || daftar.isEmpty())
                        ? "" : daftar.get(0).trim();
                if (teks.isEmpty()) { jadwalMendengarkan(150); return; }
                p.transkripAnda(teks);
                hitungHening = 0;          // ada suara — penghitung hening direset
                ajukanKeAi(teks);
            }

            @Override public void onError(int error) {
                if (!hidup || sudahTidur) return;
                if (error == SpeechRecognizer.ERROR_NO_MATCH
                        || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                    // IDLE — pola Siri/GA: beberapa detik tanpa aktivitas, tidur.
                    // Durasi dipilih pemilik (Pengaturan → Mode Live); pengenal
                    // Android melaporkan hening tiap ±5 detik → dihitung per putaran.
                    int dtk = AviBrain.pref(ctx).getInt("live_hening", 8);
                    int putaran = Math.max(1, (dtk + 4) / 5);
                    if (++hitungHening < putaran) {
                        jadwalMendengarkan(150);   // masih dalam toleransi — dengar lagi
                        return;
                    }
                    hitungHening = 0;
                    if (!punyaPercakapan) { tidurSekarang(); return; }
                    mulaiPamit();
                    return;
                }
                if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                    bangunUlangPengenal(400);
                    return;
                }
                salahDengar++;
                if (salahDengar >= 12) {
                    // mikrofon memang bermasalah — tidur, jangan berputar tanpa akhir
                    p.status("Mikrofon bermasalah — AVI tidur dulu, "
                            + AviBrain.namaPemilik(ctx) + ".");
                    tidurSekarang();
                } else if (salahDengar >= 5) {
                    // jeda aman setelah galat beruntun, lalu coba lagi otomatis
                    p.status("AVI masih di sini — mendengarkan lagi sebentar lagi, "
                            + AviBrain.namaPemilik(ctx) + ".");
                    jadwalMendengarkan(1500);
                } else {
                    bangunUlangPengenal(600);
                }
            }
        });
    }

    private void mulaiMendengarkan() {
        if (!hidup || sudahTidur) return;
        if (pengenal == null) siapkanPengenal();
        if (pengenal == null) return;
        setKeadaan(OrbView.MENDENGARKAN);
        p.status("Mendengarkan… bicara saja, " + AviBrain.namaPemilik(ctx));
        Intent it = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        it.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        it.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "id-ID");
        it.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        it.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        try {
            pengenal.startListening(it);
        } catch (Exception e) {
            bangunUlangPengenal(500);
        }
    }

    private final Runnable pengingatDengar = this::mulaiMendengarkan;

    private void jadwalMendengarkan(int delayMs) {
        handler.removeCallbacks(pengingatDengar);
        handler.postDelayed(pengingatDengar, delayMs);
    }

    private void bangunUlangPengenal(int delayMs) {
        handler.postDelayed(() -> {
            if (!hidup || sudahTidur) return;
            if (pengenal != null) {
                try { pengenal.destroy(); } catch (Exception ignored) {}
                pengenal = null;
            }
            siapkanPengenal();
            mulaiMendengarkan();
        }, delayMs);
    }

    // ==================== idle → tidur (pola Siri/GA) ====================

    /** Hening ±5 detik setelah percakapan: pamit singkat, lalu tidur. */
    private void mulaiPamit() {
        if (sudahTidur) return;
        setKeadaan(OrbView.SIAP);
        p.status("AVI istirahat — sesi berakhir karena hening.");
        p.transkripAnda("");
        if (ttsSiap && tts != null) {
            tts.speak(TEKS_PAMIT, TextToSpeech.QUEUE_ADD, null, ID_PAMIT);
        }
        // pengaman: meski onDone TTS tidak datang, tetap tidur tepat waktu
        handler.postDelayed(this::tidurSekarang, 4500);
    }

    private void tidurSekarang() {
        if (sudahTidur) return;
        sudahTidur = true;
        p.tetidur();      // host yang menutup UI; host memanggil hentikan()
    }
}
