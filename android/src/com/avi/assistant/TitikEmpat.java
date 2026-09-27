package com.avi.assistant;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * TITIK EMPAT AVI — tanda tangan asisten ala Google Assistant (b18),
 * menggantikan orb gradien di panel tahan-home.
 *
 * Mengapa BUKAN OrbView: orb menggambar ulang dirinya 60 KALI per detik
 * terus-menerus (postInvalidateOnAnimation + SweepGradient berputar) —
 * di itel low-RAM itu beban GPU/CPU yang membuat panel terasa berat.
 * TitikEmpat hanya menggambar ulang SAAT animasinya berjalan
 * (ValueAnimator milik sistem) dan MATI TOTAL di keadaan SIAP —
 * nol biaya ketika AVI diam menunggu. Persis filsafat Google: empat
 * titik kecil yang hidup saat bekerja, diam saat menunggu.
 *
 * Empat keadaan (nilai sama dengan OrbView supaya LiveEngine.Pendengar
 * tercolok langsung tanpa penerjemah):
 *   SIAP (0)          : titik diam redup — TANPA animator
 *   MENDENGARKAN (1)  : gelombang bernafas bergiliran, ikut keras suara
 *   BERPIKIR (2)      : titik melompat bergantian satu per satu
 *   BICARA (3)        : titik berdenyut serempak
 *
 * Warna = identitas AVI (biru muda, sian, indigo, ungu) — bukan salinan
 * biru-merah-kuning-hijau Google.
 */
public class TitikEmpat extends View {

    public static final int SIAP = 0;
    public static final int MENDENGARKAN = 1;
    public static final int BERPIKIR = 2;
    public static final int BICARA = 3;

    private static final int[] WARNA = {
            0xFF7DD3FC, 0xFF38BDF8, 0xFF6366F1, 0xFFA855F7 };

    private int keadaan = SIAP;
    private final Paint kuas = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float[] skala = {1f, 1f, 1f, 1f};
    private ValueAnimator anim;
    private float rms = 0f;          // 0..1 keras suara saat MENDENGARKAN

    public TitikEmpat(Context c) { super(c); }
    public TitikEmpat(Context c, AttributeSet a) { super(c, a); }
    public TitikEmpat(Context c, AttributeSet a, int s) { super(c, a, s); }

    public void setKeadaan(int k) {
        if (keadaan == k && anim != null == (k != SIAP)) return;
        keadaan = k;
        aturAnimasi();
    }

    public int getKeadaan() { return keadaan; }

    /** Dari LiveEngine.onRmsChanged — TIDAK memicu invalidate sendiri
     *  (hemat): gelombang MENDENGARKAN yang memakai nilai ini memang
     *  sedang berjalan, jadi frame berikutnya otomatis tergambar ulang. */
    public void setRms(float rmsdb) {
        // ~ -2 dB senyap .. +10 dB keras → petakan ke 0..1
        float v = (rmsdb + 2f) / 12f;
        rms = v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    // ==================== mesin animasi (murah) ====================

    private void aturAnimasi() {
        hentikanAnimasi();
        long durasi;
        switch (keadaan) {
            case MENDENGARKAN: durasi = 1000; break;   // gelombang pelan
            case BERPIKIR:     durasi = 800;  break;   // lompat bergantian
            case BICARA:       durasi = 640;  break;   // denyut serempak
            default:           // SIAP — redup, TANPA animator (nol biaya)
                skala[0] = skala[1] = skala[2] = skala[3] = 1f;
                setAlpha(0.72f);
                invalidate();
                return;
        }
        setAlpha(1f);
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(durasi);
        anim.setRepeatCount(ValueAnimator.INFINITE);
        anim.setRepeatMode(ValueAnimator.RESTART);
        anim.setInterpolator(new LinearInterpolator());
        anim.addUpdateListener(a -> {
            float v = (float) a.getAnimatedValue();
            switch (keadaan) {
                case MENDENGARKAN: {
                    // gelombang bergeser antar titik + denyut ikut suara
                    float gel = 0.72f + 0.30f * (1f + 0.55f * rms);
                    for (int i = 0; i < 4; i++) {
                        double ph = (v - i * 0.14) * Math.PI * 2.0;
                        skala[i] = (float) (gel * (0.62 + 0.38 * Math.sin(ph)));
                    }
                    break;
                }
                case BERPIKIR: {
                    // satu per satu naik — pola "mikir" ala Google
                    for (int i = 0; i < 4; i++) {
                        double ph = (v - i * 0.25) * Math.PI * 2.0;
                        double naik = Math.sin(ph);
                        skala[i] = (float) (1.0 + 0.55 * Math.max(0, naik));
                    }
                    break;
                }
                default: {   // BICARA — semua serempak bernafas
                    float d = (float) Math.abs(Math.sin(v * Math.PI * 2.0));
                    skala[0] = skala[1] = skala[2] = skala[3] = 0.82f + 0.30f * d;
                    break;
                }
            }
            invalidate();
        });
        anim.start();
    }

    private void hentikanAnimasi() {
        if (anim != null) { anim.cancel(); anim = null; }
    }

    @Override protected void onDetachedFromWindow() {
        hentikanAnimasi();   // jangan hidup di luar panel
        super.onDetachedFromWindow();
    }

    // ==================== gambar ====================

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float tinggi = getHeight() * 0.5f;
        float langkah = getWidth() / 5f;          // 4 titik + sisi
        float radius = tinggi * 0.42f;
        for (int i = 0; i < 4; i++) {
            kuas.setColor(WARNA[i]);
            float cx = langkah * (i + 1);
            canvas.drawCircle(cx, tinggi, radius * skala[i], kuas);
        }
    }
}
