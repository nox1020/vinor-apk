# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in ${sdk.dir}/tools/proguard/proguard-android.txt

# Keep SMS Retriever / Play Services classes used via reflection in release.
-keep class com.google.android.gms.auth.api.phone.** { *; }
-keep class ir.vinor.app.OtpSmsAutoFill { *; }
-keep class ir.vinor.app.OtpSmsAutoFill$* { *; }
-keep class ir.vinor.app.WebLoginOtpSmsHelper { *; }

