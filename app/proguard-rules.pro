# youtubedl-android maps yt-dlp's JSON with Jackson (reflection)
-keep class com.yausername.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-dontwarn com.fasterxml.jackson.**
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod

# Apache Commons used by the library
-dontwarn org.apache.commons.**

# kotlinx.serialization (HttpDownload's meta.json)
-keepclassmembers class io.github.hamzabhai.raftaaro.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class io.github.hamzabhai.raftaaro.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp optional platforms
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
