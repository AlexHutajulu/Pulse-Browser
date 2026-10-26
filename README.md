<div align="center">
<img width="1200" height="475" alt="GHBanner" src="https://ai.google.dev/static/site-assets/images/share-ais-513315318.png" />
</div>

# Run and deploy your AI Studio app

This contains everything you need to run your app locally.

View your app in AI Studio: https://ai.studio/apps/acafb031-d55e-4c63-b286-1ad6d1d60b95

## Run Locally

**Prerequisites:**  [Android Studio](https://developer.android.com/studio)


1. Open Android Studio
2. Select **Open** and choose the directory containing this project
3. Allow Android Studio to fix any incompatibilities as it imports the project.
4. (Optional) If using AI features, configure secrets via the **Secrets panel in AI Studio** (mapped to `.env` / `.env.example`).
5. Run the `debug` build variant on an emulator or physical device (Android Gradle Plugin automatically manages the local development debug keystore).

## Release Signing & Play App Signing

- **Upload Key vs. App Signing Key:** Local or CI release builds (`assembleRelease` / `bundleRelease`) are signed using your **upload key** (`KEYSTORE_PATH`, `STORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` environment variables). When distributing via Google Play, **Play App Signing** verifies the upload signature and re-signs the final distribution APK/AAB with the production **app-signing key** managed by Google Play.
- Never commit `.jks` / `.keystore` files or signing passwords to version control.

## Security, Privacy & Network Transparency

### 1. Private Tab (Incognito) Isolation & Android WebView Limitations
- **Ephemeral Browsing & Storage:** Private tabs never persist URLs to Room history or `SharedPreferences`, enforce `WebSettings.LOAD_NO_CACHE`, disable persistent `domStorageEnabled` (`localStorage`), and inject an isolated in-memory `Storage` polyfill for the lifetime of the tab.
- **Third-Party Cookies:** Blocked unconditionally on all Private tabs (`CookieManager.setAcceptThirdPartyCookies(webView, false)`), as well as in `STRICT` Privacy Shield mode.
- **Android WebView Single-Process Cookie Engine Constraint:** Android's system `WebView` shares a single `CookieManager` instance across the app process. To prevent closing a Private tab from logging the user out of active sessions in open Regular tabs, global session cookie cleanup (`removeSessionCookies`) is executed automatically when all Private tabs are closed **only if no Regular web tabs are currently active**. Full cookie and site storage deletion is available at any time via **Settings → Clear Cookies & Site Data**.

### 2. WebRTC & Anti-Fingerprinting Timing
- **JavaScript API Wrapping (`onPageStarted`):** WebRTC IP leak protection and Anti-Fingerprinting (hardware concurrency, device memory, platform normalization, and strict canvas/audio noise) operate by wrapping browser JavaScript APIs during `WebViewClient.onPageStarted`. Domain bypasses for real-time video conferencing (`google.com`, `discord.com`, `zoom.us`) enforce strict dot-separated domain boundary validation.

### 3. Database Encryption & Non-Destructive Recovery
- **SQLCipher Encryption:** Local browsing history, bookmarks, and download metadata are stored in an encrypted SQLCipher database (`browser_database_encrypted`) using a 256-bit passphrase protected by `EncryptedSharedPreferences` (Android Keystore).
- **Zero Silent Data Wipe:** If the encrypted database already exists on disk, the browser never silently generates a new random passphrase or deletes user database files upon Keystore failure. Instead, it preserves existing database files and surfaces the actual encryption status in **Settings → Privacy & Security**.

### 4. External Network Connections
Alex Browser does not include background analytics or telemetry SDKs. Outbound network connections are restricted to:
1. **User-Initiated Navigation & Search:** Websites visited by the user and queries sent to the user's selected search engine (Google, Bing, DuckDuckGo, Yahoo, Brave, Yandex).
2. **Android Safe Browsing:** Built-in WebView Safe Browsing verification (`safeBrowsingEnabled = true`).
3. **AdShield Filter List Updates (HTTPS-Only with SSRF Protection):**
   - `https://easylist.to/easylist/easylist.txt`
   - `https://easylist.to/easylist/easyprivacy.txt`
   - `https://pgl.yoyo.org/adservers/serverlist.php?hostformat=adblockplus&showintro=0&mimetype=plaintext`
4. **User-Initiated Downloads & Extension Imports:** Files or HTTPS userscripts/extensions explicitly requested by the user, validated against private/loopback IP ranges and HTTPS downgrade attacks with HTTP caching disabled (`useCaches = false` / `CacheControl.FORCE_NETWORK`).

