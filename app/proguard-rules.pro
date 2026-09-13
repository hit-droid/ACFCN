# Keep Rhino script host interfaces
-keep class org.mozilla.javascript.** { *; }
-keep class com.selfmod.agent.script.** { *; }
-keep class com.selfmod.agent.plugin.** { *; }

# Keep plugin interface so dex plugins can implement it
-keep class com.selfmod.agent.plugin.SelfModPlugin { *; }
-keep class * implements com.selfmod.agent.plugin.SelfModPlugin { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
