# Android App Links — Vinor (Production)

## وضعیت فعلی

- SHA-256 Release از APK امضاشده استخراج و در `assetlinks.json` قرار گرفت.
- Buildهای `assembleRelease` / `assembleDirect` / `bundleRelease` به‌صورت خودکار
  `scripts/generate_assetlinks.ps1` را اجرا می‌کنند و فایل را به‌روز می‌کنند.
- Backend مسیر `GET /.well-known/assetlinks.json` را با **200** و
  `Content-Type: application/json` بدون Redirect/Auth سرو می‌کند.

## Fingerprint فعلی

```
DF:36:AD:92:F5:DC:41:67:CA:A6:C8:4C:CC:3B:19:82:A4:67:0D:F9:AC:84:E1:61:E0:53:9C:18:66:78:B4:38
```

منبع: `vinor/app/data/uploads/apk/vinor_*.apk` (در نبود keystore محلی).

## تولید خودکار

```powershell
# از ریشه apk:
.\scripts\generate_assetlinks.ps1
# یا:
.\gradlew.bat generateAssetLinks
.\gradlew.bat assembleRelease   # قبل از build، assetlinks را refresh می‌کند
```

اولویت منبع fingerprint:
1. Release keystore از `key.properties` (اگر موجود و رمز واقعی باشد)
2. آخرین APK امضاشده در `app/build/outputs` یا `../vinor/app/data/uploads/apk`

## تأیید روی دستگاه

```bash
adb shell pm verify-app-links --re-verify ir.vinor.app
adb shell pm get-app-links ir.vinor.app
adb shell am start -a android.intent.action.VIEW -d "https://vinor.ir/property/TEST" ir.vinor.app
```

وضعیت مطلوب: hostهای `vinor.ir` / `www.vinor.ir` → `verified`.
