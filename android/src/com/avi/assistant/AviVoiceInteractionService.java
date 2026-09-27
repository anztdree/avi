package com.avi.assistant;

import android.service.voice.VoiceInteractionService;

/**
 * Titik masuk ASISTEN PERANGKAT AVI (VoiceInteractionService).
 *
 * Lewat layanan inilah AVI muncul di Pengaturan → Aplikasi default →
 * Aplikasi asisten digital, dan bisa dipanggil dengan MENAHAN TOMBOL HOME
 * (navigasi tombol) atau menyapu dari sudut kiri/kanan bawah (navigasi
 * gestur) — persis cara memanggil Google Assistant.
 *
 * Tidak ada pemindaian apa pun di sini: pemicu dipegang sistem Android,
 * jadi tidak ada layanan latar yang berjalan terus dan baterai tetap aman
 * — "tidak bekerja 24 jam, tapi siap dipanggil 24 jam".
 */
public class AviVoiceInteractionService extends VoiceInteractionService {
    @Override
    public void onReady() {
        // sengaja kosong: tanpa hotword detector — pemicu murni lewat sistem.
    }
}
