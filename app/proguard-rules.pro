# Proguard and R8 optimization rules for Hermes Mobile

# General Attributes
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,SourceFile,LineNumberTable

# KotlinX Serialization
-keepclassmembers class * {
    *** Companion;
}
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers class * implements kotlinx.serialization.KSerializer {
    <fields>;
    <methods>;
}
-keep,allowobfuscation,allowshrinking class * {
    @kotlinx.serialization.Serializable class *;
}
-keepclassmembers class * {
    @kotlinx.serialization.SerialName <fields>;
}

# Domain, Storage, Pairing, Network & Auth Models
-keep class app.hermes.mobile.core.model.** { *; }
-keep class app.hermes.mobile.core.pairing.** { *; }
-keep class app.hermes.mobile.core.storage.** { *; }
-keep class app.hermes.mobile.core.auth.** { *; }
-keep class app.hermes.mobile.core.security.** { *; }
-keep class app.hermes.mobile.core.network.** { *; }

# Room Database
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# OkHttp & Okio
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**

# AndroidX Security Crypto
-keep class androidx.security.crypto.** { *; }

# Jetpack Compose & Material 3
-keep class androidx.compose.** { *; }
-keep class com.google.android.material.** { *; }
