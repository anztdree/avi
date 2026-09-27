# ATURAN PROYEK AVI — TERKUNCI

Aplikasi asisten AI pribadi Android native (Java, tanpa dependensi eksternal,
tanpa Gradle, package `com.avi.assistant`, minSdk 24).

1. **Nomor versi APK tidak berubah** kecuali pemilik yang memutuskan.
2. **Bug didiskusikan dulu sebelum koding** — perbaikan se-minimal mungkin.
3. **APK dikirim via tmpfiles.org**; APK + source di-backup permanen di folder
   `AVI/` — DILARANG menghapus. Recovery 2026-09-24: lingkungan pernah reset
   penuh, seluruh source & keystore dibangun ulang; sejak itu source juga
   di-commit ke git agar selamat dari reset.
4. **Tanpa AskUserQuestion** — diskusi lewat percakapan natural.
5. **AVI tidak multi-tasking** — DILARANG fitur multi-chat / banyak sesi
   obrolan paralel. Satu obrolan, tugas dikerjakan satu per satu sampai
   selesai (pola asisten pribadi seperti Siri/Google Assistant, bukan pola
   aplikasi chat). Keputusan pemilik, 2026-09-24.
