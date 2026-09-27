package com.avi.assistant;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

/**
 * KALIBRASI SUARA v3 — WIZARD pendaftaran suara pemilik ala "Voice Match".
 *
 * Permintaan pemilik (2026-09-25): "kalibrasi kok gk ditaruh wizard, aneh"
 * dan "katanya jangan terlalu panjang — 3 atau 4 kata, dan 3 atau 4 take
 * supaya AVI paham jelas". Maka:
 *
 *   Langkah 1/6  Selamat datang — tombol Mulai.
 *   Langkah 2/6  Kondisi ruangan (pemilik diam 1,2 dtk).
 *   Langkah 3/6  Baca frasa PENDEK 1: "Ibu masak nasi"  (3 kata, 3 take).
 *   Langkah 4/6  Baca frasa PENDEK 2: "Bola jatuh ke lantai" (4 kata, 3 take).
 *   Langkah 5/6  Kunci sapaan: "Hai AVI" (4 take — template DTW).
 *   Langkah 6/6  UJI TAMU — orang lain bicara, AVI HARUS MENOLAK.
 *                Bukti hidup bahwa kalibrasi bukan pajangan.
 *   Selesai      TERKUNCI: Reset ulang / Hapus saja. Gerbang otomatis
 *                KETAT (hanya pemilik dilayani) — bila pemilik belum
 *                memilih mode sendiri.
 *
 * Take gagal (terlalu pelan / terpotong) = ulang take itu saja. Profil
 * dibangun dari GABUNGAN semua take frasa (satu supervector, frame hening
 * otomatis dibuang oleh ProfilSuara.bangun). Satu sesi AudioRecord 16 kHz
 * per thread alur; mikrofon pasti lepas di finally. SpeechRecognizer tidak
 * dipakai di layar ini.
 */
public class KalibrasiActivity extends Activity {

    /** Frasa baca PENDEK (permintaan pemilik: 3-4 kata) — kaya fonem
     *  p/b/t/d/k/g/s/n/m/l + vokal penuh, kalimat sehari-hari. */
    private static final String FRASA1 = "Ibu masak nasi";        // 3 kata
    private static final String FRASA2 = "Bola jatuh ke lantai";  // 4 kata
    private static final String SAPAAN = "\u201CHai AVI\u201D";

    /** Jumlah take — permintaan pemilik: 3-4 supaya AVI paham jelas. */
    private static final int TAKE_FRASA = 3;   // tiap frasa baca
    private static final int TAKE_SAPA  = 4;   // sapaan kunci (DTW)

    private static final int BLOK = 512;
    private static final long RUANG_MS = 1200;

    private TextView tvJudul, tvNarasi, tvLangkah, tvInstruksi, tvFrasa, tvUmpan, tvDots;
    private ProgressBar pbLevel;
    private Button bUtama, bKedua;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean alurJalan = false;       // wizard utama (L2-L5)
    private volatile boolean tamuJalan = false;       // uji tamu (L6)
    private Thread alurThread, tamuThread;
    private Runnable tertunda;   // aksi setelah izin mikrofon diberikan
    private volatile boolean profilBaruTersimpan = false;
    /** Makna tombol utama: false = mulai wizard, true = rekam uji tamu.
     *  (Tombol TIDAK PERNAH diganti listener-nya — anti bug keadaan.) */
    private volatile boolean tombolTamu = false;

    @Override
    protected void attachBaseContext(Context baru) {
        super.attachBaseContext(AviBrain.terapkanTema(baru));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_kalibrasi);

        tvJudul    = findViewById(R.id.tvJudul);
        tvNarasi   = findViewById(R.id.tvNarasi);
        tvLangkah  = findViewById(R.id.tvLangkah);
        tvInstruksi= findViewById(R.id.tvInstruksi);
        tvFrasa    = findViewById(R.id.tvFrasa);
        tvUmpan    = findViewById(R.id.tvUmpan);
        tvDots     = findViewById(R.id.tvDots);
        pbLevel    = findViewById(R.id.pbLevel);
        bUtama     = findViewById(R.id.bUtama);
        bKedua     = findViewById(R.id.bKedua);

        bUtama.setOnClickListener(v -> {
            if (tamuJalan) return;
            if (tombolTamu) {
                if (tamuThread != null && tamuThread.isAlive()) return;
                denganIzin(this::mulaiUjiTamu);
            } else {
                if (alurThread != null && alurThread.isAlive()) return;
                denganIzin(this::mulaiWizard);
            }
        });
        bKedua.setOnClickListener(v -> aksiKedua());

        tampilkanMenurutKeadaan();
    }

    /** Aksi tombol kedua — maknanya berubah sesuai keadaan layar. */
    private void aksiKedua() {
        if (tamuJalan) {                                       // batal uji tamu
            tamuJalan = false;
            gagalUjiTamu("Uji tamu dibatalkan — rekam lagi kapan Anda siap.");
            return;
        }
        if (profilBaruTersimpan) {                             // setelah hasil uji mirip
            tamuJalan = false;
            tampilkanKunci();
            return;
        }
        if (terdaftar()) hapusKalibrasi();                     // C: hapus
    }

    // ========================= keadaan A dan C =========================

    private boolean terdaftar() { return ProfilSuara.ada(this); }

    private void tampilkanMenurutKeadaan() {
        if (terdaftar()) tampilkanKunci(); else tampilkanUndangan();
    }

    /** Keadaan A — undangan wizard. */
    private void tampilkanUndangan() {
        profilBaruTersimpan = false;
        tombolTamu = false;
        String nama = AviBrain.namaPemilik(this);
        tvJudul.setText("Kenalkan AVI dengan suara Anda");
        tvNarasi.setText("Wizard 6 langkah — frasa pendek, kurang dari dua menit. "
                + "Setelah selesai, layar ini terkunci dan AVI hanya mau melayani suara Anda.");
        tvLangkah.setText("Langkah 1 dari 6 — selamat datang");
        tvInstruksi.setText("Anda akan diminta membaca DUA frasa pendek (3 kali masing-masing) "
                + "dan menyapa \u201CHai AVI\u201D 4 kali — pelan, jelas, suara normal. "
                + "Terakhir ada UJI TAMU: minta orang lain bicara, AVI harus menolaknya. "
                + "Cari tempat yang agak sunyi" + (nama.isEmpty() ? "." : ", " + nama + "."));
        tvFrasa.setVisibility(View.GONE);
        tvDots.setVisibility(View.GONE);
        tvUmpan.setVisibility(View.GONE);
        pbLevel.setProgress(0);
        bUtama.setVisibility(View.VISIBLE);
        bUtama.setText("Mulai wizard");
        bKedua.setVisibility(View.GONE);
    }

    /** Keadaan C — terkunci: hanya Reset ulang / Hapus. */
    private void tampilkanKunci() {
        profilBaruTersimpan = false;
        tombolTamu = false;
        long w = AviBrain.pref(this).getLong("kal_waktu", 0);
        String waktu = w > 0
                ? new SimpleDateFormat("d MMM yyyy • HH.mm", Locale.getDefault())
                        .format(new Date(w))
                : "—";
        float pitch = AviBrain.pref(this).getFloat("kal_pitch", 0f);
        String mode = GerbangSapa.mode(this);
        String label = "ketat".equals(mode) ? "Ketat — hanya suara Anda dilayani"
                : "mati".equals(mode) ? "Nonaktif" : "Lembut — orang lain tetap dilayani";

        tvJudul.setText("Suara Anda terdaftar");
        tvNarasi.setText("Layar ini terkunci. Yang bisa dilakukan hanya "
                + "mereset atau menghapus — persis seperti kunci asli.");
        tvLangkah.setText("Wizard selesai ✓  •  terkunci");
        tvInstruksi.setText("Didaftarkan " + waktu
                + (pitch > 0 ? " • nada dasar suara Anda ±" + Math.round(pitch) + " Hz" : "")
                + " • gerbang: " + label + ".");
        tvFrasa.setVisibility(View.GONE);
        tvDots.setVisibility(View.GONE);
        tvUmpan.setVisibility(View.GONE);
        pbLevel.setProgress(100);
        bUtama.setVisibility(View.VISIBLE);
        bUtama.setText("Reset ulang (wizard lagi)");
        bKedua.setVisibility(View.VISIBLE);
        bKedua.setText("Hapus kalibrasi");
    }

    private void hapusKalibrasi() {
        new AlertDialog.Builder(this)
                .setTitle("Hapus kalibrasi?")
                .setMessage("Profil suara & kunci sapaan dihapus. AVI kembali "
                        + "melayani siapa pun sampai Anda mendaftar ulang.")
                .setPositiveButton("Hapus", (d, w) -> {
                    ProfilSuara.berkas(this).delete();
                    KunciSapa.berkas(this).delete();
                    AviBrain.pref(this).edit()
                            .remove("kal_lantai_db").remove("kal_bicara_db")
                            .remove("kal_skor").remove("kal_waktu")
                            .remove("kal_pitch")
                            .apply();
                    Toast.makeText(this, "Kalibrasi dihapus.", Toast.LENGTH_SHORT).show();
                    tampilkanUndangan();
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    // ============================== izin ==============================

    private boolean izinAda() {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void denganIzin(Runnable aksi) {
        if (izinAda()) { aksi.run(); return; }
        tertunda = aksi;
        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
    }

    @Override
    public void onRequestPermissionsResult(int kode, String[] izin, int[] hasil) {
        super.onRequestPermissionsResult(kode, izin, hasil);
        if (kode != 1) return;
        if (hasil.length > 0 && hasil[0] == PackageManager.PERMISSION_GRANTED) {
            if (tertunda != null) { Runnable a = tertunda; tertunda = null; a.run(); }
        } else {
            Toast.makeText(this, "Izin mikrofon ditolak — wizard pendaftaran "
                    + "suara butuh mikrofon.", Toast.LENGTH_LONG).show();
        }
    }

    // ==================== WIZARD (langkah 2-5) ====================

    private void mulaiWizard() {
        if (alurJalan) return;
        if (alurThread != null && alurThread.isAlive()) return;   // anti ganda
        alurJalan = true;
        profilBaruTersimpan = false;
        bUtama.setVisibility(View.GONE);   // terkunci selama wizard berjalan
        bKedua.setVisibility(View.GONE);
        alurThread = new Thread(this::jalankanWizard, "avi-kalibrasi");
        alurThread.start();
    }

    /** Tidur antar percobaan; false = alur dimatikan (jangan lanjut). */
    private boolean jeda(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { return false; }
        return alurJalan;
    }

    private void jalankanWizard() {
        AudioRecord ar = null;
        try {
            ar = GerbangSapa.bukaMikrofon();
            if (ar == null) {
                gagalAlur("Mikrofon tidak bisa dibuka — tutup aplikasi lain "
                        + "yang memakai mikrofon (perekam, telepon, hotword), "
                        + "lalu tekan Mulai lagi.");
                return;
            }
            ar.startRecording();
            short[] blok = new short[BLOK];

            // ---- LANGKAH 2: kondisi ruangan (pemilik diam) ----
            ui.post(() -> {
                tvLangkah.setText("Langkah 2 dari 6 — kondisi ruangan");
                tvInstruksi.setText("Diam dulu ya — AVI sedang mendengar "
                        + "ruangan selama sedetik lebih…");
                tvFrasa.setVisibility(View.GONE);
                tvDots.setVisibility(View.GONE);
                tvUmpan.setVisibility(View.GONE);
                pbLevel.setProgress(0);
            });
            double jumlah = 0;
            int nLantai = 0;
            long t0 = System.currentTimeMillis();
            while (alurJalan && System.currentTimeMillis() - t0 < RUANG_MS) {
                int n = ar.read(blok, 0, blok.length);
                if (n <= 0) continue;
                jumlah += Dsp.db(blok, 0, n);
                nLantai++;
                pasangMeter(Dsp.db(blok, 0, n));
            }
            if (!alurJalan) return;
            final float lantai = nLantai > 0 ? (float) (jumlah / nLantai) : -55f;

            // ---- LANGKAH 3-4: dua frasa pendek, 3 take masing-masing ----
            ArrayList<short[]> take1 = rekamSeri(ar, blok, lantai + 8.0,
                    FRASA1, TAKE_FRASA, 3, 4000, 800);
            if (take1 == null) return;
            umpanBalikBagus("Frasa pertama tuntas ✓");
            if (!jeda(900)) return;

            ArrayList<short[]> take2 = rekamSeri(ar, blok, lantai + 8.0,
                    FRASA2, TAKE_FRASA, 4, 4000, 800);
            if (take2 == null) return;
            umpanBalikBagus("Frasa kedua tuntas ✓");
            if (!jeda(900)) return;

            // ---- LANGKAH 5: kunci sapaan "Hai AVI" 4 take ----
            ArrayList<short[]> takeSapa = rekamSeri(ar, blok, lantai + 8.0,
                    SAPAAN, TAKE_SAPA, 5, 3500, 750);
            if (takeSapa == null) return;
            umpanBalikBagus("Sapaan terkunci ✓");
            if (!jeda(900)) return;

            // ---- bangun profil dari GABUNGAN semua take frasa ----
            ui.post(() -> tvLangkah.setText("Menyusun profil suara…"));
            short[] gabungan = gabung(take1, take2);
            ProfilSuara profil = ProfilSuara.bangun(gabungan, 0, gabungan.length, lantai);
            if (profil == null || profil.frameSuara < 30) {
                gagalAlur("Suara masih belum tertangkap baik. Cari tempat lebih "
                        + "sunyi, bicara lebih dekat ke ponsel, lalu ulangi wizard.");
                return;
            }

            KunciSapa kunci = KunciSapa.bangun(takeSapa);
            if (kunci == null || !kunci.simpan(this)) {
                gagalAlur("Gagal menyimpan kunci sapaan.");
                return;
            }
            if (!profil.simpan(this)) {
                gagalAlur("Gagal menyimpan profil suara.");
                return;
            }
            // Bawaan hasil wizard: gerbang KETAT — hanya pemilik dilayani.
            // Pilihan manual pemilik di Pengaturan tidak ditimpa.
            boolean sudahPilih = AviBrain.pref(this).getBoolean("kal_mode_dipilih", false);
            AviBrain.pref(this).edit()
                    .putFloat("kal_lantai_db", lantai)
                    .putFloat("kal_bicara_db", profil.bicaraDb)
                    .putFloat("kal_pitch", profil.pitchMedian)
                    .putLong("kal_waktu", System.currentTimeMillis())
                    .apply();
            if (!sudahPilih) {
                AviBrain.pref(this).edit().putString("kal_mode", "ketat").apply();
            }

            // ---- LANGKAH 6: UJI TAMU (interaktif — tombol muncul) ----
            ui.post(() -> {
                tvLangkah.setText("Langkah 6 dari 6 — uji tamu");
                tvInstruksi.setText("Sekarang BUKTIKAN kalibrasinya bekerja: "
                        + "minta orang lain bicara ke ponsel (atau tirukan suara "
                        + "yang berbeda). AVI HARUS menolak — artinya cuma suara "
                        + "Anda yang dilayani.");
                tvFrasa.setVisibility(View.GONE);
                tvDots.setVisibility(View.GONE);
                tvUmpan.setVisibility(View.GONE);
                pbLevel.setProgress(0);
                tombolTamu = true;          // tombol utama kini = uji tamu
                bUtama.setVisibility(View.VISIBLE);
                bUtama.setText("Rekam uji tamu");
                bKedua.setVisibility(View.VISIBLE);
                bKedua.setText("Lewati (tidak ada orang lain)");
            });
            profilBaruTersimpan = true;   // wizard inti sudah aman tersimpan
        } catch (Throwable e) {
            String nama = e.getClass().getSimpleName();
            String saran = (e instanceof OutOfMemoryError)
                    ? "Memori ponsel penuh — tutup aplikasi lain lalu ulangi."
                    : "Gangguan sementara (" + nama + ") — tekan Mulai lagi ya.";
            gagalAlur(saran);
        } finally {
            if (ar != null) {
                try { ar.stop(); } catch (Exception ignored) {}
                try { ar.release(); } catch (Exception ignored) {}
            }
        }
    }

    // ======================= LANGKAH 6: uji tamu =======================

    private void mulaiUjiTamu() {
        if (tamuJalan) return;
        if (tamuThread != null && tamuThread.isAlive()) return;
        tamuJalan = true;
        bUtama.setVisibility(View.GONE);
        bKedua.setText("Batalkan");
        tamuThread = new Thread(this::jalankanUjiTamu, "avi-uji-tamu");
        tamuThread.start();
    }

    private void jalankanUjiTamu() {
        AudioRecord ar = null;
        try {
            ar = GerbangSapa.bukaMikrofon();
            if (ar == null) {
                gagalUjiTamu("Mikrofon tidak bisa dibuka — tutup aplikasi lain "
                        + "yang memakai mikrofon, lalu coba lagi.");
                return;
            }
            ar.startRecording();
            short[] blok = new short[BLOK];
            float lantai = AviBrain.pref(this).getFloat("kal_lantai_db", -55f);
            ProfilSuara pemilik = ProfilSuara.muat(this);
            if (pemilik == null) {
                gagalUjiTamu("Profil suara tidak terbaca — ulangi wizard.");
                return;
            }

            for (int percobaan = 1; percobaan <= 2 && tamuJalan; percobaan++) {
                final int ke = percobaan;
                ui.post(() -> {
                    tvInstruksi.setText("Mendengarkan tamu… minta dia bicara "
                            + "sekarang (percobaan " + ke + " dari 2).");
                    pbLevel.setProgress(0);
                });
                short[] ucap = rekamUcapan(ar, blok, lantai + 8.0, 3500, 750);
                if (!tamuJalan) return;

                ProfilSuara tamu = (ucap.length == 0) ? null
                        : ProfilSuara.bangun(ucap, 0, ucap.length, lantai);
                if (tamu == null) {
                    ui.post(() -> tvInstruksi.setText("AVI tidak mendengar "
                            + "suara tamu — minta dia bicara lebih dekat, "
                            + "lalu rekam lagi."));
                    if (!jedaTamu(1600)) return;
                    continue;
                }

                int persen = pemilik.persen(tamu);
                if (pemilik.cocok(tamu, false)) {
                    // TAMU LOLOS = profil terlalu longgar — peringatkan!
                    ui.post(() -> {
                        tvInstruksi.setText("PERINGATAN: suara tadi MIRIP suara "
                                + "Anda (" + persen + "%) — AVI bisa salah kenal "
                                + "orang lain. Ulangi uji tamu, atau selesaikan "
                                + "dan pakai gerbang Ketat.");
                        tombolTamu = true;              // utama = uji tamu lagi
                        bUtama.setVisibility(View.VISIBLE);
                        bUtama.setText("Ulangi uji tamu");
                        bKedua.setVisibility(View.VISIBLE);
                        bKedua.setText("Tetap selesai");
                    });
                    return;
                }
                // DITOLAK — benar seperti seharusnya!
                ui.post(() -> {
                    tvLangkah.setText("Uji tamu LULUS ✓");
                    tvInstruksi.setText("Suara tamu DITOLAK (" + persen
                            + "% — di bawah ambang). AVI cuma mau melayani "
                            + "Anda. Kalibrasi bukan pajangan ✓");
                    tvFrasa.setVisibility(View.GONE);
                    tvDots.setVisibility(View.GONE);
                    pbLevel.setProgress(100);
                });
                if (!jedaTamu(1800)) return;
                ui.post(() -> { if (!isFinishing() && !isDestroyed()) tampilkanKunci(); });
                return;
            }
            if (tamuJalan) {
                gagalUjiTamu("Suara tamu tidak pernah terdengar jelas. Uji "
                        + "bisa diulang kapan saja — atau lewati.");
            }
        } catch (Throwable e) {
            gagalUjiTamu("Uji tamu terganggu (" + e.getClass().getSimpleName()
                    + ") — coba lagi ya.");
        } finally {
            if (ar != null) {
                try { ar.stop(); } catch (Exception ignored) {}
                try { ar.release(); } catch (Exception ignored) {}
            }
        }
    }

    private void gagalUjiTamu(final String pesan) {
        ui.post(() -> {
            if (isFinishing() || isDestroyed()) return;
            tvInstruksi.setText(pesan);
            tombolTamu = true;              // utama tetap = uji tamu
            bUtama.setVisibility(View.VISIBLE);
            bUtama.setText("Rekam uji tamu");
            bKedua.setVisibility(View.VISIBLE);
            bKedua.setText("Lewati");
        });
    }

    /** Jeda dalam uji tamu; false = uji dibatalkan. */
    private boolean jedaTamu(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { return false; }
        return tamuJalan;
    }

    // =================== helper perekaman wizard ===================

    /** Umpan balik singkat antar langkah. */
    private void umpanBalikBagus(String teks) {
        ui.post(() -> {
            tvLangkah.setText(teks);
            tvFrasa.setVisibility(View.GONE);
            tvDots.setVisibility(View.GONE);
        });
    }

    /** Gabung beberapa potongan PCM jadi satu (semua 16 kHz mono). */
    private static short[] gabung(ArrayList<short[]> a, ArrayList<short[]> b) {
        int n = 0;
        for (short[] x : a) n += x.length;
        for (short[] x : b) n += x.length;
        short[] out = new short[n];
        int p = 0;
        for (short[] x : a) { System.arraycopy(x, 0, out, p, x.length); p += x.length; }
        for (short[] x : b) { System.arraycopy(x, 0, out, p, x.length); p += x.length; }
        return out;
    }

    /**
     * Rekam satu SERI take (permintaan pemilik: 3-4 take per frasa supaya
     * AVI paham jelas). Take gagal = ulang take itu saja (maks 8 usaha).
     * Return null bila alur dimatikan.
     */
    private ArrayList<short[]> rekamSeri(AudioRecord ar, short[] blok, double ambang,
            String frasa, int nTake, int langkah, long maksMs, long heningMs)
            throws Exception {
        ArrayList<short[]> hasil = new ArrayList<>();
        int usaha = 0;
        while (hasil.size() < nTake && alurJalan && usaha < 8) {
            usaha++;
            final int terisi = hasil.size();
            final int usahaIni = usaha;
            final String f = frasa;
            final int lang = langkah;
            ui.post(() -> {
                tvLangkah.setText("Langkah " + lang + " dari 6"
                        + (usahaIni > nTake ? " — ulangan" : ""));
                tvInstruksi.setText("Baca/ucapkan dengan suara jelas dan normal — "
                        + "AVI menunggu sampai Anda selesai (take "
                        + (terisi + 1) + " dari " + nTake + "):");
                tvFrasa.setVisibility(View.VISIBLE);
                tvFrasa.setText(f);
                tvDots.setVisibility(View.VISIBLE);
                tvDots.setText(titik(terisi, nTake));
                pbLevel.setProgress(0);
            });
            short[] ucap = rekamUcapan(ar, blok, ambang, maksMs, heningMs);
            if (!alurJalan) return null;
            if (ucap.length < 1200
                    || Dsp.mfcc(ucap, 0, ucap.length).length < 6) {
                ui.post(() -> tvInstruksi.setText("Tidak terdengar jelas — "
                        + "bicara sedikit lebih dekat dan lebih jelas ya."));
                if (!jeda(1600)) return null;
                continue;
            }
            hasil.add(ucap);
        }
        if (!alurJalan) return null;
        if (hasil.size() < nTake) {
            gagalAlur("Seri take belum lengkap. Wizard bisa diulang "
                    + "kapan saja dari layar ini.");
            return null;
        }
        return hasil;
    }

    private String titik(int terisi, int total) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < total; i++) {
            if (i > 0) s.append("  ");
            s.append(i < terisi ? "●" : "○");
        }
        return s.toString();
    }

    private void gagalAlur(final String pesan) {
        ui.post(() -> {
            if (isFinishing() || isDestroyed()) return;
            tampilkanMenurutKeadaan();
            tvInstruksi.setText(pesan);
        });
    }

    // ======================= rekam satu ucapan =======================

    /**
     * Tunggu onset (maks 8 dtk) → rekam sampai hening 'heningMs' atau
     * batas 'maksMs'. Meter & nada dasar hidup di tvUmpan/pbLevel.
     */
    private short[] rekamUcapan(AudioRecord ar, short[] blok, double ambang,
                                long maksMs, long heningMs) throws Exception {
        long t0 = System.currentTimeMillis();
        int keras = 0;
        while ((alurJalan || tamuJalan)
                && System.currentTimeMillis() - t0 < 8000) {
            int n = ar.read(blok, 0, blok.length);
            if (n <= 0) continue;
            double db = Dsp.db(blok, 0, n);
            pasangMeter(db);
            if (db > ambang) { if (++keras >= 3) break; } else keras = 0;
        }
        if (keras < 3) return new short[0];   // tak pernah terdengar

        ByteArrayOutputStream buf = new ByteArrayOutputStream(BLOK * 2 * 128);
        long mulai = System.currentTimeMillis();
        long suaraTerakhir = System.currentTimeMillis();
        int hitung = 0;
        while ((alurJalan || tamuJalan) && System.currentTimeMillis() - mulai < maksMs) {
            int n = ar.read(blok, 0, blok.length);
            if (n <= 0) continue;
            for (int i = 0; i < n; i++) {
                int s = blok[i];
                buf.write(s & 0xFF);
                buf.write((s >> 8) & 0xFF);
            }
            long kini = System.currentTimeMillis();
            double db = Dsp.db(blok, 0, n);
            if (db > ambang - 2.0) suaraTerakhir = kini;
            if ((hitung++ & 15) == 0) {
                pasangMeter(db);
                tampilkanNada(blok);
            }
            if (kini - mulai > 900 && kini - suaraTerakhir > heningMs) break;
        }
        byte[] b = buf.toByteArray();
        short[] pcm = new short[b.length / 2];
        ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm);
        return pcm;
    }

    private void pasangMeter(final double db) {
        final int level = (int) Math.max(0, Math.min(100, (db + 60.0) / 50.0 * 100.0));
        ui.post(() -> pbLevel.setProgress(level));
    }

    /** Nada dasar sesaat (tiap ±0,5 dtk) — umpan balik "AVI benar-benar dengar". */
    private void tampilkanNada(short[] blok) {
        double[][] p = Dsp.pitch(blok, 0, blok.length);
        for (double[] frame : p) {
            if (frame[0] > 0) {
                final int hz = (int) Math.round(frame[0]);
                ui.post(() -> {
                    if (!alurJalan && !tamuJalan) return;
                    tvUmpan.setVisibility(View.VISIBLE);
                    tvUmpan.setText("nada ±" + hz + " Hz");
                });
                return;
            }
        }
    }

    // ========================== siklus hidup ==========================

    @Override
    protected void onPause() {
        // mikrofon tidak boleh hidup di latar — hentikan alur apa pun
        alurJalan = false;
        tamuJalan = false;
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!alurJalan && !tamuJalan) tampilkanMenurutKeadaan();
    }
}
