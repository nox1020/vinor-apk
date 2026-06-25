# هماهنگی منوی فوتر اپلیکیشن با سایت

> **به‌روزرسانی:** از نسخه WebView-only، منوی فوتر کاملاً در وب (`bottom_nav.html` + `/api/menu`) رندر می‌شود و اپ نیتیو تب جداگانه ندارد. فایل‌های `MenuManager` و Fragmentهای قدیمی دیگر استفاده نمی‌شوند.

## ساختار منوی سایت (API: `/api/menu`)

### منوی عمومی (کاربران غیرلاگین):
1. **خانه** (`home`) - `/` - آیکون: `fa-home`
2. **اکسپلور** (`explore`) - `/public` - آیکون: `fa-magnifying-glass`
3. **راهنما** (`help`) - `/help` - آیکون: `fa-question-circle`
4. **درباره** (`about`) - `/` - آیکون: `fa-info-circle`
5. **ورود** (`login`) - `/express/partner/login` - آیکون: `fa-user`

### منوی همکار اکسپرس (کاربران لاگین شده) - 4 تب:
1. **وینور** (`dashboard`) - `/express/partner/dashboard` - آیکون: `fa-home`
2. **پورسانت** (`commissions`) - `/express/partner/commissions` - آیکون: `fa-chart-line`
3. **روتین** (`routine`) - `/express/partner/routine` - آیکون: `fa-list-check`
4. **من** (`profile`) - `/express/partner/profile` - آیکون: `fa-user`

## ساختار اپلیکیشن

### Fragmentها (منوی همکار):
- `DashboardFragment` - وینور (صفحه اصلی)
- `CommissionsFragment` - پورسانت
- `RoutineFragment` - روتین
- `ProfileFragment` - من
- `ExploreFragment` - اکسپلور (سازگاری عقب‌رو، در منو نمایش داده نمی‌شود)
- `MyPropertiesFragment` - ملک من (سازگاری عقب‌رو، در منو نمایش داده نمی‌شود)

### MenuManager:
- دریافت منو از API سایت (`/api/menu`)
- Mapping بین key منوی سایت و Fragment ID
- Mapping بین icon سایت (FontAwesome) و drawable اندروید
- Fallback به منوی پیش‌فرض در صورت خطا

### MainActivity:
- بارگذاری منو از API در `onCreate`
- به‌روزرسانی داینامیک Bottom Navigation
- به‌روزرسانی URL Fragmentها بر اساس منوی API
- هماهنگ با تغییرات منوی سایت

## ویژگی‌ها:

✅ **هماهنگی کامل**: منوی اپلیکیشن دقیقاً همان منوی سایت است
✅ **داینامیک**: منو از API سایت دریافت می‌شود
✅ **Fallback**: در صورت خطا از منوی پیش‌فرض استفاده می‌شود
✅ **به‌روزرسانی خودکار**: URL Fragmentها بر اساس منوی API تنظیم می‌شوند

