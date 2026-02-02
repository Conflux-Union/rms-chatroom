# ================================
# RMS Discord ProGuard Rules
# ================================

# Keep source file names and line numbers for better crash reports
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ================================
# Retrofit
# ================================
-keepattributes Signature
-keepattributes Exceptions
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }

# ================================
# OkHttp
# ================================
-dontwarn okhttp3.**
-keep class okhttp3.** { *; }
-dontwarn okio.**
-keep class okio.** { *; }

# ================================
# Gson
# ================================
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses
-keepattributes EnclosingMethod

# Keep Gson TypeToken and generic signatures
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
-keepclassmembers class * extends com.google.gson.reflect.TypeToken {
    <init>();
}

# Keep all data model classes
-keep class cn.net.rms.chatroom.data.model.** { *; }
-keepclassmembers class cn.net.rms.chatroom.data.model.** {
    <fields>;
    <init>(...);
}
# Keep all API request/response body classes
-keep class cn.net.rms.chatroom.data.api.** { *; }
-keepclassmembers class cn.net.rms.chatroom.data.api.** {
    <fields>;
    <init>(...);
}
# Keep LiveKit data classes
-keep class cn.net.rms.chatroom.data.livekit.ParticipantInfo { *; }
-keep class cn.net.rms.chatroom.data.livekit.ConnectionState { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# ================================
# LiveKit
# ================================
-keep class io.livekit.** { *; }
-dontwarn io.livekit.**
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# ================================
# Room Database
# ================================
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# ================================
# Hilt / Dagger
# ================================
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ComponentSupplier { *; }
-keep class * implements dagger.hilt.internal.GeneratedComponent { *; }
-keepclasseswithmembers class * {
    @dagger.* <methods>;
}
-keepclasseswithmembers class * {
    @javax.inject.* <fields>;
}
-keepclasseswithmembers class * {
    @javax.inject.* <init>(...);
}

# ================================
# Jetpack Compose
# ================================
-dontwarn androidx.compose.**
-keep class androidx.compose.** { *; }

# ================================
# Coroutines
# ================================
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# ================================
# Kotlin Serialization (if used)
# ================================
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault

# ================================
# DataStore
# ================================
-keep class androidx.datastore.** { *; }

# ================================
# WebSocket
# ================================
-keep class cn.net.rms.chatroom.data.websocket.** { *; }

# ================================
# Enums
# ================================
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ================================
# Parcelable
# ================================
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}
