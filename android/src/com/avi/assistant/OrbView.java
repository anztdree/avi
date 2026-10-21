package com.avi.assistant;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.util.AttributeSet;
import android.view.View;

/**
 * ARK REACTOR v2.4 — wajah asisten AVI bergaya reaktor JARVIS.
 *
 * TEMA BARU pilihan pemilik (A. "ARK REACTOR", disetujui 2026):
 * inti bola cair mengalir keluarga SIAN–TEAL, dikelilingi DUA CINCIN
 * TIPIS berputar yang mengisyaratkan keadaan:
 *   SIAP         cincin berputar paling pelan, pendaran tenang;
 *   MENDENGARKAN cincin ikut mengikuti kerasnya suara (RMS);
 *   BERPIKIR     cincin mempercepat, satelit mengorbit;
 *   BICARA       gelombang cepat mengikuti TTS.
 * Tanpa library apa pun — murni Canvas, murah untuk HP low-RAM
 * (shader inti di-cache; cincin = dua drawArc per frame).
 *
 * Dipakai dua jalur panel tahan-home (overlay_avisession) dan Mode
 * Live (activity_live) — identitas reaktor konsisten di mana pun.
 */
public class OrbView extends View {

    public static final int SIAP = 0;
    public static final int MENDENGARKAN = 1;
    public static final int BERPIKIR = 2;
    public static final int BICARA = 3;

    private int keadaan = SIAP;
    private int warna = 0xFF38BDF8;   // sian elektrik — identitas AVI

    // v2.4 ARK: inti mengalir keluarga sian–teal (bukan lagi campuran
    // ungu); putaran inti dipertahankan — terasa reaktor hidup.
    private static final int[] WARNA_ARK = {
            0xFFE0FBFF, 0xFF7DE8FF, 0xFF38BDF8, 0xFF2DD4BF, 0xFFE0FBFF };
    private boolean gradien = false;
    private SweepGradient sapuInti;          // cache — dibuat sekali per ukuran
    private int sapuLebar = -1;
    private final Matrix matriksSapu = new Matrix();

    private float rmsTarget = 0f;   // 0..1 dari onRmsChanged
    private float rmsLembut = 0f;   // smoothing agar gerak halus

    private final Paint kuas = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint kuasRing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF kotakRingLuar = new RectF();   // cincin luar
    private final RectF kotakRingDalam = new RectF();  // cincin dalam
    private int ringLebarCache = -1;

    private long mulaiNapas;

    public OrbView(Context c) { super(c); siap(); }
    public OrbView(Context c, AttributeSet a) { super(c, a); siap(); }
    public OrbView(Context c, AttributeSet a, int s) { super(c, a, s); siap(); }

    private void siap() {
        mulaiNapas = System.currentTimeMillis();
        kuas.setStyle(Paint.Style.FILL);
    }

    public void setKeadaan(int k) {
        if (keadaan == k) return;
        keadaan = k;
        if (k != MENDENGARKAN) { rmsTarget = 0f; }
    }

    public int getKeadaan() { return keadaan; }

    public void setWarnaOrb(int w) { warna = w; invalidate(); }

    /** Inti gradien berputar (lebih cepat saat BERPIKIR). */
    public void setGradienGoogle(boolean g) {
        if (gradien == g) return;
        gradien = g;
        invalidate();
    }

    /** Masukkan nilai RMS dari RecognitionListener.onRmsChanged (kisar -2..12 dB). */
    public void setRms(float rmsdB) {
        float n = (rmsdB + 2f) / 12f;
        if (n < 0) n = 0;
        if (n > 1) n = 1;
        rmsTarget = n;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float dasar = Math.min(cx, cy);
        if (dasar <= 0) { postInvalidateOnAnimation(); return; }

        float t = (System.currentTimeMillis() - mulaiNapas) / 1000f;
        rmsLembut += (rmsTarget - rmsLembut) * 0.18f;

        float denyut;
        int glowAlfa;
        switch (keadaan) {
            case MENDENGARKAN:
                denyut = 0.035f * (float) Math.sin(t * 3.4) + rmsLembut * 0.30f;
                glowAlfa = 70;
                break;
            case BERPIKIR:
                denyut = 0.055f * (float) Math.sin(t * 1.6);
                glowAlfa = 55;
                break;
            case BICARA:
                denyut = 0.075f * (float) Math.sin(t * 7.0);
                glowAlfa = 85;
                break;
            default: // SIAP
                denyut = 0.025f * (float) Math.sin(t * 1.3);
                glowAlfa = 40;
                break;
        }
        if (denyut < -0.09f) denyut = -0.09f;

        // ===== CINCIN REAKTOR (digambar dulu, di balik pendaran) =====
        gambarCincin(canvas, cx, cy, dasar, t);

        // lapisan luar: pendaran lembut
        float rLuar = dasar * (0.92f + denyut * 0.6f);
        kuas.setShader(new RadialGradient(cx, cy, rLuar,
                urai(warna, glowAlfa), urai(warna, 0), Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, rLuar, kuas);

        // lapisan tengah
        float rTengah = dasar * (0.62f + denyut * 0.5f);
        kuas.setShader(new RadialGradient(cx, cy, rTengah,
                urai(warna, 130), urai(warna, 20), Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, rTengah, kuas);

        // inti
        float rInti = dasar * (0.34f + denyut);
        if (gradien) {
            // shader di-CACHE (dibuat sekali per ukuran) — hanya matrix
            // rotasi yang diubah tiap frame, hemat di HP low-RAM
            if (sapuInti == null || sapuLebar != getWidth()) {
                sapuInti = new SweepGradient(cx, cy, WARNA_ARK, null);
                sapuLebar = getWidth();
            }
            float putaranDetik = keadaan == BERPIKIR ? 110f : 26f;
            matriksSapu.reset();
            matriksSapu.postRotate((t * putaranDetik) % 360f, cx, cy);
            sapuInti.setLocalMatrix(matriksSapu);
            kuas.setShader(sapuInti);
        } else {
            kuas.setShader(new RadialGradient(cx - rInti * 0.3f, cy - rInti * 0.35f,
                    rInti * 1.5f, terang(warna), warna, Shader.TileMode.CLAMP));
        }
        canvas.drawCircle(cx, cy, rInti, kuas);

        // satelit kecil saat berpikir (berputar)
        if (keadaan == BERPIKIR) {
            kuas.setShader(null);
            for (int i = 0; i < 3; i++) {
                double sudut = t * 2.2 + i * (Math.PI * 2 / 3);
                float sx = cx + (float) Math.cos(sudut) * dasar * 0.80f;
                float sy = cy + (float) Math.sin(sudut) * dasar * 0.80f;
                kuas.setColor(urai(warna, 180 - i * 40));
                canvas.drawCircle(sx, sy, dasar * 0.045f, kuas);
            }
        }

        postInvalidateOnAnimation();  // animasi terus sampai view tidak tampil
    }

    /** v2.4 ARK: dua cincin tipis berputar — jantung gaya reaktor.
     *  Kecepatan & terangnya mengikuti keadaan + RMS (murah: 2 drawArc). */
    private void gambarCincin(Canvas canvas, float cx, float cy,
                              float dasar, float t) {
        if (ringLebarCache != getWidth()) {
            float rLuar = dasar * 0.84f;
            float rDalam = dasar * 0.70f;
            kotakRingLuar.set(cx - rLuar, cy - rLuar, cx + rLuar, cy + rLuar);
            kotakRingDalam.set(cx - rDalam, cy - rDalam, cx + rDalam, cy + rDalam);
            ringLebarCache = getWidth();
        }

        float derajat, alfa;
        switch (keadaan) {
            case MENDENGARKAN:
                derajat = t * (70f + rmsLembut * 150f);
                alfa = 140 + rmsLembut * 80;
                break;
            case BERPIKIR:
                derajat = t * 150f;
                alfa = 175;
                break;
            case BICARA:
                derajat = t * (55f + 25f * (float) Math.sin(t * 5.0));
                alfa = 135;
                break;
            default: // SIAP
                derajat = t * 16f;
                alfa = 65;
                break;
        }

        kuasRing.setStyle(Paint.Style.STROKE);
        kuasRing.setStrokeCap(Paint.Cap.ROUND);
        kuasRing.setShader(null);

        // cincin luar: busur 96° searah jarum keadaan
        kuasRing.setStrokeWidth(Math.max(2f, dasar * 0.038f));
        kuasRing.setColor(urai(warna, Math.min(220, (int) alfa)));
        canvas.save();
        canvas.rotate(derajat % 360f, cx, cy);
        canvas.drawArc(kotakRingLuar, -90f, 96f, false, kuasRing);
        canvas.restore();

        // cincin dalam: busur 150° lawan arah, lebih tipis & redup
        kuasRing.setStrokeWidth(Math.max(1.5f, dasar * 0.022f));
        kuasRing.setColor(urai(warna, Math.min(160, (int) (alfa * 0.65f))));
        canvas.save();
        canvas.rotate(-(derajat * 0.62f + 130f) % 360f, cx, cy);
        canvas.drawArc(kotakRingDalam, -30f, 150f, false, kuasRing);
        canvas.restore();
    }

    private static int urai(int warna, int alfa) {
        return Color.argb(Math.max(0, Math.min(255, alfa)),
                Color.red(warna), Color.green(warna), Color.blue(warna));
    }

    private static int terang(int warna) {
        return campur(warna, 0xFFFFFFFF, 0.35f);
    }

    private static int campur(int a, int b, float t) {
        int r = Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t);
        int g = Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t);
        int bl = Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t);
        return Color.rgb(r, g, bl);
    }
}
