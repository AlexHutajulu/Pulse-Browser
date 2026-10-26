# Spesifikasi Mesin

Mesin ini terdiri dari empat lapisan yang bekerja berurutan di dalam
browser WebView:

1. **Mesin aturan jaringan** — sintaks ABP/uBO: domain, path, wildcard,
   exception `@@`, opsi `$third-party`, `$domain`, dan tipe resource
   (script, image, stylesheet, XHR, subdocument, media, font, ping,
   websocket). Kapasitas 150.000 aturan jaringan dan 80.000 kosmetik.
   Daftar: EasyList, EasyPrivacy, PeterLowe, dan empat daftar uBlock
   (filters, badware, privacy, unbreak), dengan cache 24 jam dan
   fallback bundel offline.
2. **Mesin scriptlet** — aturan `##+js(...)`, `#@#+js(...)` (exception),
   dan `#%#//scriptlet(...)` diparsing per-domain dan dieksekusi hanya
   untuk nama yang didukung: `set-constant`,
   `abort-on-property-read`, `abort-on-property-write`,
   `prevent-setTimeout`, `prevent-setInterval`. Nama di luar daftar
   diparsing tetapi tidak dieksekusi.
3. **Pemroses data YouTube** — membersihkan payload pemutar di lapisan
   data (json-prune, `lactMilliseconds`, stub ad_break), mengunci
   `google_ad_status` ke 1, dan menonaktifkan ServiceWorker khusus
   YouTube. Hanya untuk youtube.com dan music.youtube.com.
4. **Lapisan heuristik** — 262 domain iklan, 30 kata kunci, dan 35 pola
   path sebagai lapisan terakhir, setelah mesin aturan jaringan dan
   setelah exception ABP. Kata kunci dan pola path hanya berlaku
   third-party. Pola `/tr/` dan `/ad_status` tidak digunakan karena
   terlalu umum.

## Urutan keamanan (tidak boleh dipindah)

SAFE -> whitelist -> critical-bypass -> segmen streaming ->
domain/tracker hardcoded -> keyword -> mesin aturan jaringan ->
exception ABP (`matchesAllowRule()`) -> lapisan heuristik.

Exception ABP selalu menang atas lapisan heuristik. Surrogate
(inplayer-adx, adsbygoogle, GPT, prebid, analytics) mengembalikan
respons 200 yang aman supaya pemutar tidak terjebak menunggu
dependensi iklan.

## File

- `app/src/main/java/com/example/manager/ScriptletEngine.kt`
- `app/src/main/java/com/yourbrowser/adblock/HeuristicsEngine.kt`
- `app/src/main/assets/adblock/scriptlet_library.js`
- `app/src/main/assets/adblock/youtube_processor.js`
- `app/src/test/java/com/example/EngineTest.kt` (8 pengujian)

## Verifikasi sebelum rilis

- `./gradlew :app:compileDebugKotlin`
- `./gradlew :app:testDebugUnitTest --tests "com.example.EngineTest"`
- `node --check` untuk kedua file JS di atas
- Checklist perangkat: login, YouTube, pembayaran, berita berbayar,
  CAPTCHA, PDF/blob, mode offline, dan 10 tab
