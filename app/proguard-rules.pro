# Keep JNI entry points - they are resolved by name at runtime.
-keepclasseswithmembernames class com.dsh.codepocket.terminal.PtyNative {
    native <methods>;
}

# javascript interfaces
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
