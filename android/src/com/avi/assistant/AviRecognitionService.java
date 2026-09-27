package com.avi.assistant;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.os.RemoteException;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognitionService;
import android.speech.SpeechRecognizer;

import java.util.List;

/**
 * PENGENAL SUARA AVI (RecognitionService).
 *
 * Kenapa kelas ini harus ada? Sejak Android 10, meta-data asisten
 * (voice-interaction-service) WAJIB mendeklarasikan
 * android:recognitionService. Tanpa itu VoiceInteractionServiceInfo gagal
 * parse ("No recognitionService specified") dan AVI DISARING KELUAR dari
 * daftar Pengaturan → Aplikasi default → Aplikasi asisten digital.
 * Inilah penyebab AVI tidak pernah muncul di daftar asisten HP
 * (terverifikasi dari kode framework AOSP Android 12, baris 145-148).
 *
 * Implementasinya PROXY cerdas: sesi mendengarkan diteruskan ke pengenal
 * suara bawaan perangkat (umumnya milik Google). Target dipilih EKSPLISIT
 * dan SELALU bukan diri sendiri, jadi tidak mungkin terjadi rekursi walau
 * AVI dijadikan asisten bawaan perangkat. Aplikasi AVI sendiri sudah
 * memegang izin RECORD_AUDIO, dan framework memeriksa izin itu untuk
 * setiap pemanggil sebelum menyampaikan permintaan ke sini.
 */
public class AviRecognitionService extends RecognitionService {

    /** Kunci Settings.Secure tempat sistem menyimpan pengenal suara bawaan. */
    private static final String KUNCI_PENGENAL_BAWAAN = "voice_recognition_service";

    /** Aksi layanan pengenal suara (RecognitionService.SERVICE_INTERFACE). */
    private static final String AKSI_PENGENAL = "android.speech.RecognitionService";

    /** Proxy sesi mendengarkan yang sedang aktif (null = tidak aktif). */
    private SpeechRecognizer proxy;

    /** Klien sistem yang sedang dilayani (null = tidak ada). */
    private Callback klien;

    // ------------------------------------------------------------------
    // Kontrak RecognitionService
    // ------------------------------------------------------------------

    @Override
    protected void onStartListening(Intent recognizerIntent, final Callback klienBaru) {
        klien = klienBaru;

        ComponentName target = pilihPengenal();
        if (target == null) {
            // Tidak ada pengenal lain di perangkat — tidak bisa memproses.
            galat(SpeechRecognizer.ERROR_CLIENT);
            return;
        }

        hentikanProxy();
        try {
            proxy = SpeechRecognizer.createSpeechRecognizer(this, target);
            proxy.setRecognitionListener(new Penghantar());
            proxy.startListening(recognizerIntent);
        } catch (Exception gagal) {
            hentikanProxy();
            galat(SpeechRecognizer.ERROR_CLIENT);
        }
    }

    @Override
    protected void onCancel(Callback klienBatal) {
        hentikanProxy();
        klien = null;
    }

    @Override
    protected void onStopListening(Callback klienStop) {
        if (proxy != null) {
            try {
                proxy.stopListening();
            } catch (Exception diabaikan) {
                // Proxy memang sudah mati — biarkan.
            }
        }
    }

    @Override
    public void onDestroy() {
        hentikanProxy();
        klien = null;
        super.onDestroy();
    }

    // ------------------------------------------------------------------
    // Pengantar peristiwa proxy → klien sistem
    // ------------------------------------------------------------------

    /** Menyambungkan setiap peristiwa pengenal bawaan ke klien sistem. */
    private class Penghantar implements RecognitionListener {
        @Override public void onReadyForSpeech(Bundle params) {
            Callback target = klien;
            if (target == null) return;
            try { target.readyForSpeech(params); } catch (RemoteException terputus) { }
        }
        @Override public void onBeginningOfSpeech() {
            Callback target = klien;
            if (target == null) return;
            try { target.beginningOfSpeech(); } catch (RemoteException terputus) { }
        }
        @Override public void onRmsChanged(float rmsdB) {
            Callback target = klien;
            if (target == null) return;
            try { target.rmsChanged(rmsdB); } catch (RemoteException terputus) { }
        }
        @Override public void onBufferReceived(byte[] buffer) {
            Callback target = klien;
            if (target == null) return;
            try { target.bufferReceived(buffer); } catch (RemoteException terputus) { }
        }
        @Override public void onEndOfSpeech() {
            Callback target = klien;
            if (target == null) return;
            try { target.endOfSpeech(); } catch (RemoteException terputus) { }
        }
        @Override public void onError(int error) {
            galat(error);
        }
        @Override public void onResults(Bundle results) {
            Callback target = klien;
            if (target == null) return;
            try { target.results(results); } catch (RemoteException terputus) { }
        }
        @Override public void onPartialResults(Bundle partialResults) {
            Callback target = klien;
            if (target == null) return;
            try { target.partialResults(partialResults); } catch (RemoteException terputus) { }
        }
        @Override public void onEvent(int eventType, Bundle params) {
            // Tidak ada peristiwa tambahan yang diproksikan.
        }
    }

    // ------------------------------------------------------------------
    // Pemilihan target proxy & utilitas
    // ------------------------------------------------------------------

    /**
     * Pilih pengenal suara yang akan diproksikan: prioritas pertama adalah
     * pengenal bawaan perangkat, dengan SYARAT bukan diri sendiri (anti-
     * rekursi). Kalau bawaan tidak bisa dipakai, cari pengenal lain milik
     * aplikasi yang berbeda.
     */
    private ComponentName pilihPengenal() {
        ComponentName diri = new ComponentName(this, getClass());

        // 1) Pengenal bawaan perangkat (umumnya Google) — pakai bila bukan kita.
        try {
            String bawaan = Settings.Secure.getString(
                    getContentResolver(), KUNCI_PENGENAL_BAWAAN);
            ComponentName cn = bawaan == null ? null : ComponentName.unflattenFromString(bawaan);
            if (cn != null && !cn.equals(diri)) {
                return cn;
            }
        } catch (Exception diabaikan) {
            // Setting tidak terbaca — lanjut ke pencarian alternatif.
        }

        // 2) Pengenal lain mana pun milik aplikasi berbeda.
        try {
            PackageManager pm = getPackageManager();
            List<ResolveInfo> daftar = pm.queryIntentServices(
                    new Intent(AKSI_PENGENAL), 0);
            if (daftar != null) {
                for (ResolveInfo ri : daftar) {
                    if (ri == null || ri.serviceInfo == null) continue;
                    ComponentName cn = new ComponentName(
                            ri.serviceInfo.packageName, ri.serviceInfo.name);
                    if (!cn.equals(diri)) {
                        return cn;
                    }
                }
            }
        } catch (Exception diabaikan) {
            // Tidak ada yang bisa diproksikan.
        }
        return null;
    }

    /** Kirim kode galat ke klien, kalau masih ada. */
    private void galat(int kode) {
        Callback target = klien;
        if (target == null) return;
        try { target.error(kode); } catch (RemoteException terputus) { }
    }

    /** Matikan proxy dengan aman (urutan: stop → destroy → null). */
    private void hentikanProxy() {
        SpeechRecognizer lama = proxy;
        proxy = null;
        if (lama == null) return;
        try {
            lama.stopListening();
        } catch (Exception diabaikan) {
            // Sudah mati.
        }
        try {
            lama.destroy();
        } catch (Exception diabaikan) {
            // Sudah mati.
        }
    }
}
