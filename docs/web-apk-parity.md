# Web → APK Inventory — Vinor Express Partner

منبع حقیقت محصول: **Web** روی `https://vinor.ir/express/partner`.  
APK شِل نیتیو خانه است (نه WebView-shell)، مطابق `roadmap/AGENT_GOAL.md`.

- **خانه / چت / ورود OTP / کیف پول / روتین / پروفایل / اعلان / جزئیات ملک:** نیتیو + همان API وب.
- **صفحات فرعی با منطق وب (آموزش، پیگیری، دعوت، نشان‌ها، آگهی، سطوح، تنظیمات، پلاس، درخواست همکاری):** از نسخهٔ **1.0.286** داخل اپ با WebView همان HTML وب.
- **CRM مشتریان (`/customers`):** از **1.0.319** در منوی «من» برای همکار تأییدشده وقتی `crm_ui_enabled` روشن است؛ همان HTML داخل `PartnerPageFragment`.
- **ادمین `/admin`:** خارج از محدودهٔ APK همکار.
- **Bottom tab وب وجود ندارد.** چیپ دانلود APK روی نیتیو نیست (عمدی).

Cookie جلسه: `vinor_session` (Flask). هویت: `user_phone`.

---

## Chrome وب

| قطعه | فایل | APK |
| --- | --- | --- |
| هدر شناور (برند + منو) | `ep_header_float_actions.html` | نیتیو در `DashboardFragment` |
| Drawer راست GPT | `profile_drawer.html` | نیتیو در `activity_main.xml` / `MainActivity` |
| کامپوزر + شیت پیوست | `ai_chat_composer.html` | نیتیو داشبورد |
| مدال مشتری | `home_customer_modal.html` | نیتیو داشبورد (زیرمجموعهٔ فیلدها) |
| مدال همهٔ آگهی‌ها | `home_divar_ads_carousel.html` | نیتیو داشبورد |
| Bottom tab | ندارد | `BottomNavigationView` همیشه `GONE` |
| چیپ دانلود APK | روی خانهٔ وب | عمداً حذف |

---

## صفحات HTML وب و وضعیت APK

وضعیت: **Native** = UI کاتلین. **InAppWeb** = همان صفحهٔ وب داخل اپ. **Browser** = قبلاً Chrome؛ از 1.0.286 InAppWeb. **Redirect** = وب به خانه/مسیر دیگر. **Out of scope** = ادمین.

| صفحه | Route | فایل اصلی | Login | نقش | APK | اختلاف |
| --- | --- | --- | --- | --- | --- | --- |
| خانه AI | `/express/partner/home` | `home.html` | مهمان+ | همه | Native `DashboardFragment` | C جزئی (مدال مشتری کامل وب پیچیده‌تر است) |
| ریشه | `/express/partner/` | redirect home | — | — | Native home | OK |
| داشبورد قدیمی | `/dashboard` | redirect home | — | — | Native home | OK |
| ورود تلفن | `/login` | `auth/login_step1.html` | مهمان | — | Native drawer + `LoginStep1` | OK |
| OTP | `/login/otp` | `auth/login_step2.html` | OTP session | — | Native drawer + `LoginStep2` | OK |
| درخواست همکاری | `/apply` | `apply.html` | لاگین | مهمان→همکار | InAppWeb | قبلاً Browser |
| وضعیت درخواست | `/thanks` | `thanks.html` | لاگین | pending | InAppWeb | قبلاً Browser |
| پیگیری | `/requests` | `requests.html` | pending+ | همکار | InAppWeb | **قبلاً اشتباه به روتین می‌رفت** |
| روتین روزانه | `/routine` | `routine.html` | مهمان+ | همه | Native `RoutineFragment` | در منوی وب نیست؛ دیپ‌لینک دارد |
| کیف پول | `/commissions` | `commissions.html` | pending+ | همکار | Native `CommissionsFragment` | Partial UI |
| تراکنش‌ها | `/commissions/transactions` | `commissions_txn_list.html` | approved | همکار | از کیف پول نیتیو / InAppWeb دیپ‌لینک | Partial |
| نشان‌ها | `/favorites` | `favorites.html` | pending+ | همکار | InAppWeb | قبلاً Browser |
| آموزش | `/training` | `training.html` | مهمان+ | همه | InAppWeb | قبلاً Browser |
| دعوت | `/invite` | `invite_colleagues.html` | pending+ | همکار | InAppWeb | قبلاً Browser |
| سطوح | `/levels` | `coop_levels.html` | مهمان+ | همه | InAppWeb | قبلاً Browser |
| تنظیمات | `/settings` | `settings.html` | pending+ | همکار | InAppWeb | قبلاً Browser |
| حافظه زنده | `/settings/memory` | `settings_memory.html` | pending+ | همکار | از تنظیمات InAppWeb | Partial |
| پلاس | `/plus` | `plus.html` | مهمان+ | همه | InAppWeb | دیپ‌لینک |
| آگهی‌های من | `/listings` | `special_listings.html` | special | پلاس | InAppWeb | قبلاً Browser |
| آگهی جدید | `/listings/new` | `special_listing_new.html` | special | پلاس | InAppWeb از listings | OK |
| اینباکس پیشنهاد | `/inbox-offers` | `special_offer_inbox.html` | special | پلاس | InAppWeb دیپ‌لینک | Partial |
| پروفایل (صفحه) | `/profile` | redirect `home?menu=1` | — | — | Native drawer / `ProfileFragment` | OK |
| ویرایش پروفایل | `/profile/edit` | `profile_edit.html` | pending+ | همکار | Native `ProfileEditFragment` | OK |
| اعلان / پستچی | `/notifications` → `home?thread=__postchi__` | home | pending+ | همکار | Native `NotificationsFragment` | D: لیست نیتیو نه ترد پستچی داخل چت |
| جزئیات ملک | `/lands/<code>` → `home?land=` | chat-detail | مهمان+ | همه | Native `LandDetailFragment` | D: کارت نیتیو به‌جای تزریق چت |
| دیوار | `/divar/<token>` → `home?divar=` | chat-detail | مهمان+ | همه | ACTION_VIEW دیوار / کارت آگهی | Partial |
| CRM مشتری | `/customers` | `customers.html` | approved+`crm_ui_enabled` | همکار | InAppWeb `PartnerPageFragment`؛ ردیف «مشتریان» فقط اگر تأییدشده و فلگ روشن | OK از 1.0.319 |
| آفلاین PWA | `/offline` | `offline.html` | any | — | `VinorOfflineUi` + بنر | H جدا |
| شرایط همکاری | `/cooperation-terms` | `cooperation_terms.html` | مهمان+ | — | InAppWeb | OK |
| شرایط استفاده | `/legal/terms` | `legal/terms.html` | any | — | InAppWeb | قبلاً Browser |
| حریم خصوصی | `/legal/privacy` → `/privacy` | public | any | — | InAppWeb / public | OK |
| کمک / AI | `/help` `/ai` | redirect home AI | — | — | Native home | OK |
| عمومی `/` `/public` | redirect partner home | — | مهمان | — | Native home | OK |
| ادمین | `/admin/*` | admin templates | ادمین | ادمین | Out of scope | — |

### ناوبری وب (از کجا → به کجا)

- هدر منو → drawer.
- Drawer مهمان: آموزش + OTP.
- Drawer تأییدنشده: درخواست/وضعیت، دعوت، آموزش.
- Drawer تأییدشده: پیگیری، مشتریان (فقط `crm_ui_enabled`)، کیف پول، نشان‌ها، آموزش، دعوت، آگهی (پلاس)، پروفایل، سطوح، تنظیمات، خروج.
- خانه خالی: کارت همکاری → apply/login؛ خرید ملک → مدال آگهی؛ سطوح → `/levels`.
- چت: پیوست، ارسال، تردها در drawer.

APK همان گراف را دنبال می‌کند؛ پیگیری از 1.0.286 به `/requests` می‌رود.

---

## APIهای مشترک (قرارداد یکسان)

ورود: `POST /api/login-request`, `POST /api/verify`, `POST /otp/resend`, `POST /api/logout`.  
خانه: `GET /home/data` (شامل `crm_ui_enabled`)، `GET /home` HTML آگهی، `POST /home/register-customer`, `GET /ai/history`, `POST /ai/chat`, `POST /ai/upload`.  
مشتریان CRM: HTML `/customers` (API JSON جدا در APK نیست). فلگ منو: `crm_ui_enabled` از `home/data`، و از `profile/data` اگر همان کلید آنجا باشد.  
ملک: `GET /api/express/{code}`, `GET /lands/{code}/chat-detail`.  
کیف پول / روتین / پروفایل / اعلان / یادداشت: همان endpointهای وب.

CSRF در API JSON همکار عمدتاً session cookie است؛ APK کوکی را روی `CookieManager` نگه می‌دارد.

---

## Deep Link

| URL وب | APK از 1.0.286 |
| --- | --- |
| `/property/{id}`, `/express/{code}`, `/express/partner/lands/{id}`, `home?land=` | `LandDetailFragment` — مهمان مجاز |
| `/express/partner/home` | داشبورد نیتیو |
| `/express/partner/requests` و بقیهٔ صفحات فرعی | `PartnerPageFragment` |
| `/express/partner/customers` | `PartnerPageFragment` (نیاز به نشست؛ از 1.0.319) |
| `/express/partner/commissions` | نیتیو کیف پول |
| `/express/partner/routine` | نیتیو روتین |
| `/express/partner/notifications` | نیتیو اعلان |
| `/express/partner/login` | ورود نیتیو / drawer |
| مسیر نیازمند لاگین بدون نشست | ذخیره در `DeepLinkPendingStore` → پس از OTP مقصد |

---

## Offline / Cache / Update / Security (خلاصه معماری APK)

| لایه | کلاس‌ها | وضعیت |
| --- | --- | --- |
| بنر قطعی نت | `MainActivity.updateOfflineBanner` | فعال |
| Overlay وب | `VinorOfflineUi` | روی WebViewهای داخلی |
| Snapshot/interceptor | `VinorOfflinePageStore`, `VinorWebResourceInterceptor` | کد موجود؛ شِل نیتیو خانه را intercept نمی‌کند |
| صف POST | `VinorOfflineQueue` | فقط مسیرهای غیرحساس؛ موفقیت جعلی مالی ندارد |
| آپدیت APK | `VinorAppUpdate*` + PackageInstaller | فعال؛ بدون چیپ شناور |
| FCM | `VinorFcmService` / `VinorFcmRegistrar` | فعال |
| سکرت در APK | ندارد | کوکی جلسه روی دستگاه |

---

## نقش‌ها

| نقش وب | APK |
| --- | --- |
| Guest | خانه + OTP drawer + آموزش/سطوح InAppWeb |
| Logged unapproved | apply/thanks + خانه |
| Approved partner | پیگیری، مشتریان (فلگ CRM)، کیف پول، چت |
| Vinor Plus (special) | آگهی‌های من |
| Admin | خارج از APK |
