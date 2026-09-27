package com.avi.assistant;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * PAPAN PESAN BERSAMA — jantung aturan pemilik (2026-09-24):
 * "Tahan tombol home dan bicara langsung dari aplikasi AVI itu adalah
 * SATU papan pesan yang sama."
 *
 * Kelas ini menggambar riwayat percakapan YANG SAMA (AviBrain.muatRiwayat —
 * penyimpanan tunggal aplikasi) ke dalam Mode Live (LiveActivity) dan
 * papan di aplikasi AVI (b18: panel asisten tahan-home ala Google
 * tidak lagi menggambar riwayat — riwayat tetap SATU penyimpanan).
 *
 * Sengaja murni Java + View standar (hukum proyek: tanpa dependensi).
 * Lembar compact hanya menampilkan beberapa pesan terakhir supaya ringan
 * di perangkat low-RAM; papan lengkap tetap ada di aplikasi AVI.
 */
public final class PapanPesan {

    /** Berapa pesan terakhir yang digambar di lembar compact. */
    private static final int MAKS_BARIS = 8;
    /** Batas tinggi papan di lembar compact (dp) — sisanya digulir.
     *  b17: 196 → 120 — panel ala Google harus RINGKAS; papan lengkap
     *  tetap ada di aplikasi AVI (satu penyimpanan yang sama). */
    private static final int TINGGI_MAKS_DP = 120;

    private PapanPesan() {}

    /** gaya = true → palet gelap sesi (bubble AVI pakai kartu_jawaban). */
    public static void render(Context c, LinearLayout papan, ScrollView gulir,
                              boolean gayaGelap) {
        if (papan == null) return;
        papan.removeAllViews();
        List<Msg> daftar = AviBrain.muatRiwayat(c);
        int mulai = Math.max(0, daftar.size() - MAKS_BARIS);
        int diGambar = 0;
        for (int i = mulai; i < daftar.size(); i++) {
            if (daftar.get(i).teks.trim().isEmpty()) continue;
            papan.addView(baris(c, daftar.get(i), gayaGelap, diGambar++ > 0));
        }
        if (diGambar == 0) {
            TextView kosong = new TextView(c);
            kosong.setText("Satu papan pesan dengan aplikasi AVI — "
                    + "obrolan di sini dan di sana menyambung, "
                    + AviBrain.namaPemilik(c) + ".");
            kosong.setTextSize(12f);
            kosong.setGravity(Gravity.CENTER);
            kosong.setTextColor(gayaGelap ? 0xFF8FA3C8 : 0xFF6B7A99);
            kosong.setPadding(dip(c, 8), dip(c, 6), dip(c, 8), dip(c, 6));
            papan.addView(kosong);
        }
        if (gulir != null) {
            gulir.post(() -> {
                // klem tinggi papan: sempit bila isinya sedikit, maksimum
                // TINGGI_MAKS_DP bila riwayat panjang (sisanya digulir)
                View isi = gulir.getChildAt(0);
                int tinggiIsi = isi == null ? 0 : isi.getMeasuredHeight();
                if (tinggiIsi > 0) {
                    int maks = dip(c, TINGGI_MAKS_DP);
                    gulir.getLayoutParams().height =
                            Math.min(Math.max(tinggiIsi, dip(c, 44)), maks);
                    gulir.requestLayout();
                }
                gulir.fullScroll(View.FOCUS_DOWN);
            });
        }
    }

    /** Satu gelembung pesan. pertama dipakai untuk margin atas baris. */
    private static LinearLayout baris(Context c, Msg m, boolean gelap,
                                      boolean pertama) {
        LinearLayout baris = new LinearLayout(c);
        baris.setOrientation(LinearLayout.HORIZONTAL);
        baris.setGravity(m.dariUser ? Gravity.END : Gravity.START);
        LinearLayout.LayoutParams lpBaris = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lpBaris.topMargin = dip(c, pertama ? 5 : 0);
        baris.setLayoutParams(lpBaris);

        TextView gelembung = new TextView(c);
        gelembung.setText(m.teks);
        gelembung.setTextSize(13f);
        gelembung.setLineSpacing(0, 1.2f);
        gelembung.setMaxWidth(dip(c, 268));
        gelembung.setMaxLines(5);
        gelembung.setEllipsize(android.text.TextUtils.TruncateAt.END);
        gelembung.setPadding(dip(c, 13), dip(c, 8), dip(c, 13), dip(c, 8));

        if (m.dariUser) {
            gelembung.setBackgroundResource(R.drawable.bubble_user);
            gelembung.setTextColor(0xFFFFFFFF);
        } else if (gelap) {
            gelembung.setBackgroundResource(R.drawable.kartu_jawaban);
            gelembung.setTextColor(0xFFF2F7FF);
        } else {
            gelembung.setBackgroundResource(R.drawable.bubble_avi);
            gelembung.setTextColor(c.getResources()
                    .getColor(R.color.avi_teks, c.getTheme()));
        }
        baris.addView(gelembung);
        return baris;
    }

    /** Pemisah halus antara riwayat lama dan giliran yang sedang berjalan. */
    public static View pemisahHidup(Context c, boolean gelap) {
        View v = new View(c);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dip(c, 1));
        lp.topMargin = dip(c, 7);
        lp.bottomMargin = dip(c, 2);
        v.setLayoutParams(lp);
        GradientDrawable g = new GradientDrawable();
        g.setColor(gelap ? Color.parseColor("#265EC8F8")
                : Color.parseColor("#14000000"));
        v.setBackground(g);
        return v;
    }

    private static int dip(Context c, int d) {
        return Math.round(d * c.getResources().getDisplayMetrics().density);
    }
}
