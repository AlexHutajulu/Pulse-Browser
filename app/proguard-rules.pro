# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# Keep WebView @JavascriptInterface methods (AlexBrowserNative & AdBlockDevTools)
-keepattributes JavascriptInterface
-keepattributes *Annotation*
-keepattributes Signature, InnerClasses, EnclosingMethod

-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

-keep class com.example.ui.components.CustomWebView$* {
    @android.webkit.JavascriptInterface <methods>;
}

-keep class com.example.ui.components.CustomWebViewKt$* {
    @android.webkit.JavascriptInterface <methods>;
}

-keep class com.example.devtools.DevToolsJsBridge {
    public *;
}

# Keep Room Database entities and DAOs
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao class *
-dontwarn androidx.room.paging.**

# Keep BrowserTab and persisted models
-keep class com.example.ui.viewmodel.BrowserTab { *; }
-keep class com.example.manager.CustomToolbarScript { *; }
-keep class com.example.manager.BrowserExtension { *; }

# Keep RenderConfigProvider & FrameMetricsTelemetrySync entrypoints
-keep class com.example.manager.RenderConfigProvider { *; }
-keep class com.example.manager.FrameMetricsTelemetrySync { *; }

# OkHttp & Coroutines rules
-dontwarn okhttp3.**
-dontwarn okio.**
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
