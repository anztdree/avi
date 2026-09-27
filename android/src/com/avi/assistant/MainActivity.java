package com.avi.assistant;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Layar utama AVI v1.2: riwayat chat bergaya gelembung + input pill
 * kirim⇄mic. Hero diringkas (b19): logo 4 titik statis + salam —
 * tanpa orb bernapas, tanpa kartu saran (kalibrasi web Task 11).
 * Mode Live (mic) membuka LiveActivity; hasilnya ikut masuk riwayat ini.
 */
public class MainActivity extends Activity {

    private ListView daftar;
    private LinearLayout heroBox;
    private TextView tvSalam, tvSub;
    private EditText etInput;
    private ImageView bAksi;
    private AdptrGelembung adptr;
    private final List<Msg> isi = new ArrayList<>();
    private boolean sedangStream = false;
    private int posisiAnimasi = Integer.MAX_VALUE;   // baris >= ini dianimasikan

    // TTS mode chat: baca balasan AVI bila switch “Bacakan balasan” menyala
    private TextToSpeech tts;
    private boolean ttsSiap = false;

    @Override
    protected void attachBaseContext(Context baru) {
        super.attachBaseContext(AviBrain.terapkanTema(baru));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // PINTU ASISTEN LAMA: setting asisten di ponsel mungkin masih
        // menunjuk activity ini (bekas build lama) sehingga sistem
        // meluncurkan MainActivity saat tombol home ditahan. Serahkan
        // lantai ke layar Orbit — jangan tampilkan layar chat.
        String aksi = getIntent() != null ? getIntent().getAction() : null;
        if (Intent.ACTION_ASSIST.equals(aksi)
                || Intent.ACTION_VOICE_COMMAND.equals(aksi)) {
            startActivity(new Intent(this, OrbitAssistActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            finish();
            return;
        }

        setContentView(R.layout.activity_main);

        daftar     = findViewById(R.id.daftarChat);
        heroBox    = findViewById(R.id.heroBox);
        tvSalam    = findViewById(R.id.tvSalam);
        tvSub      = findViewById(R.id.tvSub);
        etInput    = findViewById(R.id.etInput);
        bAksi      = findViewById(R.id.bAksi);

        tvSalam.setText(sapaan());
        tvSub.setText("Ada yang bisa AVI bantu, " + AviBrain.namaPemilik(this) + "?");

        // ATURAN PEMILIK: TIDAK ADA tombol "Percakapan baru" — tahan home,
        // Mode Live, dan chat di aplikasi ini adalah SATU papan pesan yang
        // sama yang mengalir terus (AviBrain.riwayat).
        findViewById(R.id.btnPengaturan).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));

        bAksi.setOnClickListener(v -> {
            String t = etInput.getText().toString().trim();
            if (!t.isEmpty()) kirim(t);
            else startActivity(new Intent(this, LiveActivity.class));
        });

        etInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                pasangIkonAksi();
            }
        });

        adptr = new AdptrGelembung();
        daftar.setDivider(null);
        daftar.setStackFromBottom(true);
        daftar.setTranscriptMode(ListView.TRANSCRIPT_MODE_NORMAL);
        daftar.setAdapter(adptr);

        muatIsi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // pemilik baru saja mengganti tema di Pengaturan → layar ini ikut
        if (AviBrain.temaBerganti(this)) { recreate(); return; }
        if (!AviBrain.pref(this).getBoolean("onboarding_done", false)
                && !AviBrain.apiKeyAktif(this)) {
            startActivity(new Intent(this, OnboardingActivity.class));
        }
        // b16: suara DEFAULT NYALA — AVI asisten SUARA; dulu bawaan mati
        // sehingga di aplikasi AVI tidak pernah bersuara (keluhan pemilik
        // "suara avi tidak keluar"). Pilihan mematikan tetap dihormati.
        if (AviBrain.pref(this).getBoolean("tts_on", true) && tts == null) {
            siapkanTts();
        }
        muatIsi();   // sesi Mode Live menulis ke riwayat — segarkan
    }

    @Override
    protected void onDestroy() {
        if (tts != null) {
            try { tts.stop(); tts.shutdown(); } catch (Exception ignored) {}
            tts = null;
            ttsSiap = false;
        }
        super.onDestroy();
    }

    private void siapkanTts() {
        tts = new TextToSpeech(this, ok -> {
            ttsSiap = ok == TextToSpeech.SUCCESS;
            if (!ttsSiap || tts == null) return;
            // b16: fallback bahasa — tanpa data suara Indonesia, coba pakai
            // suara bawaan ponsel daripada gagal senyap tanpa suara.
            boolean oke = false;
            try { oke = tts.setLanguage(new Locale("id", "ID"))
                    >= TextToSpeech.LANG_AVAILABLE; } catch (Exception ignored) {}
            if (!oke) {
                try { tts.setLanguage(new Locale("id")); } catch (Exception ignored) {}
            }
            try {
                String namaSuara = AviBrain.pref(this).getString("tts_suara", "");
                if (!namaSuara.isEmpty() && tts.getVoices() != null) {
                    for (android.speech.tts.Voice v : tts.getVoices()) {
                        if (namaSuara.equals(v.getName())) { tts.setVoice(v); break; }
                    }
                }
            } catch (Exception ignored) {}
            try {
                tts.setSpeechRate(AviBrain.pref(this).getInt("tts_rate", 100) / 100f);
            } catch (Exception ignored) {}
        });
    }

    /** Bersihkan teks sebelum dibacakan: blok kode dilewati, tandaMarkdown dibuang. */
    private String teksSuara(String s) {
        return s.replaceAll("(?s)```.*?```", " (blok kode dilewati) ")
                .replaceAll("[#*_`>]", " ")
                .replaceAll("\\s+", " ").trim();
    }

    // ============================ tampilan ============================

    private String sapaan() {
        int h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        String ket = (h >= 4 && h < 11) ? "Selamat pagi"
                : (h >= 11 && h < 15) ? "Selamat siang"
                : (h >= 15 && h < 19) ? "Selamat sore" : "Selamat malam";
        return ket + ", " + AviBrain.namaPemilik(this) + "!";
    }

    private void muatIsi() {
        if (sedangStream) return;   // jangan ganggu gelembung yang sedang mengalir
        isi.clear();
        isi.addAll(AviBrain.muatRiwayat(this));
        adptr.notifyDataSetChanged();
        aturHero();
    }

    private void aturHero() {
        heroBox.setVisibility(isi.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void pasangIkonAksi() {
        boolean adaTeks = !etInput.getText().toString().trim().isEmpty();
        bAksi.setImageResource(adaTeks ? R.drawable.ic_send : R.drawable.ic_mic);
        bAksi.setContentDescription(adaTeks ? "Kirim" : "Mode Live — bicara langsung");
    }

    // ============================ kirim ============================

    private void kirim(String teks) {
        String t = teks.trim();
        if (t.isEmpty() || sedangStream) return;
        etInput.setText("");

        posisiAnimasi = isi.size();
        isi.add(new Msg(t, true));
        isi.add(new Msg(getString(R.string.sedang_berpikir), false));
        adptr.notifyDataSetChanged();
        aturHero();
        daftar.post(() -> daftar.setSelection(isi.size() - 1));
        sedangStream = true;
        pasangIkonAksi();

        final int idxAvi = isi.size() - 1;
        AviBrain.tanyaStream(this, t, new AviBrain.StreamBalas() {
            @Override public void token(String teksSejauhIni) {
                if (isFinishing() || isDestroyed() || idxAvi >= isi.size()) return;
                isi.set(idxAvi, new Msg(teksSejauhIni.isEmpty()
                        ? getString(R.string.sedang_berpikir) : teksSejauhIni, false));
                adptr.notifyDataSetChanged();
            }
            @Override public void selesai(String teksAkhir) {
                if (isFinishing() || isDestroyed() || idxAvi >= isi.size()) return;
                isi.set(idxAvi, new Msg(teksAkhir, false));   // tampilkanFinal: anti dobel
                adptr.notifyDataSetChanged();
                sedangStream = false;
                pasangIkonAksi();
                // bacakan bila pemilik mengaktifkannya (Pengaturan → Suara)
                if (ttsSiap && tts != null && !teksAkhir.trim().isEmpty()) {
                    String ucap = teksSuara(teksAkhir);
                    if (!ucap.isEmpty()) {
                        try { tts.speak(ucap, TextToSpeech.QUEUE_ADD, null,
                                "chat" + idxAvi); } catch (Exception ignored) {}
                    }
                }
            }
        });
    }

    // ============================ adapter gelembung ============================

    private class AdptrGelembung extends BaseAdapter {
        @Override public int getCount() { return isi.size(); }
        @Override public Object getItem(int pos) { return isi.get(pos); }
        @Override public long getItemId(int pos) { return pos; }

        @Override
        public View getView(int pos, View ubah, ViewGroup induk) {
            boolean animasi = pos >= posisiAnimasi;
            posisiAnimasi = Math.max(posisiAnimasi, pos + 1);

            Msg m = isi.get(pos);
            LinearLayout baris = new LinearLayout(MainActivity.this);
            baris.setOrientation(LinearLayout.HORIZONTAL);
            baris.setGravity(m.dariUser ? Gravity.END : Gravity.START);
            baris.setPadding(px(4), px(5), px(4), px(5));

            int maksLebar = (int) (getResources().getDisplayMetrics().widthPixels * 0.78f);

            // avatar orb mini di kiri untuk setiap pesan AVI (identitas Arc)
            if (!m.dariUser) {
                ImageView avatar = new ImageView(MainActivity.this);
                avatar.setImageResource(R.drawable.orb_mini);
                avatar.setContentDescription("AVI");
                LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(px(30), px(30));
                alp.topMargin = px(5);
                avatar.setLayoutParams(alp);
                baris.addView(avatar);
            }

            LinearLayout gelembung = new LinearLayout(MainActivity.this);
            gelembung.setOrientation(LinearLayout.VERTICAL);
            gelembung.setPadding(px(17), px(13), px(17), px(13));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (m.dariUser) {
                lp.leftMargin = px(40);
            } else {
                lp.leftMargin = px(11);
                lp.rightMargin = px(40);
            }
            gelembung.setLayoutParams(lp);
            gelembung.setBackgroundResource(m.dariUser
                    ? R.drawable.bubble_user : R.drawable.bubble_avi);
            baris.addView(gelembung);

            // pisahkan blok kode (``` ... ```) dari teks biasa
            String[] bagian = m.teks.split("```");
            for (int i = 0; i < bagian.length; i++) {
                if (bagian[i].isEmpty()) continue;
                if (i % 2 == 0) {                       // teks biasa
                    TextView tv = new TextView(MainActivity.this);
                    tv.setText(bagian[i].trim());
                    tv.setTextSize(15f);
                    tv.setMaxWidth(maksLebar);
                    tv.setLineSpacing(0, 1.2f);
                    // teks di gelembung gradasi (user) harus putih
                    tv.setTextColor(m.dariUser ? 0xFFFFFFFF
                            : getResources().getColor(R.color.avi_teks, getTheme()));
                    gelembung.addView(tv);
                } else {                                // blok kode
                    String kode = bagian[i].replaceFirst("^[a-zA-Z0-9_+-]*\\n", "").trim();
                    LinearLayout kartu = new LinearLayout(MainActivity.this);
                    kartu.setOrientation(LinearLayout.VERTICAL);
                    kartu.setBackgroundResource(R.drawable.card_kode);
                    kartu.setPadding(px(10), px(8), px(10), px(8));
                    LinearLayout.LayoutParams klp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
                    klp.topMargin = px(6);
                    kartu.setLayoutParams(klp);

                    TextView tvKode = new TextView(MainActivity.this);
                    tvKode.setText(kode);
                    tvKode.setTextSize(13f);
                    tvKode.setTypeface(android.graphics.Typeface.MONOSPACE);
                    tvKode.setMaxWidth(maksLebar - px(34));
                    tvKode.setTextColor(getResources().getColor(R.color.teks_kode, getTheme()));
                    kartu.addView(tvKode);

                    TextView bSalin = new TextView(MainActivity.this);
                    bSalin.setText("Salin");
                    bSalin.setTextSize(12f);
                    bSalin.setTextColor(getResources().getColor(R.color.teks_kode, getTheme()));
                    bSalin.setPadding(0, px(6), 0, 0);
                    bSalin.setOnClickListener(v -> {
                        ClipboardManager cm = (ClipboardManager)
                                getSystemService(Context.CLIPBOARD_SERVICE);
                        cm.setPrimaryClip(ClipData.newPlainText("kode", kode));
                        Toast.makeText(MainActivity.this, "Kode disalin.",
                                Toast.LENGTH_SHORT).show();
                    });
                    kartu.addView(bSalin);
                    gelembung.addView(kartu);
                }
            }

            if (animasi) {                              // animasi masuk gelembung
                baris.setAlpha(0f);
                baris.setTranslationY(px(24));
                baris.animate().alpha(1f).translationY(0f).setDuration(220).start();
            }
            return baris;
        }
    }

    private int px(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }
}
