# ================================
# RMS Discord ProGuard Rules
# ================================

# Keep source file names and line numbers for better crash reports
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ================================
# Retrofit
# ================================
# Retrofit ships its own consumer rules; only the annotated API interfaces need
# hand-keeping. The broad `-keep class retrofit2.** { *; }` defeated shrinking.
-keepattributes Signature
-keepattributes Exceptions
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-dontwarn retrofit2.**

# ================================
# OkHttp / Okio
# ================================
# Both ship consumer rules that keep what they need. Suppress warnings only.
-dontwarn okhttp3.**
-dontwarn okio.**

# ================================
# Gson (reflection-based serialization)
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
# Keep all data model classes (serialized/deserialized by name via Gson)
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
# LiveKit / WebRTC
# ================================
# LiveKit's AAR ships a consumer proguard.txt that keeps protobuf messages
# (`* extends GeneratedMessageLite`) and the WebRTC JNI surface
# (`livekit.org.webrtc.**`). We only need to keep the app-facing SDK classes and
# silence JNI warnings; the former blanket `-keep class io.livekit.** { *; }`
# was over-broad.
-dontwarn org.webrtc.**
-dontwarn livekit.org.webrtc.**
-dontwarn io.livekit.**
-keep class io.livekit.android.** { *; }

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
# No runtime reflection in Compose; the compiler plugin handles retention.
# Removed the blanket `-keep class androidx.compose.** { *; }`.
-dontwarn androidx.compose.**

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
# No reflection; removed the blanket `-keep class androidx.datastore.** { *; }`.
-dontwarn androidx.datastore.**

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
