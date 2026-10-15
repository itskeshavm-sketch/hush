# HUSH release keep rules.
#
# Everything here exists because something reaches for it in a way R8 cannot
# see. Remove one and the release APK breaks in a way the debug build never
# showed you, which is the expensive kind of bug.

# --- BouncyCastle ---------------------------------------------------------
# The BC provider is registered by class name at runtime by the JCA, and
# JCAProvider classes are resolved reflectively from the provider list.
-keep class org.bouncycastle.** { *; }
-keep class org.bouncycastle.crypto.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**

# --- WebRTC ---------------------------------------------------------------
# JNI bindings are looked up by exact signature.
-keep class org.webrtc.** { *; }
-keep class com.mesibo.** { *; }
-dontwarn org.webrtc.**

# Native methods must survive or the JNI lookup fails at class-load time.
-keepclasseswithmembernames class * {
    native <methods>;
}

# --- Kotlin coroutines ----------------------------------------------------
-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }

# --- Serialization boundary ----------------------------------------------
# Room/SQLite cursors and the envelope codecs construct these reflectively
# from column names.
-keepclassmembers class * {
    @androidx.room.* <methods>;
}

# Compose keeps its own rules via the compiler plugin; this is for the
# activity that hosts it.
-keep class androidx.activity.ComponentActivity { *; }
-keep class androidx.compose.runtime.** { *; }