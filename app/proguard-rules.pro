# Chaquopy Java API is called reflectively from the Kotlin bridge.
-keep class com.chaquo.python.** { *; }
-dontwarn com.chaquo.python.**

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
