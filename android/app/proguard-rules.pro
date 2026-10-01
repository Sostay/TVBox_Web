-keepattributes *Annotation*
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.github.catvod.** { *; }
-keep class com.tvbox.web.** { *; }
