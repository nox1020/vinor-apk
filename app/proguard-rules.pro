# R8 / ProGuard — نسخه Release وینور (WebView + FCM + SMS Retriever)

-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod, JavascriptInterface

# کلاس‌های اپ (جلوگیری از کرش پس از minify)
-keep class ir.vinor.app.** { *; }
-keepclassmembers class ir.vinor.app.** { *; }

# پل‌های JavaScript در WebView
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class ir.vinor.app.VinorCacheBridge { *; }
-keep class ir.vinor.app.VinorConnectivityBridge { *; }
-keep class ir.vinor.app.VinorSpeechBridge { *; }
-keep class ir.vinor.app.VinorNotifyBridge { *; }
-keep class ir.vinor.app.VinorDownloadBridge { *; }

# SMS Retriever (ورود OTP بدون مجوز READ_SMS)
-keep class com.google.android.gms.auth.api.phone.** { *; }
-keep class ir.vinor.app.OtpSmsAutoFill { *; }
-keep class ir.vinor.app.OtpSmsAutoFill$* { *; }
-keep class ir.vinor.app.WebLoginOtpSmsHelper { *; }
-keep class ir.vinor.app.VinorOtpBridge { *; }

# اعلان و FCM
-keep class ir.vinor.app.VinorNotifyHelper { *; }
-keep class ir.vinor.app.VinorFcmService { *; }
-keep class ir.vinor.app.VinorFirebaseAutoConfig { *; }
-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.firebase.**
-dontwarn com.google.android.gms.**

# Kotlin / Coroutines
-keep class kotlin.Metadata { *; }
-dontwarn kotlinx.coroutines.**

# OkHttp (در صورت استفاده در آینده از شبکه نیتیو)
-dontwarn okhttp3.**
-dontwarn okio.**

# ViewBinding
-keep class ir.vinor.app.databinding.** { *; }

