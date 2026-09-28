package com.avi.assistant;

import android.animation.ObjectAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.Manifest;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

/**
 * PINTU ASISTEN v1.3 (b20) — PANEL GOOGLE MURNI (jalur ACTIVITY untuk
 * HP low-RAM; jalur sesi AviSession memakai layout & wiring yang sama).
 *
 * Disetujui pemilik lewat kalibrasi visual web (Task 13-17, "efisien
 * yg utama"): scrim tipis 0,30 + jawaban AVI melayang di atas pill +
 * SATU bar "Tanya AVI…" + tombol suara tiga peran. Tanpa salam, tanpa
 * ✕, tanpa titik, tanpa status — kata-kata pemilik HIDUP DI DALAM pill
 * dan hilang sendiri saat giliran tuntas.
 *
 * Baru b20 (laporan pemilik): dasar gelap menguat dari bawah di zona
 * jawaban+pill (pola Google Assistan — app di belakang tak lagi
 * bertabrakan dengan teks), dan mic MENDENGARKAN kini SOLID teal +
 * ikon gelap + pill "Mendengarkan…" — keadaan kerja terlihat jelas.
 *
 * Baru b21 (laporan pemilik): balasan AI dalam BUBBLE; izin mic
 * DIMINTA seketika panel terbuka dan begitu diberikan mic menyala
 * sendiri; panel TAK tidur sendiri saat hening (tidurBilaSenyap=false).
 *
 * Baru b22 (laporan pemilik): panel tombol TIDAK LAGI menjalankan gerbang
 * verifikasi "Hai AVI" (lewatiGerbang — ucapan pertama dimakan gerbang,
 * AVI terasa tuli ±30 dtk; pola Google: tombol fisik = langsung dengar),
 * dan mesin dijaga dari churn recognizer (sumber rasa berat).
 *
 * Otomatis (Task 17): mesin menyala begitu panel tergambar — mic
 * langsung siap mendengarkan bila izin ada; tanpa izin mic, pill ketik
 * tetap hidup (teksManual). Tutup: ketuk area kosong / usap ke bawah /
 * AVI pamit. Barge-in: sentuh jawaban saat AVI bicara.
 */
public class OrbitAssistActivity extends Activity implements LiveEngine.Pendengar {

    private View akar;
    private View barisJawaban;
    private EditText etPil;
    private ImageView ikonSuara;
    private TextView tvAvi;
    private View bSuara;
    private LiveEngine mesin;
    private boolean mesinJalan;
    private GestureDetector usap;
    private ObjectAnimator denyut;
    private int keadaan = OrbView.SIAP;
    private boolean micSip = true;
    private boolean transkripDiPil = false;

    @Override
    protected void attachBaseContext(Context baru) {
        super.attachBaseContext(AviBrain.terapkanTema(baru));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.overlay_avisession);   // statis — instan

        akar = findViewById(R.id.akarSesi);
        barisJawaban = findViewById(R.id.barisJawabanSesi);
        etPil = findViewById(R.id.etPilSesi);
        ikonSuara = findViewById(R.id.ikonSuaraSesi);
        bSuara = findViewById(R.id.bSuaraSesi);
        tvAvi = findViewById(R.id.tvAviSesi);

        // usap ke bawah = tutup (pola Google); ketukan biasa diteruskan
        // ke onClick akar = KETUK AREA KOSONG = tutup (pola web disetujui)
        usap = new GestureDetector(this,
                new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onFling(MotionEvent a, MotionEvent b,
                                             float vx, float vy) {
                if (vy > 0 && vy > Math.abs(vx) * 1.4f && vy > 900f) {
                    pamit();
                    return true;
                }
                return false;
            }
        });
        akar.setOnTouchListener((v, ev) -> usap.onTouchEvent(ev));
        akar.setOnClickListener(v -> pamit());

        // barge-in: sentuh jawaban saat AVI bicara = langsung diam
        tvAvi.setOnClickListener(v -> {
            if (mesin != null && keadaan == OrbView.BICARA) mesin.potongTts();
        });

        // tombol suara: kirim ketikan / stop TTS (mic = cuma umpan balik)
        bSuara.setOnClickListener(v -> {
            if (mesin == null) return;
            String t = etPil.getText().toString().trim();
            if (!transkripDiPil && !t.isEmpty()) { mesin.teksManual(t); return; }
            if (keadaan == OrbView.BICARA) mesin.potongTts();
        });

        // Enter di pill = kirim (pola web)
        etPil.setOnEditorActionListener((v, aksi, ev) -> {
            if (aksi == EditorInfo.IME_ACTION_SEND
                    || ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER) {
                String t = etPil.getText().toString().trim();
                if (!t.isEmpty() && mesin != null) mesin.teksManual(t);
                return true;
            }
            return false;
        });
        etPil.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { perbaruiTombol(); }
        });

        // satu animasi pendek saja — panel muncul nyaris seketika
        akar.setAlpha(0f);
        akar.animate().alpha(1f).setDuration(120L).start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mesin == null) mesin = new LiveEngine(this, this);
        if (!mesinJalan) {
            mesinJalan = true;
            // b19: mesin menyala SETELAH frame pertama tergambar — dan
            // TANPA syarat izin: mesin mengurus izin sendiri (status),
            // pill ketik tetap hidup bila mic tak tersedia.
            akar.post(() -> {
                if (isFinishing() || isDestroyed() || mesin == null) return;
                micSip = mesin.izinMicAda();
                perbaruiTombol();
                mesin.tidurBilaSenyap = false;   // b21: panel terus mendengar
                // b22: panel dibuka lewat TOMBOL = langsung dengar tanpa
                // gerbang sapa — dulu ucapan pertama dimakan verifikasi
                mesin.lewatiGerbang = true;
                mesin.mulai();          // OTOMATIS siap mendengarkan
                // b21: izin mic belum ada? MINTA SEKARANG — dulu tidak
                // ada satu pun yang meminta, mesin mati diam tanpa pesan
                if (!micSip) {
                    requestPermissions(
                            new String[]{Manifest.permission.RECORD_AUDIO}, 11);
                }
            });
        }
    }

    @Override
    protected void onPause() {
        // mikrofon tidak boleh hidup di latar — privasi & anti-gema
        if (mesin != null) { mesin.hentikan(); mesinJalan = false; }
        berhentiDenyut();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (mesin != null) { mesin.hentikan(); mesin = null; }
        berhentiDenyut();
        super.onDestroy();
    }

    /** b21: izin mic baru saja diberikan → dengaran langsung nyala
     *  tanpa disentuh (hidupkan ulang mesin yang tadinya mati diam). */
    @Override
    public void onRequestPermissionsResult(int kode, String[] izin, int[] hasil) {
        super.onRequestPermissionsResult(kode, izin, hasil);
        if (kode != 11 || mesin == null) return;
        if (izin.length > 0 && Manifest.permission.RECORD_AUDIO.equals(izin[0])
                && hasil.length > 0
                && hasil[0] == PackageManager.PERMISSION_GRANTED) {
            micSip = mesin.izinMicAda();
            perbaruiTombol();
            mesin.hentikan();
            mesin.mulai();
            mesinJalan = true;
        }
    }

    private void pamit() { finish(); }

    // ================= wajah tombol suara (tiga peran) =================

    private void perbaruiTombol() {
        if (bSuara == null || ikonSuara == null) return;
        GradientDrawable latar = (GradientDrawable) bSuara.getBackground().mutate();

        // b20: pill ikut menyebut keadaan — cermin placeholder web;
        // keadaan kerja mic kini terlihat dari DUA tempat
        if (etPil != null) {
            etPil.setHint(keadaan == OrbView.MENDENGARKAN && micSip
                    ? "Mendengarkan…" : "Tanya AVI…");
        }

        if (!micSip) {                        // mic tak tersedia — ketik saja
            ikonSuara.setImageResource(R.drawable.ic_mic);
            ikonSuara.setColorFilter(0xFF9AA8A4);
            latar.setColor(0x1A2DD4BF);
            bSuara.setEnabled(false);
            berhentiDenyut();
            return;
        }
        bSuara.setEnabled(true);

        String ketikan = etPil.getText().toString().trim();
        if (!transkripDiPil && !ketikan.isEmpty()) {        // PERAN 2: kirim
            ikonSuara.setImageResource(R.drawable.ic_send);
            ikonSuara.setColorFilter(0xFFFFFFFF);
            latar.setColor(AviBrain.warnaAksen(this));
            berhentiDenyut();
        } else if (keadaan == OrbView.BICARA) {             // PERAN 3: stop
            ikonSuara.setImageResource(R.drawable.ic_stop);
            ikonSuara.setColorFilter(0xFFF2F7F5);
            latar.setColor(0x24FFFFFF);
            berhentiDenyut();
        } else {                                            // PERAN 1: mic
            boolean dengar = keadaan == OrbView.MENDENGARKAN;
            ikonSuara.setImageResource(R.drawable.ic_mic);
            // b20: saat mendengarkan tombol SOLID teal + ikon gelap —
            // keadaan kerja jelas terlihat (latar 18% dulu terlalu samar)
            ikonSuara.setColorFilter(dengar ? 0xFF07120F : 0xFF2DD4BF);
            latar.setColor(dengar ? 0xFF2DD4BF : 0x1A2DD4BF);
            denyutkan(dengar);
        }
    }

    /** Umpan balik dengar: tombol mic berdenyut halus (pola web aviDenyut). */
    private void denyutkan(boolean nyala) {
        if (nyala) {
            if (denyut == null) {
                denyut = ObjectAnimator.ofFloat(bSuara, "alpha", 1f, 0.55f);
                denyut.setDuration(700L);
                denyut.setRepeatCount(ObjectAnimator.INFINITE);
                denyut.setRepeatMode(ObjectAnimator.REVERSE);
            }
            if (!denyut.isRunning()) denyut.start();
        } else {
            berhentiDenyut();
        }
    }

    private void berhentiDenyut() {
        if (denyut != null) { denyut.cancel(); denyut = null; }
        if (bSuara != null) bSuara.setAlpha(1f);
    }

    // ================= peristiwa dari mesin (thread utama) =================

    @Override public void keadaan(int k) {
        keadaan = k;
        perbaruiTombol();
    }

    @Override public void status(String teks) {
        // tanpa teks status — murni obrolan (pola web disetujui)
    }

    /** Kata-kata pemilik HIDUP DI DALAM pill — bicara maupun ketik
     *  (pola web Task 15); "" = bersihkan (potong / pamit). */
    @Override public void transkripAnda(String teks) {
        if (etPil == null) return;
        String t = teks == null ? "" : teks;
        transkripDiPil = !t.trim().isEmpty();
        etPil.setText(t);
        if (transkripDiPil) etPil.setSelection(t.length());
    }

    @Override public void teksAvi(String teks) {
        if (tvAvi == null || barisJawaban == null) return;
        if (teks == null || teks.trim().length() == 0) {
            barisJawaban.setVisibility(View.GONE);
            return;
        }
        barisJawaban.setVisibility(View.VISIBLE);
        tvAvi.setText(teks);
    }

    /** Giliran tuntas — pill kembali jadi input kosong (pola web). */
    @Override public void giliranBeres() {
        transkripDiPil = false;
        if (etPil != null) etPil.setText("");
    }

    @Override public void rms(float rmsdb) {
        // titik dihapus — tidak ada yang perlu digerakkan
    }

    @Override public void tetidur() { pamit(); }
}
