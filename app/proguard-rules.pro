# Chaquopy Java API is called reflectively from the Kotlin bridge.
-keep class com.chaquo.python.** { *; }
-dontwarn com.chaquo.python.**

# Python muxer calls TsHub.push by name. R8 was renaming the class to `t` and
# stripping push(), so live frames never reached ExoPlayer on release APKs.
-keep class com.cuboai.local.data.TsHub { *; }
-keepclassmembers class com.cuboai.local.data.TsHub { *; }

# Media3 / ExoPlayer
-dontwarn androidx.media3.**
-keep class androidx.media3.** { *; }

# Encrypted prefs / Tink
-dontwarn com.google.crypto.tink.**

# AWS Cognito Android SDK
-keep class com.amazonaws.** { *; }
-dontwarn com.amazonaws.**
-dontwarn org.apache.commons.logging.**
-dontwarn org.apache.http.**
