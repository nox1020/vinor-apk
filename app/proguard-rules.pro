# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in ${sdk.dir}/tools/proguard/proguard-android.txt

# Keep WebView-related classes if needed in future.
-keep class ir.vinor.app.OtpSmsAutoFill { *; }
-keep class ir.vinor.app.OtpSmsAutoFill$* { *; }
-keep class ir.vinor.app.WebLoginOtpSmsHelper { *; }

