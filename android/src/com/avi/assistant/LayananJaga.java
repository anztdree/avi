package com.avi.assistant;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/**
 * PENJAGA KECEPATAN v2.1 — akar keluhan pemilik:
 * "lingkaran timbul saat tombol home di tahan, itu tuh DELAY LAMA!"
 *
 * Dulu: tahan home → sistem meluncurkan AVI → proses DINGIN (harus
 * lahir dulu di HP low-RAM, 1-3 detik) → SElama menunggu itu, badge
 * lingkaran putih SystemUI menganga di atas tombol home. Terasa lama
 * dan mengganggu.
 *
 * Kini: layanan ini TIDAK mengerjakan apa pun — cukup menjaga proses
 * AVI tetap HANGAT di memori (pola yang sama dipakai Google
 * Assistant), sehingga panel muncul hampir seketika dan badge sistem
 * hanya berkedip sepersekian detik.
 *
 * Nol sensor, nol wake-lock, nol pemindaian, nol jaringan: konsumsi
 * baterai praktis nol. Notifikasi IMPORTANCE_MIN = tanpa suara,
 * tanpa getar, tidak muncul di status bar — hanya senyap di laci.
 */
public class LayananJaga extends Service {

    static final int ID_NOTIF = 21;
    private static final String KANAL = "avi_jaga";

    /** Nyalakan penjaga — aman dipanggil berulang (idempoten). */
    static void nyalakan(Context c) {
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
    public int onStartCommand(Intent intent, int flags, int startId) {
        keDalamForeground();
        // START_STICKY: bila ROM mematikan, sistem bangkitkan sendiri.
        return START_STICKY;
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
                .setShowWhen(false);

        Notification n = b.build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(ID_NOTIF, n,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(ID_NOTIF, n);
        }
    }
}
