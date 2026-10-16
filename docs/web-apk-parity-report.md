# گزارش همگامی Web و APK — Vinor

نسخهٔ APK این گزارش: **1.0.319** (مشتریان CRM؛ جزئیات قدیمی‌تر از 1.0.286)  
مرجع وب: `/express/partner` روی `vinor.ir`  
جزئیات صفحه به صفحه: [`web-apk-parity.md`](web-apk-parity.md)

## تصمیم معماری

خانهٔ همکار در APK **نیتیو** می‌ماند (هدف محصول در `AGENT_GOAL.md` و قید قبلی: نه WebView-shell، نه ۴ تب پایین، نه چیپ دانلود).  
منطق تجاری صفحات فرعی دوباره نیتیو نوشته نشد؛ همان HTML وب داخل اپ (`PartnerPageFragment`) بارگذاری می‌شود.

## اعداد

| شاخص | مقدار |
| --- | ---: |
| Routeهای HTML محصول همکار (بدون redirect) | 24 |
| Redirectهای وب به خانه/مسیر دیگر | 12 |
| Route ادمین | خارج از محدوده |
| مقصد نیتیو در nav_graph | 12 + `partnerPageFragment` |
| صفحات Missing به‌عنوان سطح منو | 0 (`/customers` از 1.0.319 در منو، پشت `crm_ui_enabled`) |
| Partial | 5 |
| UI mismatch باقی | خانهٔ نیتیو در حد جزئیات مدال مشتری / پستچی |
| Functional mismatch باقی | جزئیات ملک نیتیو در برابر تزریق چت وب |
| API mismatch | 0 روی مسیرهای پیاده‌شده (همان backend) |

## دسته‌بندی اختلاف (پس از 1.0.286)

| کد | معنی | باقی‌مانده |
| --- | --- | --- |
| A Missing | `/customers` از 1.0.319 در منوی «من» است؛ همان فلگ وب `crm_ui_enabled` + تأیید همکار | رفع شد |
| B Partial | مدال مشتری بدون لیست فایل/پلاس/اسلات کامل؛ کیف پول بدون لیست تراکنش جدا؛ تنظیمات/حافظه از وب | متوسط |
| C UI | پیکسل خانه عمدتاً هم‌تراز 1.0.285 | کم |
| D Functional | ملک نیتیو ≠ `home?land=` در چت؛ پستچی = لیست اعلان نه ترد `__postchi__` | آگاهانه / بعدی |
| E API | قرارداد یکی است | — |
| F Navigation | پیگیری قبلاً روتین بود — **رفع شد** | — |
| G Auth | OTP نیتیو + کوکی؛ مقصد دیپ‌لینک بعد از ورود — **رفع شد** | — |
| H Offline | بنر + overlay وب؛ interceptor شِل قدیمی به خانهٔ نیتیو وصل نیست | بعدی |
| I Android | کیبورد/مجوز/آپدیت/FCM | پایدار |

## وضعیت‌ها

| حوزه | وضعیت |
| --- | --- |
| Authentication | OTP نیتیو/drawer، `vinor_session`، logout کوکی+FCM |
| Deep Link | ملک مهمان، `home?land=`، صفحات فرعی InAppWeb، pending بعد از OTP |
| Offline | بنر قطعی؛ خانهٔ نیتیو بدون snapshot HTML |
| Cache | سیاست و استور موجود؛ خانه از PartnerApi زنده می‌خواند |
| Update | دانلود امن + PackageInstaller؛ بدون چیپ شناور |
| Security | HTTPS، بدون سکرت در APK، App Links بدون `/d` |
| Performance | کاروسل آگهی lazy؛ WebView فقط صفحات فرعی |

## مشتریان CRM (1.0.345)

ردیف «مشتریان» در تب من فقط وقتی `is_approved` و `crm_ui_enabled` هر دو روشن باشند. فلگ روی `GET /express/partner/home/data` است؛ اگر `profile/data` هم کلید را بفرستد همان مقدم است. باز شدن صفحه نیتیو `CustomersFragment` است. فهرست، تب و فرم از HTML همان `GET/POST /express/partner/customers` خوانده می‌شود. API JSON جدا نیست. POST با `X-CSRFToken` و Referer همان صفحه است و در صف آفلاین نمی‌رود.

فایل‌ها: `CustomersFragment.kt`, `PartnerCustomers.kt`, `PartnerApi.kt`, `MainActivity.kt`, `DeepLinkRouter.kt`, `version.txt`.

## تغییرات دور 1.0.286

1. Inventory کامل وب/APK در `docs/web-apk-parity.md`.
2. «پیگیری» منو → `/express/partner/requests` (دیگر روتین نیست).
3. آموزش، دعوت، نشان‌ها، آگهی، سطوح، تنظیمات، درخواست همکاری، شرایط: داخل اپ نه مرورگر.
4. Deep link ملک برای مهمان؛ `home?land=`؛ ذخیرهٔ مقصد تا بعد از OTP.
5. Back: drawer → overlay خانه → تاریخچهٔ WebView → pop.

## فایل‌های تغییر این دور

`PartnerPageFragment.kt`, `BaseWebViewFragment.kt`, `MainActivity.kt`, `DeepLinkRouter.kt`, `DashboardFragment.kt`, `LoginStep2Fragment.kt`, `nav_graph.xml`, `DeepLinkRouterTest.kt`, `version.txt`, `docs/web-apk-parity.md`, `docs/web-apk-parity-report.md`

## تست

- Unit `DeepLinkRouterTest` روی CI (SDK محلی این ماشین نیست).
- دستی لازم: ورود OTP و برگشت به لینک ملک؛ پیگیری از منو؛ آموزش بدون خروج از اپ؛ Back روی صفحهٔ وب داخلی.

## جدول نهایی Route

| Web Route | APK | Status | UI | Function | API | Offline | Deep Link |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `/express/partner/home` | `DashboardFragment` | Native | aligned | aligned | same | banner | yes |
| `/login` `/login/otp` | Drawer + LoginStep | Native | aligned | aligned | same | no | yes |
| `/apply` `/thanks` | `PartnerPageFragment` | InAppWeb | web | web | same | overlay | pending+ |
| `/requests` | `PartnerPageFragment` | InAppWeb | web | **fixed nav** | same | overlay | yes |
| `/routine` | `RoutineFragment` | Native | Partial | aligned | same | banner | yes |
| `/commissions` | `CommissionsFragment` | Native | Partial | aligned | same | banner | yes |
| `/favorites` `/training` `/invite` `/levels` `/settings` `/plus` `/listings` | `PartnerPageFragment` | InAppWeb | web | web | same | overlay | yes |
| `/profile/edit` | `ProfileEditFragment` | Native | aligned | aligned | same | banner | via profile |
| `/notifications` | `NotificationsFragment` | Native | Partial | list≠thread | same | banner | yes |
| `/lands/<code>` | `LandDetailFragment` | Native | different chrome | D vs chat inject | same | banner | guest yes |
| `/customers` | `CustomersFragment` | Native | web HTML | web؛ منو فقط approved+`crm_ui_enabled` | same HTML | footer | yes |
| `/admin` | — | Out of scope | — | — | — | — | no |
