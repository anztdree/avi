package com.avi.assistant;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Bundle;

/**
 * PEMINTA IZIN MIC (b21) — activity TRANSPARAN yang hidup sebentar hanya
 * untuk meminta RECORD_AUDIO, dibuka dari panel asisten saat dibuka.
 *
 * Kenapa perlu: AviSession (VoiceInteractionSession) TIDAK bisa
 * requestPermissions, dan dulu TIDAK ADA SATU PUN yang meminta izin ini
 * di jalur panel — akibatnya mesin mati diam (mulai() return tanpa suara,
 * status tak tampil) dan pemilik merasa "mic tidak langsung bekerja".
 *
 * Begitu izin diberikan, panel langsung dinyalakan ulang lewat
 * {@link #saatDiizinkan} (proses sama — statik, sengaja sederhana):
 * mic menyala OTOMATIS tanpa menyentuh apa pun. Ditolak → panel tetap
 * hidup lewat jalur ketik.
 */
public class MintaIzinMicActivity extends Activity {

    /** Dipasang panel yang meminta; dipanggil SATU kali bila diizinkan. */
    public static Runnable saatDiizinkan;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            beres();
            finish();          // izin sudah ada — tidak mengganggu layar
            return;
        }
        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 11);
    }

    @Override
    public void onRequestPermissionsResult(int kode, String[] izin, int[] hasil) {
        super.onRequestPermissionsResult(kode, izin, hasil);
        Runnable r = saatDiizinkan;
        saatDiizinkan = null;
        if (kode == 11 && r != null && hasil.length > 0
                && hasil[0] == PackageManager.PERMISSION_GRANTED) r.run();
        finish();              // transparan — pulang seketika, panel tetap di bawah
    }

    @Override
    protected void onDestroy() {
        saatDiizinkan = null;  // jangan sisakan referensi panel lama
        super.onDestroy();
    }

    private static void beres() {
        Runnable r = saatDiizinkan;
        saatDiizinkan = null;
        if (r != null) r.run();
    }
}
