# یادداشت انتشار ۱.۰.۱۴۹۴

نسخهٔ نام: 1.0.1494
نسخهٔ کد فروشگاه (پلی و بازار): 11494
نسخهٔ کد نصب مستقیم: همان ۱۱۴۹۴، مگر اینکه شمارهٔ اجرای CI بزرگ‌تر باشد؛ در آن صورت فقط APK سایت بالاتر می‌رود و پلی و بازار دست نمی‌خورند.

## پلی — فارسی (حداکثر ۵۰۰ نویسه)

سرصفحه و نوار پایین دیگر زیر ساعت و دکمه‌های سیستم نمی‌روند.
آگهی و پیگیری از سمت مالک حذف نمی‌شوند و بایگانی می‌شوند.
در آگهی‌های من، مدیریت ملک و پیگیری، برگهٔ فعال و بایگانی‌شده با تعداد دیده می‌شود.
به‌روزرسانی این نسخه از خود فروشگاه انجام می‌شود.

## Play — English (500 characters)

The header and bottom bar sit clear of the status bar and system navigation buttons.
Listings and follow-ups are archived instead of deleted by the owner.
My ads, property management, and follow-ups have Active and Archived tabs with counts.
This version updates from the store itself.

## کافه‌بازار

همان متن فارسی بخش پلی.

## نصب مستقیم از vinor.ir

همان قابلیت‌ها. به‌روزرسانی هنوز با دانلود APK امضاشده از وینور است.

## ساخت آرتیفکت فروشگاه

طعم‌ها از version.txt کد می‌گیرند. متغیر GITHUB_RUN_NUMBER را برای پلی و بازار تنظیم نکنید؛ آن متغیر فقط versionCode نصب مستقیم را بالا می‌برد.

```
./gradlew :app:bundlePlayRelease :app:assembleBazaarRelease
```

خروجی:

- app/build/outputs/bundle/playRelease/ — AAB پلی
- app/build/outputs/apk/bazaar/release/ — APK بازار

APK سایت جدا است:

```
./gradlew :app:assembleSideloadRelease
```

روی CI همین سه خروجی با نام‌های `vinor-play-aab`، `vinor-bazaar-apk` و `vinor-direct-apk` بارگذاری می‌شوند. امضای محلی بدون `key.properties` ممکن نیست.
