# ---- Rhino script host ----
-keep class org.mozilla.javascript.** { *; }
-dontwarn org.mozilla.javascript.**

# ---- App classes touched via JNI / reflection / plugins ----
# JNI bridge: method names must not be renamed.
-keepclasseswithmembernames class com.selfmod.agent.offline.native.** { native <methods>; }
-keep class com.selfmod.agent.offline.native.LocalLlmEngine { *; }
-keep class com.selfmod.agent.offline.native.LocalLlmEngine$TokenCallback { *; }
-keep class com.selfmod.agent.offline.native.LocalLlmEngine$LoadCallback { *; }

# Script sandbox API
-keep class com.selfmod.agent.script.** { *; }

# Plugin system: dex plugins implement this interface by name.
-keep class com.selfmod.agent.plugin.** { *; }
-keep interface com.selfmod.agent.plugin.SelfModPlugin { *; }
-keep class * implements com.selfmod.agent.plugin.SelfModPlugin { *; }

# ---- OkHttp / Okio ----
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ---- androidx.security-crypto / Tink (reflection heavy) ----
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**

# Keep enum values used by JSON serialization
-keepclassmembers enum * { *; }

# Keep line numbers for readable crash reports
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
