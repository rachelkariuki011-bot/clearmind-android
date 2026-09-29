# ClearMind Android Companion

The phone app that enforces what parents and schools set in ClearMind:
Focus Lock (Accessibility), Safe Browsing (local DNS VPN with dwell tracking),
anti-uninstall (Device Admin), and a 5-minute watchdog heartbeat.

## Get an APK in 5 minutes

1. Create an empty GitHub repo named `clearmind-android`.
2. Download this folder (`/android-source.zip` on your ClearMind site), unzip, and push:
   ```bash
   cd clearmind-android
   git init && git add . && git commit -m "ClearMind companion"
   git branch -M main
   git remote add origin https://github.com/YOUR_USER/clearmind-android.git
   git push -u origin main
   ```
3. Open **Actions** in GitHub — "Build ClearMind APK" runs automatically (or press *Run workflow*).
4. When it finishes, the APK is at
   `https://github.com/YOUR_USER/clearmind-android/releases/latest/download/clearmind.apk`.
   Paste that link on the ClearMind **/download** page.

## Signing (recommended before real families use it)

Without these secrets the APK is signed with a debug key (installs fine, but updates must keep the same key).
```bash
keytool -genkey -v -keystore release.jks -alias clearmind -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 release.jks   # copy output
```
Repo → Settings → Secrets → Actions: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.
Optional repo variable `CLEARMIND_API` = your ClearMind site URL (defaults to https://focused-clearminds.lovable.app).
If you use a custom domain, also change the `android:host` in `AndroidManifest.xml`.

## API used by the phone

| Endpoint | Purpose |
|---|---|
| `POST /api/public/v1/pair` `{token \| code, device}` | Swap one-time QR/CLM code for a long-lived device token |
| `GET /api/public/v1/sync-rules` | Schedules, class lock, blocked apps + domains |
| `POST /api/public/v1/report-incident` `{incidents:[{category, domain, visitedAt, dwellSeconds, exitMeasured}]}` | Feeds the parent Alert Inbox + instant alerts |
| `POST /api/public/v1/heartbeat` `{battery, connection, tamper:{accessibility,vpn,deviceAdmin}}` | Every 5 min; >15 min silence = possible bypass |

All but `/pair` need `Authorization: Bearer cmd_…`.

## Notes
- Play Store policy restricts Accessibility/VPN apps; distribute the APK directly or via Android Enterprise.
- Tecno/Infinix/iTel: allow Auto-start + Battery Lab/Power Marathon exemption, or the watchdog is killed.
