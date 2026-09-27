package com.avi.assistant;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.UnknownHostException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Otak AVI: bicara langsung dengan penyedia AI lewat API key milik pemilik
 * (Gemini / NVIDIA NIM / OpenRouter — semua OpenAI-compatible).
 * Tidak ada server perantara sama sekali.
 */
public final class AviBrain {

    /** Model Gemini yang teruji cepat — disematkan sebagai rekomendasi teratas. */
    public static final String MODEL_REKOMENDASI_GEMINI = "gemini-flash-lite-latest";

    /** Callback yang DIJAMIN dipanggil TEPAT SEKALI dengan teks tak-null. */
    public interface Balas { void selesai(String teks); }
    /** Callback streaming: token dipanggil berkali-kali, selesai TEPAT SEKALI. */
    public interface StreamBalas {
        void token(String teksSejauhIni);
        void selesai(String teksAkhir);
    }
    public interface DaftarModel { void selesai(List<String> model, String galat); }

    private static final Handler UTAMA = new Handler(Looper.getMainLooper());
    private static final int MAKS_RIWAYAT_TERSIMPAN = 200;
    private static final int TIMEOUT_HUBUNG = 12000;
    private static final int TIMEOUT_BACA = 120000;

    private static final Pattern POLA_AKSI =
            Pattern.compile("\\[AKSI:([^\\]]*)\\]", Pattern.CASE_INSENSITIVE);

    private AviBrain() {}

    // ============================ preferensi ============================

    public static SharedPreferences pref(Context c) {
        return c.getApplicationContext().getSharedPreferences("avi", Context.MODE_PRIVATE);
    }

    public static String penyedia(Context c) {
        return pref(c).getString("penyedia", "gemini");
    }

    public static String namaPenyedia(Context c) {
        switch (penyedia(c)) {
            case "nvidia": return "NVIDIA NIM";
            case "openrouter": return "OpenRouter";
            default: return "Google Gemini";
        }
    }

    public static String dasarUrl(Context c) {
        switch (penyedia(c)) {
            case "nvidia": return "https://integrate.api.nvidia.com/v1/";
            case "openrouter": return "https://openrouter.ai/api/v1/";
            default: return "https://generativelanguage.googleapis.com/v1beta/openai/";
        }
    }

    public static boolean apiKeyAktif(Context c) {
        return !pref(c).getString("apikey." + penyedia(c), "").trim().isEmpty();
    }

    public static String modelAktif(Context c) {
        return pref(c).getString("model." + penyedia(c), "").trim();
    }

    public static String namaPemilik(Context c) {
        String n = pref(c).getString("nama_pemilik", "").trim();
        return n.isEmpty() ? "Sir" : n;
    }

    // ============================ persona ============================

    public static String persona(Context c) {
        String nama = namaPemilik(c);
        return "Kamu AVI — asisten AI pribadi yang tertanam di ponsel majikanmu. "
                + "Kepribadian: elegan, hangat, sedikit jenaka ala JARVIS; cakap dan hormat, tidak bertele-tele. "
                + "Panggil majikanmu \"" + nama + "\". Gunakan bahasa Indonesia yang baik "
                + "(ikuti bahasa majikan bila ia memakai bahasa lain). "
                + "Jawab ringkas dan padat; bila diminta detail, boleh lebih panjang dan terstruktur. "
                + "Kamu AVI — jangan mengaku sebagai produk AI lain. "
                + "SELALU akhiri jawaban dengan satu pertanyaan lanjutan singkat, misalnya: "
                + "\"Ada lagi yang bisa AVI bantu, " + nama + "?\" "
                + "Waktu di tempat majikan sekarang: " + sebutWaktu() + ".\n"
                + "Kamu bisa mengendalikan sebagian perangkat majikan dengan menaruh tag "
                + "[AKSI:nama|param] di akhir jawaban. Daftar aksi: "
                + "[AKSI:alarm|jam|menit|label-opsional], [AKSI:timer|detik|label-opsional], "
                + "[AKSI:senter|nyala atau mati], [AKSI:web|url], [AKSI:aplikasi|nama-aplikasi]. "
                + "Aturan aksi: jelaskan dulu secara singkat, lalu letakkan tag di baris terpisah "
                + "di akhir jawaban; jam pakai angka 0-23.";
    }

    static String cobaLokal(Context c, String tanya) {
        String t = tanya.toLowerCase();
        Locale id = new Locale("id", "ID");
        if (t.contains("jam berapa") || t.contains("pukul berapa")) {
            return "Sekarang pukul " + new SimpleDateFormat("HH.mm", id).format(new Date())
                    + ", " + namaPemilik(c) + ". Ada lagi yang bisa AVI bantu?";
        }
        if (t.contains("hari ini tanggal") || t.matches(".*tanggal berapa.*")) {
            return "Hari ini " + new SimpleDateFormat("EEEE, d MMMM yyyy", id).format(new Date())
                    + ". Ada lagi yang bisa AVI bantu?";
        }
        return null;
    }

    private static String sebutWaktu() {
        Locale id = new Locale("id", "ID");
        Calendar k = Calendar.getInstance();
        int h = k.get(Calendar.HOUR_OF_DAY);
        String ket = (h >= 4 && h < 11) ? "pagi"
                : (h >= 11 && h < 15) ? "siang"
                : (h >= 15 && h < 19) ? "sore" : "malam";
        return new SimpleDateFormat("EEEE, d MMMM yyyy • HH:mm", id).format(k.getTime())
                + " (" + ket + ")";
    }

    // ============================ tanya (utuh) ============================

    public static void tanya(final Context ctx, final String pertanyaan, final Balas balas) {
        String lokal = cobaLokal(ctx, pertanyaan);
        if (lokal != null) {
            simpanRiwayat(ctx, new Msg(pertanyaan, true));
            simpanRiwayat(ctx, new Msg(lokal, false));
            UTAMA.post(() -> balas.selesai(lokal));
            return;
        }
        if (!apiKeyAktif(ctx)) {
            String m = "API key belum dipasang, " + namaPemilik(ctx) + ". "
                    + "Buka Pengaturan → pilih penyedia → tempel API key.";
            UTAMA.post(() -> balas.selesai(m));
            return;
        }
        final String model = modelAktif(ctx);
        if (model.isEmpty()) {
            String m = "Modelnya belum dipilih, " + namaPemilik(ctx) + ". "
                    + "Buka Pengaturan → Model → Ambil & pilih model.";
            UTAMA.post(() -> balas.selesai(m));
            return;
        }
        final String key = pref(ctx).getString("apikey." + penyedia(ctx), "").trim();

        new Thread(() -> {
            String hasil;
            boolean sukses = false;
            HttpURLConnection c = null;
            try {
                JSONArray pesan = susunPesan(ctx, pertanyaan);
                JSONObject badan = new JSONObject()
                        .put("model", model)
                        .put("messages", pesan);

                c = (HttpURLConnection) new URL(dasarUrl(ctx) + "chat/completions").openConnection();
                c.setRequestMethod("POST");
                c.setConnectTimeout(TIMEOUT_HUBUNG);
                c.setReadTimeout(TIMEOUT_BACA);
                c.setRequestProperty("Authorization", "Bearer " + key);
                c.setRequestProperty("Content-Type", "application/json");
                c.setDoOutput(true);
                OutputStream os = c.getOutputStream();
                os.write(badan.toString().getBytes(StandardCharsets.UTF_8));
                os.flush();
                os.close();

                int kode = c.getResponseCode();
                if (kode != 200) {
                    hasil = pesanRamah(kode, bacaStream(c.getErrorStream()));
                } else {
                    JSONObject jawaban = new JSONObject(bacaStream(c.getInputStream()));
                    String isi = ambilIsiBalasan(jawaban);
                    hasil = bersihkanDanJalankanAksi(ctx, isi);
                    if (hasil.trim().isEmpty()) {
                        hasil = "(AVI menerima balasan kosong dari model — silakan coba ulangi, "
                                + namaPemilik(ctx) + ".)";
                    }
                    sukses = true;
                }
            } catch (Exception e) {
                hasil = "Gagal menghubungi " + namaPenyedia(ctx) + ": " + ramahkanException(e);
            } finally {
                if (c != null) c.disconnect();
            }

            final String akhir = hasil;
            if (sukses) {
                simpanRiwayat(ctx, new Msg(pertanyaan, true));
                simpanRiwayat(ctx, new Msg(akhir, false));
            }
            UTAMA.post(() -> balas.selesai(akhir));
        }).start();
    }

    // ============================ tanya (streaming) ============================

    public static void tanyaStream(final Context ctx, final String pertanyaan,
                                   final StreamBalas balas) {
        String lokal = cobaLokal(ctx, pertanyaan);
        if (lokal != null) {
            simpanRiwayat(ctx, new Msg(pertanyaan, true));
            simpanRiwayat(ctx, new Msg(lokal, false));
            UTAMA.post(() -> { balas.token(lokal); balas.selesai(lokal); });
            return;
        }
        if (!apiKeyAktif(ctx)) {
            String m = "API key belum dipasang, " + namaPemilik(ctx) + ". "
                    + "Buka Pengaturan → pilih penyedia → tempel API key.";
            UTAMA.post(() -> balas.selesai(m));
            return;
        }
        final String model = modelAktif(ctx);
        if (model.isEmpty()) {
            String m = "Modelnya belum dipilih, " + namaPemilik(ctx) + ". "
                    + "Buka Pengaturan → Model → Ambil & pilih model.";
            UTAMA.post(() -> balas.selesai(m));
            return;
        }
        final String key = pref(ctx).getString("apikey." + penyedia(ctx), "").trim();

        new Thread(() -> {
            StringBuilder mentah = new StringBuilder();
            boolean sukses = false;
            String hasil;
            HttpURLConnection c = null;
            try {
                JSONArray pesan = susunPesan(ctx, pertanyaan);
                JSONObject badan = new JSONObject()
                        .put("model", model)
                        .put("messages", pesan)
                        .put("stream", true);

                c = (HttpURLConnection) new URL(dasarUrl(ctx) + "chat/completions").openConnection();
                c.setRequestMethod("POST");
                c.setConnectTimeout(TIMEOUT_HUBUNG);
                c.setReadTimeout(TIMEOUT_BACA);
                c.setRequestProperty("Authorization", "Bearer " + key);
                c.setRequestProperty("Content-Type", "application/json");
                c.setRequestProperty("Accept", "text/event-stream");
                c.setDoOutput(true);
                OutputStream os = c.getOutputStream();
                os.write(badan.toString().getBytes(StandardCharsets.UTF_8));
                os.flush();
                os.close();

                int kode = c.getResponseCode();
                if (kode != 200) {
                    hasil = pesanRamah(kode, bacaStream(c.getErrorStream()));
                } else {
                    BufferedReader r = new BufferedReader(
                            new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
                    String baris;
                    String terakhirTerkirim = null;
                    while ((baris = r.readLine()) != null) {
                        baris = baris.trim();
                        if (baris.isEmpty() || !baris.startsWith("data:")) continue;
                        String muatan = baris.substring(5).trim();
                        if (muatan.equals("[DONE]")) break;
                        try {
                            JSONObject obj = new JSONObject(muatan);
                            JSONArray chs = obj.optJSONArray("choices");
                            if (chs == null || chs.length() == 0) continue;
                            JSONObject pilihan = chs.getJSONObject(0);
                            JSONObject delta = pilihan.optJSONObject("delta");
                            String potongan = delta == null
                                    ? "" : delta.optString("content", "");
                            if (potongan.isEmpty()) continue;
                            mentah.append(potongan);
                            final String tampil = buangTagAksi(mentah.toString());
                            if (!tampil.equals(terakhirTerkirim)) {
                                terakhirTerkirim = tampil;
                                UTAMA.post(() -> balas.token(tampil));
                            }
                        } catch (Exception ignored) {}
                    }
                    r.close();

                    String teks = bersihkanDanJalankanAksi(ctx, mentah.toString());
                    if (teks.trim().isEmpty()) {
                        teks = "(AVI menerima balasan kosong dari model — silakan coba ulangi, "
                                + namaPemilik(ctx) + ". Kalau berulang, coba ganti model.)";
                    }
                    hasil = teks;
                    sukses = true;
                }
            } catch (Exception e) {
                if (mentah.length() > 0) {
                    // stream terpotong di tengah jalan — pakai apa yang sudah didapat
                    hasil = bersihkanDanJalankanAksi(ctx, mentah.toString());
                    sukses = true;
                } else {
                    hasil = "Gagal menghubungi " + namaPenyedia(ctx) + ": " + ramahkanException(e);
                }
            } finally {
                if (c != null) c.disconnect();
            }

            final String akhir = hasil;
            final boolean ok = sukses;
            if (ok) {
                simpanRiwayat(ctx, new Msg(pertanyaan, true));
                simpanRiwayat(ctx, new Msg(akhir, false));
            }
            UTAMA.post(() -> balas.selesai(akhir));
        }).start();
    }

    /** Susun array pesan: persona + riwayat terakhir + pertanyaan baru. */
    private static JSONArray susunPesan(Context ctx, String pertanyaan) throws Exception {
        JSONArray pesan = new JSONArray();
        pesan.put(new JSONObject().put("role", "system").put("content", persona(ctx)));
        List<Msg> riwayat = muatRiwayat(ctx);
        // daya ingat dipilih pemilik (Pengaturan → Daya ingat AI), bawaan 20
        int maksIngat = pref(ctx).getInt("daya_ingat", 20);
        int awal = Math.max(0, riwayat.size() - maksIngat);
        for (int i = awal; i < riwayat.size(); i++) {
            Msg m = riwayat.get(i);
            pesan.put(new JSONObject()
                    .put("role", m.dariUser ? "user" : "assistant")
                    .put("content", m.teks));
        }
        pesan.put(new JSONObject().put("role", "user").put("content", pertanyaan));
        return pesan;
    }

    private static String bacaStream(InputStream is) throws Exception {
        if (is == null) return "";
        BufferedReader r = new BufferedReader(
                new InputStreamReader(is, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String b;
        while ((b = r.readLine()) != null) sb.append(b).append('\n');
        r.close();
        return sb.toString();
    }

    /** Buang tag aksi dari teks TANPA mengeksekusinya (untuk tampilan streaming). */
    static String buangTagAksi(String teks) {
        if (teks == null) return "";
        Matcher m = POLA_AKSI.matcher(teks);
        return m.replaceAll("").replaceAll("\n{3,}", "\n\n").trim();
    }

    // ============================ warna aksen ============================

    /**
 * Aksen mengikuti tema: cerah → biru pekat (kontras di atas putih),
     * gelap → sian elektrik (identitas Arc). Dipakai orb & teks aksi.
     */
    public static int warnaAksen(Context c) {
        return temaGelap(c) ? 0xFF38BDF8 : 0xFF2563EB;
    }

    public static int campurWarna(int a, int b, float t) {
        int r = Math.round(android.graphics.Color.red(a)
                + (android.graphics.Color.red(b) - android.graphics.Color.red(a)) * t);
        int g = Math.round(android.graphics.Color.green(a)
                + (android.graphics.Color.green(b) - android.graphics.Color.green(a)) * t);
        int bl = Math.round(android.graphics.Color.blue(a)
                + (android.graphics.Color.blue(b) - android.graphics.Color.blue(a)) * t);
        return android.graphics.Color.rgb(r, g, bl);
    }

    // ============================ eksekusi aksi ============================

    static String ambilIsiBalasan(JSONObject jawaban) {
        try {
            JSONArray chs = jawaban.optJSONArray("choices");
            if (chs != null && chs.length() > 0) {
                JSONObject pilihan = chs.getJSONObject(0);
                JSONObject pesan = pilihan.optJSONObject("message");
                if (pesan != null) return pesan.optString("content", "").trim();
            }
        } catch (Exception ignored) {}
        return "";
    }

    /** Cari tag [AKSI:...] di teks model, jalankan, buang tag, sisipkan hasilnya. */
    static String bersihkanDanJalankanAksi(Context ctx, String teks) {
        if (teks == null) return "";
        Matcher m = POLA_AKSI.matcher(teks);
        StringBuilder hasilAksi = new StringBuilder();
        while (m.find()) {
            String isi = m.group(1).trim();
            String[] bag = isi.split("\\|");
            String nama = bag.length > 0 ? bag[0].trim() : "";
            String[] param = new String[Math.max(0, bag.length - 1)];
            for (int i = 1; i < bag.length; i++) param[i - 1] = bag[i].trim();
            if (hasilAksi.length() > 0) hasilAksi.append('\n');
            hasilAksi.append("→ ").append(DeviceActions.jalankan(ctx, nama, param));
        }
        String bersih = buangTagAksi(teks);
        if (hasilAksi.length() > 0) {
            bersih = (bersih + "\n\n" + hasilAksi.toString().trim()).trim();
        }
        return bersih;
    }

    // ============================ daftar model ============================

    public static void daftarModel(final Context ctx, final DaftarModel d) {
        if (!apiKeyAktif(ctx)) {
            UTAMA.post(() -> d.selesai(null, "API key belum diisi — tempel dulu di atas."));
            return;
        }
        final String prov = penyedia(ctx);
        final String key = pref(ctx).getString("apikey." + prov, "").trim();
        new Thread(() -> {
            List<String> hasil = new ArrayList<>();
            String galat = null;
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(dasarUrl(ctx) + "models").openConnection();
                c.setConnectTimeout(TIMEOUT_HUBUNG);
                c.setReadTimeout(30000);
                c.setRequestProperty("Authorization", "Bearer " + key);
                int kode = c.getResponseCode();
                if (kode != 200) {
                    galat = pesanRamah(kode, bacaStream(c.getErrorStream()));
                } else {
                    JSONObject obj = new JSONObject(bacaStream(c.getInputStream()));
                    JSONArray data = obj.optJSONArray("data");
                    if (data != null) {
                        for (int i = 0; i < data.length(); i++) {
                            String id = data.getJSONObject(i).optString("id", "");
                            if (!id.isEmpty() && cocokChat(id)) hasil.add(id);
                        }
                    }
                    java.util.Collections.sort(hasil);
                }
            } catch (Exception e) {
                galat = "Gagal mengambil model: " + ramahkanException(e);
            } finally {
                if (c != null) c.disconnect();
            }
            if (galat == null && hasil.isEmpty()) {
                galat = "Tidak ada model chat yang tersedia untuk key ini.";
            }
            if (galat == null && "gemini".equals(prov)) {
                hasil.remove(MODEL_REKOMENDASI_GEMINI);
                hasil.add(0, MODEL_REKOMENDASI_GEMINI);
            }
            final List<String> fHasil = hasil;
            final String fGalat = galat;
            UTAMA.post(() -> d.selesai(fGalat == null ? fHasil : null, fGalat));
        }).start();
    }

    private static boolean cocokChat(String id) {
        String s = id.toLowerCase();
        return !(s.contains("embed") || s.contains("aqa") || s.contains("tts")
                || s.contains("whisper") || s.contains("rerank") || s.contains("moderation")
                || s.contains("guard") || s.contains("imagen") || s.contains("veo")
                || s.contains("live") || s.contains("audio") || s.contains("vision-ocr"));
    }

    // ============================ tes koneksi ============================

    public static void ujiKoneksi(final Context ctx, final Balas balas) {
        if (!apiKeyAktif(ctx)) {
            UTAMA.post(() -> balas.selesai("API key belum diisi."));
            return;
        }
        final String model = modelAktif(ctx);
        if (model.isEmpty()) {
            UTAMA.post(() -> balas.selesai("Model belum dipilih — ambil & pilih dulu."));
            return;
        }
        tanya(ctx, "Balas dengan satu kata saja: siap.", teks -> {
            String t = teks == null ? "" : teks.toLowerCase();
            boolean baik = t.contains("siap") && !t.contains("gagal") && !t.contains("belum");
            if (baik) {
                balas.selesai("Tersambung ke " + namaPenyedia(ctx)
                        + " — " + model + " merespons dengan baik.");
            } else {
                balas.selesai("Respons tidak seperti dugaan: " + teks);
            }
        });
    }

    // ============================ riwayat ============================

    public static List<Msg> muatRiwayat(Context c) {
        List<Msg> daftar = new ArrayList<>();
        try {
            String json = pref(c).getString("riwayat", "[]");
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                daftar.add(new Msg(o.optString("teks", ""), o.optBoolean("user", false)));
            }
        } catch (Exception ignored) {}
        return daftar;
    }

    public static void simpanRiwayat(Context c, Msg m) {
        try {
            List<Msg> daftar = muatRiwayat(c);
            daftar.add(m);
            while (daftar.size() > MAKS_RIWAYAT_TERSIMPAN) daftar.remove(0);
            JSONArray arr = new JSONArray();
            for (Msg x : daftar) {
                arr.put(new JSONObject()
                        .put("teks", x.teks)
                        .put("user", x.dariUser));
            }
            pref(c).edit().putString("riwayat", arr.toString()).apply();
        } catch (Exception ignored) {}
    }

    public static void kosongkanRiwayat(Context c) {
        pref(c).edit().remove("riwayat").apply();
    }

    // ============================ tema ============================

    /**
     * CERAH = wajah utama AVI (pilihan pemilik: putih modern, gelap
     * hanya selingan). Pref "tema": kosong/cerah → terang, "gelap" → malam.
     */
    public static boolean temaGelap(Context c) {
        return "gelap".equals(pref(c).getString("tema", "cerah"));
    }

    /**
     * Paksa context memakai mode ui sesuai tema terpilih, lalu catat tema
     * yang terpasang — dipakai temaBerganti() agar layar terbuka ikut
     * berganti saat pemilik mengubah tema di Pengaturan.
     */
    public static Context terapkanTema(Context dasar) {
        boolean gelap = temaGelap(dasar);
        try {
            pref(dasar).edit().putInt("ui_terpakai", gelap ? 1 : 0).apply();
        } catch (Exception ignored) {}
        Configuration cfg = new Configuration(dasar.getResources().getConfiguration());
        int sekarang = cfg.uiMode & Configuration.UI_MODE_NIGHT_MASK;
        int diinginkan = gelap ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO;
        if (sekarang != diinginkan) {
            cfg.uiMode = (cfg.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | diinginkan;
            return dasar.createConfigurationContext(cfg);
        }
        return dasar;
    }

    /**
     * True bila tema yang terpasang pada layar ini sudah tidak sama dengan
     * tema terpilih (pemilik baru saja mengganti tema di Pengaturan).
     */
    public static boolean temaBerganti(Context c) {
        int terpasang = pref(c).getInt("ui_terpakai", -1);
        return terpasang != -1 && terpasang != (temaGelap(c) ? 1 : 0);
    }

    // ============================ pesan ramah & HTTP ============================

    static String pesanRamah(int kode, String isi) {
        String dasar;
        switch (kode) {
            case 401:
            case 403:
                dasar = "API key tidak diterima — periksa lagi isinya di Pengaturan.";
                break;
            case 404:
                dasar = "Model atau alamat tidak ditemukan — coba pilih model lain.";
                break;
            case 429:
                dasar = "Kuota/key sedang jebol permintaan — tunggu sebentar lalu coba lagi.";
                break;
            default:
                if (kode >= 500) dasar = "Server " + kode + " sedang bermasalah — coba lagi nanti.";
                else dasar = "Kesalahan HTTP " + kode + ".";
        }
        String potongan = isi == null ? "" : isi.trim();
        if (potongan.length() > 300) potongan = potongan.substring(0, 300) + "…";
        return dasar + (potongan.isEmpty() ? "" : "\n(" + potongan + ")");
    }

    static String ramahkanException(Exception e) {
        if (e instanceof UnknownHostException) {
            return "tidak ada koneksi internet.";
        }
        if (e instanceof SocketTimeoutException) {
            return "koneksi lambat / waktu tunggu habis — coba lagi.";
        }
        String pesan = e.getMessage();
        return e.getClass().getSimpleName() + (pesan == null ? "" : ": " + pesan);
    }
}
