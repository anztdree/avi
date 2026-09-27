package com.avi.assistant;

import android.animation.ObjectAnimator;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.service.voice.VoiceInteractionSession;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

/**
 * Sesi ASISTEN PERANGKAT AVI v1.3 (b20) — jalur VoiceInteractionSession
 * untuk HP NON-low-RAM. Tampilan & kelakuan IDENTIK dengan
 * OrbitAssistActivity: PANEL GOOGLE MURNI hasil kalibrasi web
 * (Task 13-17) — scrim tipis 0,30, jawaban AVI melayang di atas pill,
 * SATU bar "Tanya AVI…" + tombol suara tiga peran (mic/kirim/stop).
 * Tanpa salam, tanpa ✕, tanpa titik, tanpa status.
 *
 * Baru b20 (laporan pemilik): dasar gelap menguat dari bawah di zona
 * jawaban+pill (pola Google Assistan — app di belakang tak lagi
 * bertabrakan dengan teks), dan mic MENDENGARKAN kini SOLID teal +
 * ikon gelap + pill "Mendengarkan…" — keadaan kerja terlihat jelas.
 *
 * Baru b21 (laporan pemilik): balasan AI dalam BUBBLE; izin mic
 * DIMINTA seketika panel terbuka (MintaIzinMicActivity) dan begitu
 * diberikan mic menyala sendiri; panel TAK tidur sendiri saat hening
 * (tidurBilaSenyap=false — terus mendengar sampai ditutup manual).
 *
 * Kata-kata pemilik hidup DI DALAM pill (bicara maupun ketik) dan hilang
 * sendiri saat giliran tuntas. Mesin menyala otomatis begitu sesi
 * tergambar — mic langsung siap mendengarkan; tanpa izin mic, pill ketik
 * tetap hidup. Menutup: ketuk area kosong, usap ke bawah, atau AVI pamit.
 */
public class AviSession extends VoiceInteractionSession implements LiveEngine.Pendengar {

    private View akar;
    private View barisJawaban;
    private EditText etPil;
    private ImageView ikonSuara;
    private TextView tvAvi;
    private View bSuara;
    private LiveEngine mesin;
    private GestureDetector usap;
    private ObjectAnimator denyut;
    private int keadaan = OrbView.SIAP;
    private boolean micSip = true;
    private boolean transkripDiPil = false;

    public AviSession(Context context) {
        super(context);
    }

    @Override
    public View onCreateContentView() {
        akar = getLayoutInflater().inflate(R.layout.overlay_avisession, null);
        barisJawaban = akar.findViewById(R.id.barisJawabanSesi);
        etPil = akar.findViewById(R.id.etPilSesi);
        ikonSuara = akar.findViewById(R.id.ikonSuaraSesi);
        bSuara = akar.findViewById(R.id.bSuaraSesi);
        tvAvi = akar.findViewById(R.id.tvAviSesi);

        // usap ke bawah = tutup; ketukan biasa diteruskan ke onClick akar
        // = KETUK AREA KOSONG = tutup (pola web disetujui)
        usap = new GestureDetector(getContext(),
                new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onFling(MotionEvent a, MotionEvent b,
                                             float vx, float vy) {
                if (vy > 0 && vy > Math.abs(vx) * 1.4f && vy > 900f) {
                    finish();
                    return true;
                }
                return false;
            }
        });
        akar.setOnTouchListener((v, ev) -> usap.onTouchEvent(ev));
        akar.setOnClickListener(v -> finish());

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
        return akar;
    }

    @Override
    public void onShow(Bundle args, int showFlags) {
        // jendela transparan menutup seluruh layar; scrim tipis 0,30
        // digambar layout sendiri — tanpa dim sistem tambahan
        Dialog jendela = getWindow();
        if (jendela != null && jendela.getWindow() != null) {
            Window w = jendela.getWindow();
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setDimAmount(0f);
            w.setGravity(Gravity.BOTTOM);
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
            // pill ketik: keyboard mendorong isi, bukan menutupi
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }

        // satu animasi pendek saja — sesi muncul nyaris seketika
        if (akar != null) {
            akar.setAlpha(0f);
            akar.animate().alpha(1f).setDuration(120L).start();
        }

        if (mesin == null) mesin = new LiveEngine(getContext(), this);
        // mesin menyala SETELAH sesi tergambar — OTOMATIS siap
        // mendengarkan; pill ketik tetap hidup bila mic tak tersedia
        akar.post(() -> {
            Dialog d = getWindow();
            if (mesin == null || d == null || !d.isShowing()) return;
            micSip = mesin.izinMicAda();
            perbaruiTombol();
            mesin.tidurBilaSenyap = false;   // b21: panel terus mendengar
            mesin.mulai();
            // b21: izin mic belum ada? MINTA SEKARANG — dulu tidak ada
            // satu pun yang meminta, mesin mati diam tanpa pesan
            if (!micSip) mintaIzinMic();
        });
    }

    /** b21: izin mic diminta lewat activity transparan kecil
     *  (VoiceInteractionSession tak bisa requestPermissions).
     *  Begitu diberikan, dengaran langsung nyala tanpa disentuh. */
    private void mintaIzinMic() {
        try {
            MintaIzinMicActivity.saatDiizinkan = () -> {
                if (mesin == null || akar == null) return;
                micSip = mesin.izinMicAda();
                perbaruiTombol();
                mesin.hentikan();
                mesin.mulai();   // hidupkan ulang — mic kini siap mendengarkan
            };
            Intent it = new Intent(getContext(), MintaIzinMicActivity.class);
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(it);
        } catch (Exception ignored) {
            // tanpa izin pun panel tetap hidup lewat jalur ketik
        }
    }

    @Override
    public void onHide() {
        if (mesin != null) mesin.hentikan();
        berhentiDenyut();
    }

    @Override
    public void onDestroy() {
        if (mesin != null) { mesin.hentikan(); mesin = null; }
        berhentiDenyut();
        super.onDestroy();
    }

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
            latar.setColor(AviBrain.warnaAksen(getContext()));
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

    @Override public void tetidur() {
        finish();
    }
}
