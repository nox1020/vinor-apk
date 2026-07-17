# Android App Links — Vinor

## هدف

با باز کردن هر لینک `https://vinor.ir/...` یا `https://www.vinor.ir/...`:

- اگر اپ نصب باشد → مستقیماً اپ باز شود (بدون دیالوگ مرورگر، پس از تأیید Digital Asset Links).
- اگر اپ نصب نباشد → همان لینک در مرورگر نمایش داده شود.

## ۱) قرار دادن `assetlinks.json` روی سرور

فایل باید دقیقاً از این آدرس‌ها با HTTP 200 و `Content-Type: application/json` سرو شود:

- https://vinor.ir/.well-known/assetlinks.json
- https://www.vinor.ir/.well-known/assetlinks.json  (در صورت استفاده از www)

مسیر پیشنهادی در ریپوی وب:

```
app/static/.well-known/assetlinks.json
```

در پروژه Vinor یک route برای سرو این فایل اضافه شده است.

### پر کردن SHA-256

1. APK/AAB امضاشدهٔ release را نصب کنید.
2. لاگ‌کت را فیلتر کنید: `VinorAppLinksCerts`
3. خط SHA-256 را کپی کنید (فرمت `AA:BB:CC:...`).
4. مقدار `REPLACE_WITH_RELEASE_SHA256_FINGERPRINT` را در `assetlinks.json` جایگزین کنید.

یا با keytool:

```bash
keytool -list -v -keystore path/to/vinor-release.jks -alias vinor_release
```

خط `SHA256:` را بدون فاصلهٔ اضافه در JSON بگذارید.

اسکریپت کمکی (از ریشهٔ پوشه apk):

```powershell
.\scripts\generate_assetlinks.ps1
```

## ۲) تأیید نصب لینک‌ها روی دستگاه

```bash
adb shell pm get-app-links ir.vinor.app
adb shell pm verify-app-links --re-verify ir.vinor.app
```

وضعیت مطلوب: `vinor.ir` و `www.vinor.ir` → `verified`.

تست دستی:

```bash
adb shell am start -a android.intent.action.VIEW -d "https://vinor.ir/property/TESTCODE" ir.vinor.app
```

## ۳) مسیرهای پشتیبانی‌شده در اپ

| لینک | رفتار |
|------|--------|
| `/property/{id}` | جزئیات فایل (`/express/partner/lands/{id}`) — نیاز به ورود |
| `/express/{code}` | کارت عمومی اکسپرس — بدون ورود |
| `/express/partner/...` | صفحات پنل — در صورت نیاز ورود سپس بازگشت به لینک |
| ناشناخته | صفحه اصلی پنل (`/express/partner/`) |

## ۴) جریان ورود

اگر مسیر `requiresAuth` باشد و کوکی جلسه نباشد:

1. لینک مقصد در `DeepLinkPendingStore` ذخیره می‌شود.
2. صفحه `/express/partner/login?next=...` باز می‌شود.
3. پس از ورود موفق، همان لینک اولیه باز می‌شود.

## ۵) Manifest

`MainActivity` با `android:autoVerify="true"` برای:

- `https://vinor.ir/*`
- `https://www.vinor.ir/*`
