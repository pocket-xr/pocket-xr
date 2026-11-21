# 1.1.11 (Google Play): R8 for a Play upload (-Ppocketxr.play=true). Conservative: Pocket XR's own classes and the
# libraries that are reached from native code, by reflection or through Binder keep their names and members, so only
# unused library code is removed and the rest of the libraries are renamed. The mapping goes to Play with the bundle.

# Pocket XR: reflection on hidden Android APIs, the Shizuku user service (started by class name), AIDL stubs.
-keep class com.hayeco.** { *; }

# Native code and JNI: MediaPipe, LiteRT and TensorFlow Lite, and their protobuf messages.
-keep class com.google.mediapipe.** { *; }
-keep class com.google.ai.edge.litert.** { *; }
-keep class org.tensorflow.** { *; }
-keep class com.google.protobuf.** { *; }
-keep class com.google.android.datatransport.** { *; }

# 1.6.6: Flogger, MediaPipe's logger. FluentLogger.forEnclosingClass() finds the class that asked for a logger by
# walking the call stack for FluentLogger's own frame. With R8 renaming it (to df0 in 1.6.2) and inlining and
# outlining its helpers, the walk found no caller and MediaPipe's Graph failed in its static setup
# ("IllegalStateException: no caller found on the stack for: df0"), so hand tracking never started in the Play build
# (a Pixel 10 Pro XL on Android 17, 1.6.2 from Google Play). Kept whole, so its frames and names stay as written.
-keep class com.google.common.flogger.** { *; }

# Shizuku: the binder handed over by its provider, and the user service it starts.
-keep class rikka.** { *; }
-keep class moe.shizuku.** { *; }

# WorkManager (brought by a library) finds its Room database by class name; R8 broke that at start on the first try.
-keep class androidx.work.** { *; }
-keep class androidx.room.** { *; }
-keep class * extends androidx.room.RoomDatabase { *; }
-keep class androidx.startup.** { *; }

# EGL images of HardwareBuffers.
-keep class androidx.graphics.** { *; }

-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,SourceFile,LineNumberTable
-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }

# Optional classes the libraries mention but Android or the app never loads.
-dontwarn com.google.auto.value.**
-dontwarn javax.annotation.**
-dontwarn org.checkerframework.**
-dontwarn com.google.errorprone.annotations.**
# MediaPipe's image embedder on LiteRT-LM and its graph profiler protos: not in its AAR, never used by Pocket XR.
-dontwarn com.google.ai.edge.litertlm.**
-dontwarn com.google.mediapipe.proto.**
# PC screens: libmoonlight-core calls MoonBridge's static methods and reads its fields by name from C.
-keep class com.limelight.nvstream.jni.** { *; }
-keep class com.limelight.nvstream.av.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
