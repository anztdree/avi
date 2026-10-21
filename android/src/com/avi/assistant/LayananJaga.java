package com.avi.assistant;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;

/**
 * PENJAGA KECEPATAN v2.4 — menjaga proses AVI hangat agar panel
 * tahan-home muncul seketika (tanpa cold start 1-3 detik di HP low-RAM).
 *
 * AMANDEMEN PEMILIK (v2.4): "hilangkan status bar soal avi karena itu
 * cuma ganggu .. kecuali emang proses berhasil boleh".
 *
 * Maka aturannya kini:
 * 1. MODE PENUH aktif (asisten ponsel = layanan suara AVI) → penjaga
 *    TIDAK diperlukan: sistem sendiri mem-binding layanan suara dan
 *    proses tetap hangat TANPA FGS → TIDAK ADA notifikasi sama sekali.
 *    nyalakan() justru MEMATIKAN penjaga yang mungkin masih jalan.
 * 2. Jalur activity fallback → penjaga tetap hidup (prosesnya memang
 *    diperlukan) dan notifikasi dibikin senyap-dalam: IMPORTANCE_MIN +
 *    VISIBILITY_SECRET — tanpa suara, tanpa ikon status bar, tak tampil
 *    di layar kunci, hanya senyap di dasar laci.
 *
 * Nol sensor, nol wake-lock, nol pemindaian, nol jaringan: baterai aman.
 */
public class LayananJaga extends Service {

    static final int ID_NOTIF = 21;
    private static final String KANAL = "avi_jaga";

    /** v2.4: status hidup penjaga — dibaca kartu "Izin & Kesehatan". */
    static volatile boolean JALAN = false;

    /** Mode Penuh = asisten ponsel menunjuk layanan suara AVI (jalur
     *  sesi ala Google Assistant — tanpa orb/lingkaran putih). */
    static boolean modePenuh(Context c) {
        try {
            ComponentName vis = new ComponentName(c, AviVoiceInteractionService.class);
            String a = Settings.Secure.getString(
                    c.getContentResolver(), "assistant");
            ComponentName ca = a == null ? null
                    : ComponentName.unflattenFromString(a);
            String v = Settings.Secure.getString(
                    c.getContentResolver(), "voice_interaction_service");
            ComponentName cv = v == null ? null
                    : ComponentName.unflattenFromString(v);
            return vis.equals(ca) && vis.equals(cv);
        } catch (Exception e) {
            return false;
        }
    }

    /** Nyalakan penjaga — aman dipanggil berulang (idempoten).
     *  Mode Penuh aktif = penjaga justru DIMATIKAN (tidak diperlukan). */
    static void nyalakan(Context c) {
        if (modePenuh(c)) {
            try { c.stopService(new Intent(c, LayananJaga.class)); }
            catch (Exception ignored) { }
            return;
        }
        Intent it = new Intent(c, LayananJaga.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(it);
            else c.startService(it);
        } catch (Exception ignored) {
            // ROM agresif kadang menolak start di latar — receiver boot
            // dan buka-app berikutnya mencoba lagi.
        }
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        JALAN = true;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (modePenuh(this)) {
            // Mode Penuh baru saja aktif sementara penjaga masih jalan —
            // pamit seketika: status bar kembali BERSIH dari AVI.
            JALAN = false;
            try { stopForeground(true); } catch (Exception ignored) { }
            stopSelf();
            return START_NOT_STICKY;
        }
        keDalamForeground();
        // START_STICKY: bila ROM mematikan, sistem bangkitkan sendiri.
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        JALAN = false;
        super.onDestroy();
    }

    private void keDalamForeground() {
        NotificationManager nm = (NotificationManager)
                getSystemService(Context.NOTIFICATION_SERVICE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(KANAL,
                    "AVI siap dipanggil", NotificationManager.IMPORTANCE_MIN);
            ch.setSound(null, null);
            ch.enableVibration(false);
            ch.setShowBadge(false);
            ch.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
            ch.setDescription(
                    "Menjaga AVI agar muncul seketika saat tombol home ditahan.");
            nm.createNotificationChannel(ch);
            b = new Notification.Builder(this, KANAL);
        } else {
            b = new Notification.Builder(this)
                    .setPriority(Notification.PRIORITY_MIN);
        }
        b.setSmallIcon(R.drawable.ic_mic)
                .setContentTitle("AVI siap dipanggil")
                .setContentText("Tahan tombol home — AVI muncul seketika.")
                .setOngoing(true)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_SECRET);

        Notification n = b.build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(ID_NOTIF, n,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(ID_NOTIF, n);
        }
    }
}
