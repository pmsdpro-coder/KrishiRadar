# LiteRT native code — prevent R8 from stripping JNI entry points
-keep class com.google.ai.edge.litert.** { *; }
-keep class com.google.ai.edge.litert.lm.** { *; }
-keepclassmembers class * {
    @com.google.ai.edge.litert.* <methods>;
}

# Preserve native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# LiteRT-LM inference engine JNI bindings
-keep class org.tensorflow.lite.** { *; }
-keep class org.tensorflow.lite.task.** { *; }
-keep interface org.tensorflow.lite.** { *; }
-dontwarn org.tensorflow.lite.**

# Keep LLM session and callback interfaces
-keep interface com.google.ai.edge.litert.lm.LlmInference$LlmInferenceResultListener { *; }
-keep class com.google.ai.edge.litert.lm.LlmInference { *; }
-keep class com.google.ai.edge.litert.lm.LlmInference$LlmInferenceOptions { *; }
-keep class com.google.ai.edge.litert.lm.LlmInference$LlmInferenceOptions$Builder { *; }

# WorkManager
-keep class androidx.work.** { *; }
-keep class * extends androidx.work.Worker
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# Hilt
-keepclassmembers class ** {
    @dagger.hilt.android.HiltAndroidApp <methods>;
}

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# Kotlin
-keep class kotlin.Metadata { *; }
-keepclassmembers class kotlin.Metadata {
    public <methods>;
}

# Enums
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
