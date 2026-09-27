package com.avi.assistant;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.util.AttributeSet;
import android.view.View;

/**
 * Orb AVI — bola cair ala Siri sebagai "wajah" asisten.
 * Empat keadaan: SIAP (bernapas pelan), MENDENGARKAN (berdenyut mengikuti
 * kerasnya suara via onRmsChanged), BERPIKIR (berputar pelan + satelit),
 * BICARA (gelombang cepat). Tanpa library apa pun — murni Canvas.
 */
public class OrbView extends View {

    public static final int SIAP = 0;
    public static final int MENDENGARKAN = 1;
    public static final int BERPIKIR = 2;
    public static final int BICARA = 3;

    private int keadaan = SIAP;
    private int warna = 0xFF38BDF8;   // sian elektrik — identitas Arc

    // b17: mode GRADIEN ala Google Assistant — inti bola mengalir empat
    // warna (biru muda → sian → indigo → ungu) dan berputar pelan;
    // tahan tombol home terasa "asisten Google sungguhan".
    private static final int[] WARNA_GOOGLE = {
            0xFF7DD3FC, 0xFF38BDF8, 0xFF6366F1, 0xFFA855F7, 0xFF7DD3FC };
    private boolean gradien = false;
    private SweepGradient sapuInti;          // cache — dibuat sekali per ukuran
    private int sapuLebar = -1;
    private final Matrix matriksSapu = new Matrix();

    private float rmsTarget = 0f;   // 0..1 dari onRmsChanged
    private float rmsLembut = 0f;   // smoothing agar gerak halus

    private final Paint kuas = new Paint(Paint.ANTI_ALIAS_FLAG);
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

    /** b17: nyalakan inti gradien ala Google Assistant (berputar pelan,
     *  lebih cepat saat BERPIKIR). Cahaya luar tetap sian khas AVI. */
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
            // b17: shader di-CACHE (dibuat sekali per ukuran) — hanya matrix
            // rotasi yang diubah tiap frame, hemat di HP low-RAM
            if (sapuInti == null || sapuLebar != getWidth()) {
                sapuInti = new SweepGradient(cx, cy, WARNA_GOOGLE, null);
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
