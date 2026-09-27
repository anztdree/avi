package com.avi.assistant;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ViewFlipper;

import java.util.ArrayList;
import java.util.List;

/**
 * Onboarding terpandu 3 langkah (ala asisten modern), bisa dilewati:
 * 1) pilih penyedia AI  2) tempel API key  3) ambil & pilih model.
 * Muncul sekali di awal bila konfigurasi masih kosong.
 */
public class OnboardingActivity extends Activity {

    private ViewFlipper flipper;
    private RadioGroup rgProv;
    private EditText etKey;
    private TextView tvPetunjuk, tvStatusModel;
    private ListView lvModel;
    private Button bLanjut;
    private String prov = "gemini";
    private int posisiPilih = -1;          // baris model terpilih (sorotan)
    private List<String> daftarTerakhir = new ArrayList<>();

    @Override
    protected void attachBaseContext(Context baru) {
        super.attachBaseContext(AviBrain.terapkanTema(baru));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_onboarding);

        flipper = findViewById(R.id.flipper);
        rgProv = findViewById(R.id.rgProv);
        etKey = findViewById(R.id.etKeyOnboard);
        tvPetunjuk = findViewById(R.id.tvPetunjukOnboard);
        tvStatusModel = findViewById(R.id.tvStatusModel);
        lvModel = findViewById(R.id.lvModel);
        bLanjut = findViewById(R.id.bLanjut);

        prov = AviBrain.penyedia(this);
        if ("nvidia".equals(prov)) rgProv.check(R.id.rbProvNvidia);
        else if ("openrouter".equals(prov)) rgProv.check(R.id.rbProvOpenrouter);
        else rgProv.check(R.id.rbProvGemini);
        etKey.setText(AviBrain.pref(this).getString("apikey." + prov, ""));

        rgProv.setOnCheckedChangeListener((g, id) -> {
            prov = id == R.id.rbProvNvidia ? "nvidia"
                    : id == R.id.rbProvOpenrouter ? "openrouter" : "gemini";
            AviBrain.pref(this).edit().putString("penyedia", prov).apply();
            etKey.setText(AviBrain.pref(this).getString("apikey." + prov, ""));
            petunjuk();
        });

        ((TextView) findViewById(R.id.tvSalamOnboard)).setText(
                "Halo, " + AviBrain.namaPemilik(this) + "!");

        findViewById(R.id.bLewati).setOnClickListener(v -> selesai());
        bLanjut.setOnClickListener(v -> langkahBerikut());
        findViewById(R.id.bAmbilModel).setOnClickListener(v -> ambilModel());

        petunjuk();
    }

    private void petunjuk() {
        switch (prov) {
            case "nvidia":
                tvPetunjuk.setText("API key NVIDIA NIM (gratis) — buat di build.nvidia.com");
                break;
            case "openrouter":
                tvPetunjuk.setText("API key OpenRouter — buat di openrouter.ai/keys");
                break;
            default:
                tvPetunjuk.setText("API key Gemini (punya kuota gratis) — buat di aistudio.google.com/apikey");
        }
    }

    private void langkahBerikut() {
        int anak = flipper.getDisplayedChild();
        if (anak == 0) {
            String key = etKey.getText().toString().trim();
            if (key.isEmpty()) {
                Toast.makeText(this, "Tempel dulu API key-nya, "
                        + AviBrain.namaPemilik(this) + ".", Toast.LENGTH_SHORT).show();
                return;
            }
            AviBrain.pref(this).edit().putString("apikey." + prov, key).apply();
            flipper.showNext();
            bLanjut.setText("Selesai");
            ambilModel();
        } else if (anak == 1) {
            // Selesai TIDAK BOLEH buntu: bila model belum dipilih, isi otomatis
            // dengan rekomendasi (Gemini) / hasil urutan pertama daftar.
            if (AviBrain.modelAktif(this).isEmpty()) {
                String otomatis = "";
                if ("gemini".equals(prov)) otomatis = AviBrain.MODEL_REKOMENDASI_GEMINI;
                else if (!daftarTerakhir.isEmpty()) otomatis = daftarTerakhir.get(0);
                if (otomatis.isEmpty()) {
                    Toast.makeText(this, "Daftar model belum terbaca — coba "
                            + "sentuh \"Ambil daftar model\" lagi, "
                            + AviBrain.namaPemilik(this) + ".", Toast.LENGTH_LONG).show();
                    return;
                }
                AviBrain.pref(this).edit().putString("model." + prov, otomatis).apply();
                Toast.makeText(this, "Model otomatis: " + otomatis,
                        Toast.LENGTH_SHORT).show();
            }
            selesai();
        }
    }

    private void ambilModel() {
        tvStatusModel.setText("Mengambil daftar model…");
        AviBrain.daftarModel(this, (daftar, galat) -> {
            if (isFinishing() || isDestroyed()) return;
            if (galat != null) {
                tvStatusModel.setText(galat + "\nTetap bisa menyelesaikan wizard — "
                        + "model bisa dipilih nanti di Pengaturan.");
                return;
            }
            daftarTerakhir = new ArrayList<>(daftar);
            String rekomendasi = "gemini".equals(AviBrain.penyedia(this))
                    ? AviBrain.MODEL_REKOMENDASI_GEMINI : daftar.get(0);
            tvStatusModel.setText("Sentuh model untuk langsung memakainya. "
                    + "Rekomendasi: " + rekomendasi);
            lvModel.setAdapter(new ArrayAdapter<String>(this,
                    android.R.layout.simple_list_item_1, daftarTerakhir) {
                @Override
                public View getView(int posisi, View ubah, ViewGroup induk) {
                    TextView tv = (TextView) ubah;
                    if (tv == null) tv = new TextView(OnboardingActivity.this);
                    boolean terpilih = posisi == posisiPilih;
                    tv.setText(daftarTerakhir.get(posisi) + (terpilih ? "   ✓ dipakai" : ""));
                    tv.setTextSize(14f);
                    tv.setTypeface(android.graphics.Typeface.MONOSPACE,
                            terpilih ? android.graphics.Typeface.BOLD
                                    : android.graphics.Typeface.NORMAL);
                    tv.setTextColor(getResources().getColor(terpilih
                            ? R.color.aksen : R.color.avi_teks, getTheme()));
                    tv.setBackgroundResource(terpilih
                            ? R.drawable.bg_row_model_pilih : R.drawable.bg_row_model);
                    int p = px(14);
                    tv.setPadding(p, p, p, p);
                    return tv;
                }
            });
            // BUGFIX: selama ini daftar tidak punya pendengar klik —
            // sentuhan tidak pernah tersimpan sehingga wizard tak bisa selesai.
            lvModel.setOnItemClickListener((induk, v, posisi, id) -> {
                posisiPilih = posisi;
                String pilih = daftarTerakhir.get(posisi);
                AviBrain.pref(OnboardingActivity.this)
                        .edit().putString("model." + prov, pilih).apply();
                ((ArrayAdapter) lvModel.getAdapter()).notifyDataSetChanged();
                Toast.makeText(this, "Model dipilih: " + pilih, Toast.LENGTH_SHORT).show();
                selesai();   // langsung selesai — wizard tak lagi buntu
            });
        });
    }

    private int px(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    private void selesai() {
        AviBrain.pref(this).edit().putBoolean("onboarding_done", true).apply();
        // b15: bila suara pemilik belum terdaftar, tawarkan WIZARD kalibrasi
        // sekarang (pola Google: Voice Match ditawarkan di setup awal).
        if (!ProfilSuara.ada(this)) {
            new AlertDialog.Builder(this)
                    .setTitle("Kunci AVI dengan suara Anda")
                    .setMessage("Tanpa kalibrasi, AVI tidak bisa membedakan "
                            + "Anda dari orang lain. Jalankan wizard pendaftaran "
                            + "suara sekarang? Frasanya pendek — kurang dari "
                            + "dua menit.")
                    .setPositiveButton("Ya, daftar sekarang", (d, w) -> {
                        startActivity(new Intent(this, KalibrasiActivity.class));
                        finish();
                    })
                    .setNegativeButton("Nanti", (d, w) -> pesanSiap())
                    .setCancelable(false)
                    .show();
            return;
        }
        pesanSiap();
    }

    private void pesanSiap() {
        Toast.makeText(this, "AVI siap, " + AviBrain.namaPemilik(this) + "!",
                Toast.LENGTH_SHORT).show();
        finish();
    }
}
