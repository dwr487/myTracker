# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# Keep CameraX classes
-keep class androidx.camera.** { *; }
-keep interface androidx.camera.** { *; }

# Keep Location classes
-keep class com.google.android.gms.location.** { *; }

# Keep model classes
-keep class com.dashcam.multicam.model.** { *; }

# Keep Kotlin metadata
-keep class kotlin.Metadata { *; }

# Keep Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Keep service classes
-keep public class * extends android.app.Service

# Keep broadcast receivers
-keep public class * extends android.content.BroadcastReceiver
