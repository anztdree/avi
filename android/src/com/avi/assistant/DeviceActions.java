package com.avi.assistant;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.net.Uri;
import android.provider.AlarmClock;

import java.util.List;

/**
 * Tangan AVI: mengeksekusi aksi perangkat yang diminta model lewat tag
 * [AKSI:nama|param]. Semua aksi aman — tanpa izin berat, tanpa server.
 *
 * Nama aksi:
 *   alarm   — param: jam|menit[|label]      contoh: [AKSI:alarm|6|30|Bangun]
 *   timer   — param: detik[|label]          contoh: [AKSI:timer|300|Teh]
 *   senter  — param: nyala|mati             contoh: [AKSI:senter|nyala]
 *   web     — param: url                    contoh: [AKSI:web|https://...]
 *   aplikasi— param: nama aplikasi          contoh: [AKSI:aplikasi|kalkulator]
 */
public final class DeviceActions {

    private DeviceActions() {}

    /** Jalankan satu aksi; hasilnya teks konfirmasi untuk ditampilkan. */
    public static String jalankan(Context ctx, String nama, String[] param) {
        try {
            switch (nama == null ? "" : nama.toLowerCase()) {
                case "alarm":
                    return aksiAlarm(ctx, param);
                case "timer":
                    return aksiTimer(ctx, param);
                case "senter":
                    return aksiSenter(ctx, param);
                case "web":
                    return aksiWeb(ctx, param);
                case "aplikasi":
                    return aksiAplikasi(ctx, param);
                default:
                    return "Aksi tidak dikenal: " + nama;
            }
        } catch (Exception e) {
            return "Aksi gagal: " + e.getMessage();
        }
    }

    private static String aksiAlarm(Context ctx, String[] p) {
        int jam = angka(p, 0, 6);
        int menit = angka(p, 1, 0);
        Intent it = new Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, jam)
                .putExtra(AlarmClock.EXTRA_MINUTES, menit)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true);
        if (p != null && p.length > 2 && !p[2].trim().isEmpty()) {
            it.putExtra(AlarmClock.EXTRA_MESSAGE, p[2].trim());
        }
        ctx.startActivity(it);
        return String.format("Alarm dipasang pukul %02d.%02d.", jam, menit);
    }

    private static String aksiTimer(Context ctx, String[] p) {
        int detik = angka(p, 0, 60);
        Intent it = new Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, detik)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true);
        if (p != null && p.length > 1 && !p[1].trim().isEmpty()) {
            it.putExtra(AlarmClock.EXTRA_MESSAGE, p[1].trim());
        }
        ctx.startActivity(it);
        if (detik >= 60) return "Timer dipasang: " + (detik / 60) + " menit.";
        return "Timer dipasang: " + detik + " detik.";
    }

    private static String aksiSenter(Context ctx, String[] p) throws Exception {
        boolean nyala = !(p != null && p.length > 0 && "mati".equalsIgnoreCase(p[0]));
        CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
        String id = null;
        for (String cid : cm.getCameraIdList()) {
            Boolean lentera = cm.getCameraCharacteristics(cid)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
            Integer menghadap = cm.getCameraCharacteristics(cid)
                    .get(CameraCharacteristics.LENS_FACING);
            if (Boolean.TRUE.equals(lentera)
                    && menghadap != null && menghadap == CameraCharacteristics.LENS_FACING_BACK) {
                id = cid;
                break;
            }
        }
        if (id == null) return "Ponsel ini tidak punya senter.";
        cm.setTorchMode(id, nyala);
        return nyala ? "Senter menyala." : "Senter dimatikan.";
    }

    private static String aksiWeb(Context ctx, String[] p) {
        String url = p != null && p.length > 0 ? p[0].trim() : "";
        if (url.isEmpty()) return "Alamat web kosong.";
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://" + url;
        ctx.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        return "Membuka " + url;
    }

    private static String aksiAplikasi(Context ctx, String[] p) {
        String cari = p != null && p.length > 0 ? p[0].trim().toLowerCase() : "";
        if (cari.isEmpty()) return "Nama aplikasinya belum disebut.";
        PackageManager pm = ctx.getPackageManager();
        List<android.content.pm.ApplicationInfo> daftar =
                pm.getInstalledApplications(PackageManager.GET_META_DATA);
        for (android.content.pm.ApplicationInfo info : daftar) {
            String label = String.valueOf(pm.getApplicationLabel(info)).toLowerCase();
            if (label.contains(cari)) {
                Intent buka = pm.getLaunchIntentForPackage(info.packageName);
                if (buka != null) {
                    buka.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    ctx.startActivity(buka);
                    return "Membuka " + label + ".";
                }
            }
        }
        return "Aplikasi \"" + cari + "\" tidak ditemukan.";
    }

    private static int angka(String[] p, int i, int bawaan) {
        if (p == null || p.length <= i) return bawaan;
        try { return Integer.parseInt(p[i].trim()); } catch (Exception e) { return bawaan; }
    }
}
