# Rencana Implementasi: Integrasi Ikon Alex Browser "Singularity"

Mengimplementasikan aset ikon SVG baru (`alex-browser-singularity-primary_0a73ebc8.svg`) yang telah disediakan pengguna ke dalam Alex Browser sebelum melanjutkan ke Tahap 4.

---

## 1. Konfigurasi Launcher Icon Android

### A. Adaptive Icon (`res/mipmap-anydpi-v26/`)
- **Latar Belakang (`res/drawable/ic_launcher_background.xml`)**:
  - Menggunakan warna solid obsidian gelap `#080D19` sesuai preferensi pengguna dan palet warna SVG asli.
- **Foreground Asset (`res/drawable/ic_launcher_foreground.xml`) & Drawable Raster**:
  - Merender ikon Singularity ke dalam `res/drawable/ic_launcher_foreground_asset.png` resolusi tinggi dengan safe zone 66dp di dalam kanvas 108dp (sesuai standar Material You Adaptive Icon).
  - Mengonfigurasi `ic_launcher_foreground.xml` menggunakan `<layer-list>` dengan ukuran `66dp` x `66dp` dan `gravity="center"`.
- **Adaptive Wrappers**:
  - Memperbarui `ic_launcher.xml` dan `ic_launcher_round.xml` di `res/mipmap-anydpi-v26/` merujuk ke `@drawable/ic_launcher_background` dan `@drawable/ic_launcher_foreground`.

### B. Legacy Non-Adaptive Mipmap Raster Icons
- Menghasilkan file PNG raster (`ic_launcher.png` dan `ic_launcher_round.png`) beresolusi presisi menggunakan ImageMagick/Resvg untuk seluruh varian kepadatan layar:
  - `mipmap-mdpi`: 48x48 px (lingkaran roundIcon radius 24)
  - `mipmap-hdpi`: 72x72 px (lingkaran roundIcon radius 36)
  - `mipmap-xhdpi`: 96x96 px (lingkaran roundIcon radius 48)
  - `mipmap-xxhdpi`: 144x144 px (lingkaran roundIcon radius 72)
  - `mipmap-xxxhdpi`: 192x192 px (lingkaran roundIcon radius 96)
- Menghapus sisa file template lama `.webp` untuk mencegah konflik resource AAPT2 dan menjamin Play App Bundle mengekstrak ikon kustom.

---

## 2. Integrasi Ikon pada Tampilan Aplikasi (In-App Branding)

### A. New Tab / Homepage Hero Branding
- Menyimpan aset branding `alex_singularity_logo.png` ke dalam `app/src/main/res/drawable/`.
- Memperbarui header halaman New Tab di Compose UI (`BrowserScreen.kt`) sehingga menampilkan logo Singularity bercahaya yang selaras dengan tema obsidian browser.

### B. About Screen / Info Dialog
- Memperbarui dialog atau layar "About / Tentang Alex Browser" di pengaturan agar menyematkan logo resmi Singularity bersama nomor versi aplikasi dan identitas browser.

---

## 3. Verifikasi & Pengujian
- Menjalankan `compile_applet` untuk memastikan seluruh resource XML drawable, mipmap, dan integrasi Compose terkompilasi bersih tanpa peringatan AAPT2.
- Menjalankan unit test `gradle :app:testDebugUnitTest` untuk memvalidasi tidak ada regresi pada struktur package atau manifest.
