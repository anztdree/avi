package com.avi.assistant;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * v2.1: begitu HP menyala, penjaga kecepatan langsung dinyalakan —
 * panggilan tahan-home yang PERTAMA pun sudah hangat, tanpa cold
 * start (tanpa lingkaran putih yang menganga lama).
 *
 * Transsion/itel menyiarkan juga QUICKBOOT_POWERON (boot cepat) —
 * ikut ditangkap agar tidak ada celah.
 */
public class PenerimaBoot extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        String a = i == null ? null : i.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(a)
                || "android.intent.action.QUICKBOOT_POWERON".equals(a)) {
            LayananJaga.nyalakan(c);
        }
    }
}
