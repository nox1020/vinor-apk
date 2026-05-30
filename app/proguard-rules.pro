# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in ${sdk.dir}/tools/proguard/proguard-android.txt

# Keep SMS Retriever / Play Services classes used via reflection in release.
-keep class com.google.android.gms.auth.api.phone.** { *; }
-keep class ir.vinor.app.OtpSmsAutoFill { *; }
-keep class ir.vinor.app.OtpSmsAutoFill$* { *; }
-keep class ir.vinor.app.WebLoginOtpSmsHelper { *; }
-keep class ir.vinor.app.VinorSpeechBridge { *; }
-keep class ir.vinor.app.VinorNotifyBridge { *; }
-keep class ir.vinor.app.VinorNotifyHelper { *; }
-keep class ir.vinor.app.VinorFcmService { *; }
-keep class ir.vinor.app.VinorFirebaseAutoConfig { *; }
-keep class com.google.firebase.** { *; }

