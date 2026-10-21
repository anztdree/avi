package com.avi.assistant;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * v2.1: begitu HP menyala, penjaga kecepatan langsung dinyalakan —
 * panggilan tahan-home yang PERTAMA pun sudah hangat, tanpa cold
 * start (tanpa lingkaran putih yang menganga lama).
 *
 * v2.2 (laporan pemilik: "masih muncul bubble-nya"): dulu penjaga
 * baru nyala saat app DIBUKA — pemilik yang memasang update lalu
 * langsung tahan home masih kena proses dingin. Kini penjaga nyala
 * SEKETIKA update terpasang lewat MY_PACKAGE_REPLACED (broadcast
 * khusus ke app sendiri, tanpa izin, tanpa perlu dibuka).
 *
 * Transsion/itel menyiarkan juga QUICKBOOT_POWERON (boot cepat) —
 * ikut ditangkap agar tidak ada celah.
 */
public class PenerimaBoot extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        String a = i == null ? null : i.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(a)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)
                || "android.intent.action.QUICKBOOT_POWERON".equals(a)) {
            LayananJaga.nyalakan(c);
        }
    }
}
