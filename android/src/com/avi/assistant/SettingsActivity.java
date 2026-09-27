package com.avi.assistant;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Pengaturan AVI: penyedia AI + API key, pilih model (fetch dari penyedia),
 * nama pemilik (teks bebas), tema (cerah utama / gelap selingan — pilihan
 * pemilik), TTS (suara + kecepatan), durasi hening Mode Live, daya ingat AI,
 * asisten perangkat, bersihkan riwayat.
 * Semua perubahan tersimpan seketika; tombol Simpan memberi konfirmasi
 * eksplisit (permintaan pemilik).
 */
public class SettingsActivity extends Activity {

    private RadioGroup rgPenyedia, rgTema, rgHening, rgIngat;
    private RadioButton rbGemini, rbNvidia, rbOpenrouter, rbCerah, rbGelap;
    private RadioButton rbH5, rbH8, rbH12, rbH15;
    private RadioButton rbIngat10, rbIngat20, rbIngat50;
    private EditText etKey, etNamaPemilik;
    private TextView tvModel, tvPetunjukKey, tvRate, tvAsistenStatus, tvSuara, tvKalibrasi;
    private Switch swTts;
    private SeekBar sbRate;
    private Button bModel, bTes, bBersihkan, bAsisten, bSuara, bSimpan, bKalibrasi;
    private RadioGroup rgGerbang;
    private RadioButton rbGMati, rbGLembut, rbGKetat;
    private TextToSpeech ttsProbe;   // hanya untuk menampilkan daftar suara

    private boolean sedangMengisi = false;   // cegah TextWatcher menimpa nilai
    private boolean sedangUji = false;

    @Override
    protected void attachBaseContext(Context baru) {
        super.attachBaseContext(AviBrain.terapkanTema(baru));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        rgPenyedia   = findViewById(R.id.rgPenyedia);
        rgTema       = findViewById(R.id.rgTema);
        rbCerah      = findViewById(R.id.rbCerah);
        rbGelap      = findViewById(R.id.rbGelap);
        rbGemini     = findViewById(R.id.rbGemini);
        rbNvidia     = findViewById(R.id.rbNvidia);
        rbOpenrouter = findViewById(R.id.rbOpenrouter);
        etKey        = findViewById(R.id.etKey);
        etNamaPemilik= findViewById(R.id.etNamaPemilik);
        tvModel      = findViewById(R.id.tvModel);
        tvPetunjukKey= findViewById(R.id.tvPetunjukKey);
        tvRate       = findViewById(R.id.tvRate);
        swTts        = findViewById(R.id.swTts);
        sbRate       = findViewById(R.id.sbRate);
        bModel       = findViewById(R.id.bModel);
        bTes         = findViewById(R.id.bTes);
        bBersihkan   = findViewById(R.id.bBersihkan);
        tvAsistenStatus = findViewById(R.id.tvAsistenStatus);
        bAsisten     = findViewById(R.id.bAsisten);
        rgHening     = findViewById(R.id.rgHening);
        rbH5         = findViewById(R.id.rbH5);
        rbH8         = findViewById(R.id.rbH8);
        rbH12        = findViewById(R.id.rbH12);
        rbH15        = findViewById(R.id.rbH15);
        rgIngat      = findViewById(R.id.rgIngat);
        rbIngat10    = findViewById(R.id.rbIngat10);
        rbIngat20    = findViewById(R.id.rbIngat20);
        rbIngat50    = findViewById(R.id.rbIngat50);
        bSuara       = findViewById(R.id.bSuara);
        tvSuara      = findViewById(R.id.tvSuara);
        bSimpan      = findViewById(R.id.bSimpan);
        tvKalibrasi  = findViewById(R.id.tvKalibrasi);
        bKalibrasi   = findViewById(R.id.bKalibrasi);
        rgGerbang    = findViewById(R.id.rgGerbang);
        rbGMati      = findViewById(R.id.rbGMati);
        rbGLembut    = findViewById(R.id.rbGLembut);
        rbGKetat     = findViewById(R.id.rbGKetat);

        sbRate.setMax(100);                    // 50% .. 150% dipetakan dari 0..100
        muatNilai();
        pasangAksi();
    }

    // ================= muat =================

    private void muatNilai() {
        sedangMengisi = true;

        String prov = AviBrain.penyedia(this);
        if (prov.equals("nvidia")) rbNvidia.setChecked(true);
        else if (prov.equals("openrouter")) rbOpenrouter.setChecked(true);
        else rbGemini.setChecked(true);

        etKey.setText(AviBrain.pref(this).getString("apikey." + prov, ""));
        petunjukKey(prov);

        String model = AviBrain.modelAktif(this);
        tvModel.setText(model.isEmpty() ? "Belum dipilih" : "Aktif: " + model);

        String nama = AviBrain.pref(this).getString("nama_pemilik", "");
        etNamaPemilik.setText(nama);

        if (AviBrain.temaGelap(this)) rbGelap.setChecked(true);
        else rbCerah.setChecked(true);

        swTts.setChecked(AviBrain.pref(this).getBoolean("tts_on", false));
        int laju = AviBrain.pref(this).getInt("tts_rate", 100);
        sbRate.setProgress(Math.max(0, Math.min(100, laju - 50)));
        tvRate.setText("Kecepatan suara: " + laju + "%");

        int hening = AviBrain.pref(this).getInt("live_hening", 8);
        if (hening <= 5) rbH5.setChecked(true);
        else if (hening <= 8) rbH8.setChecked(true);
        else if (hening <= 12) rbH12.setChecked(true);
        else rbH15.setChecked(true);

        int ingat = AviBrain.pref(this).getInt("daya_ingat", 20);
        if (ingat <= 10) rbIngat10.setChecked(true);
        else if (ingat <= 20) rbIngat20.setChecked(true);
        else rbIngat50.setChecked(true);

        muatLabelSuara();
        muatStatusKalibrasi();

        sedangMengisi = false;
    }

    private void muatLabelSuara() {
        String suara = AviBrain.pref(this).getString("tts_suara", "");
        tvSuara.setText(suara.isEmpty()
                ? "Suara: bawaan mesin TTS"
                : "Suara aktif: " + suara);
    }

    @Override
    protected void onResume() {
        super.onResume();
        muatStatusAsisten();   // segarkan bila pemilik baru saja mengubah asisten
        muatStatusKalibrasi(); // segarkan bila pemilik baru saja selesai kalibrasi
    }

    /** Status kalibrasi suara pemilik (v2 — ProfilSuara terdaftar/terkunci). */
    private void muatStatusKalibrasi() {
        if (!ProfilSuara.ada(this)) {
            tvKalibrasi.setText("Belum terdaftar — AVI belum mengenali suara "
                    + "Anda. Daftarkan sekali agar AVI bangun pada sapaan "
                    + "Anda saja.");
            bKalibrasi.setText("Daftarkan suara saya");
            return;
        }
        long w = AviBrain.pref(this).getLong("kal_waktu", 0);
        String waktu = w > 0
                ? new SimpleDateFormat("d MMM yyyy HH.mm", Locale.getDefault())
                        .format(new Date(w)) : "—";
        float pitch = AviBrain.pref(this).getFloat("kal_pitch", 0f);
        String mode = GerbangSapa.mode(this);
        String label = "ketat".equals(mode) ? "Ketat"
                : "mati".equals(mode) ? "Nonaktif" : "Lembut";
        tvKalibrasi.setText("TERDAFTAR ✓ — " + waktu
                + (pitch > 0 ? " • nada dasar ±" + Math.round(pitch) + " Hz" : "")
                + " • gerbang: " + label + ".");
        bKalibrasi.setText("Buka layar kalibrasi (terkunci)");
    }

    private void petunjukKey(String prov) {
        switch (prov) {
            case "nvidia":
                tvPetunjukKey.setText("API key NVIDIA NIM (gratis) — buat di build.nvidia.com");
                break;
            case "openrouter":
                tvPetunjukKey.setText("API key OpenRouter — buat di openrouter.ai/keys");
                break;
            default:
                tvPetunjukKey.setText("API key Gemini (punya kuota gratis) — buat di aistudio.google.com/apikey");
        }
    }

    // ================= aksi =================

    private void pasangAksi() {
        rgPenyedia.setOnCheckedChangeListener((grup, id) -> {
            if (sedangMengisi) return;
            String prov = id == R.id.rbNvidia ? "nvidia"
                    : id == R.id.rbOpenrouter ? "openrouter" : "gemini";
            AviBrain.pref(this).edit().putString("penyedia", prov).apply();
            sedangMengisi = true;
            etKey.setText(AviBrain.pref(this).getString("apikey." + prov, ""));
            petunjukKey(prov);
            String model = AviBrain.modelAktif(this);
            tvModel.setText(model.isEmpty() ? "Belum dipilih" : "Aktif: " + model);
            sedangMengisi = false;
        });

        etKey.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                if (sedangMengisi) return;
                AviBrain.pref(SettingsActivity.this)
                        .edit()
                        .putString("apikey." + AviBrain.penyedia(SettingsActivity.this),
                                s.toString().trim())
                        .apply();
            }
        });

        etNamaPemilik.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                if (sedangMengisi) return;
                AviBrain.pref(SettingsActivity.this)
                        .edit()
                        .putString("nama_pemilik", s.toString().trim())
                        .apply();
            }
        });

        bModel.setOnClickListener(v -> dialogPilihModel());

        bTes.setOnClickListener(v -> {
            if (sedangUji) return;
            sedangUji = true;
            bTes.setText("Menguji…");
            AviBrain.ujiKoneksi(this, hasil -> {
                sedangUji = false;
                bTes.setText("Tes koneksi");
                Toast.makeText(this, hasil, Toast.LENGTH_LONG).show();
            });
        });

        // ===== tema: cerah utama, gelap selingan (pilihan pemilik) =====
        rgTema.setOnCheckedChangeListener((grup, id) -> {
            if (sedangMengisi) return;
            boolean gelap = id == R.id.rbGelap;
            AviBrain.pref(this).edit().putString("tema", gelap ? "gelap" : "cerah").apply();
            recreate();     // tema diterapkan lewat attachBaseContext
        });

        swTts.setOnCheckedChangeListener((tombol, nyala) -> {
            if (sedangMengisi) return;
            AviBrain.pref(this).edit().putBoolean("tts_on", nyala).apply();
        });

        sbRate.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int nilai, boolean dariUser) {
                int laju = 50 + nilai;
                tvRate.setText("Kecepatan suara: " + laju + "%");
                if (!sedangMengisi) {
                    AviBrain.pref(SettingsActivity.this).edit().putInt("tts_rate", laju).apply();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });

        bBersihkan.setOnClickListener(v -> {
            AviBrain.kosongkanRiwayat(this);
            Toast.makeText(this, "Riwayat chat dibersihkan.", Toast.LENGTH_SHORT).show();
        });


        // ===== Asisten perangkat (tahan tombol home ala Google Assistant) =====
        bAsisten.setOnClickListener(v -> bukaPengaturanAsisten());

        // ===== durasi hening Mode Live =====
        rgHening.setOnCheckedChangeListener((grup, id) -> {
            if (sedangMengisi) return;
            int dtk = id == R.id.rbH5 ? 5 : id == R.id.rbH12 ? 12
                    : id == R.id.rbH15 ? 15 : 8;
            AviBrain.pref(this).edit().putInt("live_hening", dtk).apply();
        });

        // ===== daya ingat AI =====
        rgIngat.setOnCheckedChangeListener((grup, id) -> {
            if (sedangMengisi) return;
            int n = id == R.id.rbIngat10 ? 10 : id == R.id.rbIngat50 ? 50 : 20;
            AviBrain.pref(this).edit().putInt("daya_ingat", n).apply();
        });

        // ===== pilih suara TTS =====
        bSuara.setOnClickListener(v -> dialogPilihSuara());

        // ===== kalibrasi suara pemilik =====
        bKalibrasi.setOnClickListener(v ->
                startActivity(new Intent(this, KalibrasiActivity.class)));

        // ===== mode gerbang sapaan (lembut bawaan) =====
        String kal = AviBrain.pref(this).getString("kal_mode", "lembut");
        if ("mati".equals(kal)) rbGMati.setChecked(true);
        else if ("ketat".equals(kal)) rbGKetat.setChecked(true);
        else rbGLembut.setChecked(true);
        rgGerbang.setOnCheckedChangeListener((grup, id) -> {
            if (sedangMengisi) return;
            String mode = id == R.id.rbGMati ? "mati"
                    : id == R.id.rbGKetat ? "ketat" : "lembut";
            AviBrain.pref(this).edit()
                    .putString("kal_mode", mode)
                    .putBoolean("kal_mode_dipilih", true)   // b15: pilihan manual
                    .apply();                                // tidak ditimpa wizard
            muatStatusKalibrasi();   // status ikut menampilkan gerbang
        });

        // ===== simpan (konfirmasi eksplisit, permintaan pemilik) =====
        bSimpan.setOnClickListener(v -> Toast.makeText(this,
                "Pengaturan tersimpan ✓", Toast.LENGTH_SHORT).show());
    }

    // ================= asisten perangkat =================

    private void muatStatusAsisten() {
        String aktif = Settings.Secure.getString(
                getContentResolver(), "voice_interaction_service");
        if (aktif != null && aktif.contains(getPackageName())) {
            tvAsistenStatus.setText("AKTIF — AVI adalah asisten perangkat ini. "
                    + "Tahan tombol home di layar mana pun (navigasi gestur: "
                    + "sapu dari sudut kiri/kanan bawah) untuk memanggil AVI.");
            bAsisten.setText("Buka pengaturan asisten perangkat");
        } else {
            tvAsistenStatus.setText("Belum aktif. Pilih AVI di halaman berikutnya "
                    + "agar bisa dipanggil lewat tahan tombol home — tanpa layanan "
                    + "latar, tanpa boros baterai.");
            bAsisten.setText("Jadikan AVI asisten perangkat");
        }
    }

    private void bukaPengaturanAsisten() {
        // Layar pemilih asisten berbeda-beda tiap pabrikan (stock, Samsung,
        // Xiaomi/MIUI-HyperOS, OPPO, vivo) — coba satu per satu sampai ada
        // yang terbuka, terakhir buka Pengaturan biasa.
        Intent[] kandidat = new Intent[]{
                new Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
                new Intent().setClassName("com.android.settings",
                        "com.android.settings.Settings$VoiceInputSettingsActivity"),
                new Intent().setClassName("com.android.settings",
                        "com.android.settings.voice.VoiceInputSettings"),
                new Intent("com.android.settings.VOICE_INPUT_SETTINGS"),
                new Intent("android.settings.ASSIST_SETTINGS"),
                new Intent(Settings.ACTION_SETTINGS)
        };
        for (Intent it : kandidat) {
            try {
                startActivity(it);
                return;
            } catch (Exception ignored) {}
        }
        Toast.makeText(this,
                "Layar asisten tidak ditemukan — cari \u201CAplikasi default\u201D "
                        + "atau \u201CDigital assistant\u201D di Pengaturan.",
                Toast.LENGTH_LONG).show();
    }

    // ================= dialog pilih suara TTS =================

    private void dialogPilihSuara() {
        if (ttsProbe != null) return;   // sedang menyiapkan
        final AlertDialog[] kotakTunggu = new AlertDialog[1];
        ttsProbe = new TextToSpeech(this, ok -> {
            java.util.Set<Voice> daftarSuara =
                    (ok == TextToSpeech.SUCCESS && ttsProbe != null)
                    ? ttsProbe.getVoices() : null;
            final List<Voice> suara = new ArrayList<>();
            if (daftarSuara != null) {
                // suara Indonesia dulu, lalu sisanya alfabetis
                List<Voice> id = new ArrayList<>(), lain = new ArrayList<>();
                for (Voice v : daftarSuara) {
                    (v.getLocale() != null
                            && v.getLocale().getLanguage().startsWith("id")
                            ? id : lain).add(v);
                }
                id.sort((a, b) -> a.getName().compareTo(b.getName()));
                lain.sort((a, b) -> a.getName().compareTo(b.getName()));
                suara.addAll(id);
                suara.addAll(lain);
            }
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) { matikanProbe(); return; }
                kotakTunggu[0].dismiss();
                tampilkanDaftarSuara(suara);
                matikanProbe();
            });
        });

        kotakTunggu[0] = new AlertDialog.Builder(this)
                .setTitle("Pilih suara TTS")
                .setMessage("Membaca suara yang tersedia di mesin TTS ponsel…")
                .setNegativeButton("Batal", (d, w) -> { matikanProbe(); })
                .create();
        kotakTunggu[0].show();
    }

    private void tampilkanDaftarSuara(final List<Voice> suara) {
        if (suara.isEmpty()) {
            Toast.makeText(this, "Mesin TTS ponsel ini tidak membuka daftar suara — "
                    + "AVI memakai suara bawaan.", Toast.LENGTH_LONG).show();
            return;
        }
        final List<String> label = new ArrayList<>();
        for (Voice v : suara) {
            label.add(v.getName() + "   (" + v.getLocale() + ")");
        }
        new AlertDialog.Builder(this)
                .setTitle("Pilih suara TTS")
                .setItems(label.toArray(new String[0]), (d, pos) -> {
                    Voice v = suara.get(pos);
                    AviBrain.pref(this).edit()
                            .putString("tts_suara", v.getName()).apply();
                    muatLabelSuara();
                    Toast.makeText(this, "Suara dipasang: " + v.getName(),
                            Toast.LENGTH_SHORT).show();
                })
                .setNeutralButton("Kembali ke suara bawaan", (d, w) -> {
                    AviBrain.pref(this).edit().remove("tts_suara").apply();
                    muatLabelSuara();
                })
                .setNegativeButton("Tutup", null)
                .show();
    }

    private void matikanProbe() {
        if (ttsProbe != null) {
            try { ttsProbe.shutdown(); } catch (Exception ignored) {}
            ttsProbe = null;
        }
    }

    @Override
    protected void onDestroy() {
        matikanProbe();
        super.onDestroy();
    }

    // ================= dialog pilih model =================

    private void dialogPilihModel() {
        final String prov = AviBrain.penyedia(this);

        LinearLayout kotak = new LinearLayout(this);
        kotak.setOrientation(LinearLayout.VERTICAL);
        int p = px(14);
        kotak.setPadding(p, p, p, 0);

        final EditText etCari = new EditText(this);
        etCari.setHint("Cari model / ketik manual");
        etCari.setTextSize(14f);
        kotak.addView(etCari);

        final TextView tvInfo = new TextView(this);
        tvInfo.setTextSize(12f);
        tvInfo.setTextColor(getResources().getColor(R.color.avi_teks_samping, getTheme()));
        tvInfo.setText("Memuat daftar model…");
        kotak.addView(tvInfo);

        final ListView daftar = new ListView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, px(340));
        lp.topMargin = px(8);
        daftar.setLayoutParams(lp);
        kotak.addView(daftar);

        final List<String> semua = new ArrayList<>();
        final List<String> tampil = new ArrayList<>();
        final ArrayAdapter<String> adptr = new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_1, tampil);
        daftar.setAdapter(adptr);

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Pilih model — " + AviBrain.namaPenyedia(this))
                .setView(bungkusScroll(kotak))
                .setNegativeButton("Tutup", null)
                .create();

        Runnable[] terapkanCarian = new Runnable[1];
        terapkanCarian[0] = () -> {
            String q = etCari.getText().toString().trim().toLowerCase();
            tampil.clear();
            for (String id : semua) {
                if (q.isEmpty() || id.toLowerCase().contains(q)) tampil.add(id);
            }
            adptr.notifyDataSetChanged();
        };

        etCari.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { terapkanCarian[0].run(); }
        });

        daftar.setOnItemClickListener((induk, v, posisi, id) -> {
            String pilih = tampil.get(posisi);
            AviBrain.pref(this).edit().putString("model." + prov, pilih).apply();
            tvModel.setText("Aktif: " + pilih);
            Toast.makeText(this, "Model dipilih: " + pilih, Toast.LENGTH_SHORT).show();
            dialog.dismiss();
        });

        dialog.show();

        AviBrain.daftarModel(this, (model, galat) -> {
            if (isFinishing()) return;
            if (galat != null) {
                tvInfo.setText(galat + " — tetap bisa mengetik nama model manual di atas, "
                        + "lalu sentuh \"Pakai sebagai model\".");
                tambahTombolManual(kotak, prov, etCari, dialog);
                return;
            }
            semua.clear();
            semua.addAll(model);
            if ("gemini".equals(prov)) {
                tvInfo.setText("Paling atas: " + AviBrain.MODEL_REKOMENDASI_GEMINI
                        + " (teruji responsnya cepat).");
            } else {
                tvInfo.setText("Sentuh nama model untuk memilihnya.");
            }
            terapkanCarian[0].run();
        });
    }

    private void tambahTombolManual(LinearLayout kotak, String prov,
                                    EditText etCari, AlertDialog dialog) {
        if (kotak.findViewWithTag("manual") != null) return;
        Button bManual = new Button(this);
        bManual.setTag("manual");
        bManual.setText("Pakai teks di atas sebagai model");
        bManual.setAllCaps(false);
        bManual.setOnClickListener(v -> {
            String nama = etCari.getText().toString().trim();
            if (nama.isEmpty()) {
                Toast.makeText(this, "Ketik dulu nama modelnya, Sir.", Toast.LENGTH_SHORT).show();
                return;
            }
            AviBrain.pref(this).edit().putString("model." + prov, nama).apply();
            tvModel.setText("Aktif: " + nama);
            Toast.makeText(this, "Model dipilih: " + nama, Toast.LENGTH_SHORT).show();
            dialog.dismiss();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = px(8);
        lp.bottomMargin = px(8);
        kotak.addView(bManual, lp);
    }

    private ScrollView bungkusScroll(LinearLayout isi) {
        ScrollView sc = new ScrollView(this);
        sc.addView(isi, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return sc;
    }

    private int px(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }
}
