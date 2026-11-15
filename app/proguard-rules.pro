# AdShield Pro v2.0 ProGuard / R8 Rules

# Keep kotlinx.serialization classes
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.adshield.pro.**$$serializer { *; }
-keepclassmembers class com.adshield.pro.** {
    *** Companion;
}
-keepclasseswithmembers class com.adshield.pro.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep Room entities & DAOs
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# Keep Google Play Billing
-keep class com.android.vending.billing.**

# Official TDLib Java API (org.drinkless.tdlib).
# TDLib's JNI bridge constructs TdApi objects by constructor id, so R8 must keep every generated
# class, its fields, constructors and the native entry points in Client.
-keep class org.drinkless.tdlib.** { *; }
-keepclassmembers class org.drinkless.tdlib.TdApi$* { *; }
-keepattributes *Annotation*, InnerClasses, Signature
-dontwarn org.drinkless.tdlib.**

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# World Monitor 2035 WebView @JavascriptInterface bridge & serializable models
-keepattributes *Annotation*,JavascriptInterface,Signature,InnerClasses,EnclosingMethod
-keep @interface android.webkit.JavascriptInterface
-keep class com.adshield.pro.engine.worldmonitor.WorldMonitorJsBridge {
    public <init>(...);
    @android.webkit.JavascriptInterface <methods>;
    public *;
}
-keep interface com.adshield.pro.engine.worldmonitor.WorldMonitorJsBridge$Listener { *; }
-keep class com.adshield.pro.domain.worldmonitor.** { *; }

