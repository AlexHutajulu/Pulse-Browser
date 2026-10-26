# Perbaikan Audit Keamanan — 2026-10-26

Dokumen ini menggantikan dokumen remediasi lama di `docs/audit-archive/`
(yang masih menyebut Fase 1 "IN PROGRESS"). Semua perubahan di bawah ini
ada di branch kerja lokal dan **belum di-push** sampai pemilik repo
menyetujuinya.

## Yang sudah diperbaiki

1. **Cleartext dimatikan secara default**
   - `app/src/main/res/xml/network_security_config.xml`:
     `base-config cleartextTrafficPermitted="false"`.
   - Situs HTTP yang tidak bisa di-upgrade ke HTTPS oleh HTTPS-Only
     sekarang diblokir, bukan dimuat lewat HTTP.

2. **Tidak ada lagi fallback plaintext**
   - `AppDatabase.kt`: kalau SQLCipher gagal, sesi berjalan di memori
     (`Room.inMemoryDatabaseBuilder`), tidak pernah menulis riwayat /
     bookmark / unduhan ke file Room plaintext di disk.
   - `EncryptedPreferencesManager.kt`: kalau Keystore gagal, preferensi
     hidup di memori (`InMemorySharedPreferences`) selama proses berjalan.
     File legacy tidak dimigrasi atau dihapus dalam kondisi ini, supaya
     data tidak hilang dan passphrase SQLCipher tidak tersimpan plaintext.

3. **Perekaman layar diblokir secara default**
   - `SettingsManager.kt`: `allowScreenRecording` default berubah dari
     `true` menjadi `false`, sehingga `MainActivity` memasang `FLAG_SECURE`
     secara default. Pengguna harus opt-in untuk mengizinkan perekaman.

4. **JS bridge diperketat**
   - `CustomWebView.kt`: unduhan blob lewat `AlexBrowserNative` sekarang
     menolak tipe/ekstensi executable (`.apk`, `.exe`, `.dll`, `.bat`,
     `.sh`, `.dex`, dan MIME yang setara). Unduhan normal yang dimulai
     pengguna lewat `AppDownloadManager` tidak terpengaruh.
   - Bypass proteksi (whitelist / AFP bypass) sekarang dicatat di log
     dengan host saja, bukan URL lengkap, supaya tidak diam-diam.

5. **User-Agent tidak lagi beku di Chrome 124**
   - UA desktop diturunkan dari UA WebView sistem
     (`desktopUserAgentFromDefault`), dengan segmen platform desktop
     `X11; Linux x86_64` supaya deteksi mode desktop tetap bekerja.
   - Impor ekstensi memakai `AlexBrowser/2.0`, bukan spoof Chrome 124.

6. **Pinning & integritas filter list — disiapkan, belum diaktifkan**
   - Baru: `CertificatePinning.kt` + wiring di `FilterListManager.kt`.
   - Konten filter list yang mengandung `<script`, `javascript:`, atau
     `data:text/html` ditolak dan tidak di-cache.
   - **Pin belum diisi dengan sengaja.** Pin yang diekstrak dari
     lingkungan sandbox / proxy akan salah di perangkat nyata karena
     proxy tersebut me-MITM TLS dengan CA-nya sendiri. Isi
     `FILTER_LIST_PINS` dari jaringan nyata sebelum rilis, dengan perintah
     yang tertulis di file tersebut, minimal dua pin per host (leaf +
     intermediate/backup).

7. **Kebersihan repo**
   - Dependensi Hilt yang mati dihapus dari `app/build.gradle.kts`
     (tidak ada `@HiltAndroidApp` / `@AndroidEntryPoint` di kode).
   - Dokumen remediasi lama dipindah ke `docs/audit-archive/` (bukan
     dihapus), dan gambar temp AI Studio di `.tempmediaStorage/` dihapus.
   - `FrameMetricsTelemetrySync.kt` diberi klarifikasi: file itu adalah
     cek anti-tamper lokal, bukan telemetri keluar, dengan daftar string
     yang sudah di-decode di komentarnya.

8. **Lisensi, CI, dan test**
   - `LICENSE` baru (proposal MIT — bisa diganti sebelum push).
   - `.github/workflows/android.yml`: CI dasar menjalankan
     `./gradlew testDebugUnitTest` di setiap push/PR ke main.
   - `SecurityFixesTest.kt`: test regresi untuk cleartext, default
     FLAG_SECURE, tidak ada fallback plaintext DB/preferensi, validasi
     konten filter list, status pinning, dan blokir executable via bridge.

## Yang masih perlu keputusan / kerja manual sebelum rilis publik

- Isi pin sertifikat di `CertificatePinning.kt` dari jaringan nyata.
- Tulis privacy policy dan ganti `applicationId` / namespace
  `com.example` kalau mau identitas Play Store yang bersih (ini sengaja
  tidak diubah di perbaikan ini karena mengubah applicationId berdampak
  ke update aplikasi yang sudah terinstal).
- Uji build release di beberapa perangkat / versi WebView, karena
  perbaikan ini baru diaudit secara statis dan lewat test unit baru.
- Pertimbangkan memecah `BrowserScreen.kt` (5.592 baris) di refactor
  terpisah — tidak dilakukan di sini supaya diff perbaikan keamanan
  tetap bisa direview.
