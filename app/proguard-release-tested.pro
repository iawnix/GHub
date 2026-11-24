# Applied only when building a release APK for connectedReleaseAndroidTest (-PtestBuildType=release).
# Production release APKs do not include these broad instrumentation-bridge rules.

# Preserve the small tracing bridge so AndroidJUnitRunner can start after shrinking.
-keep class androidx.tracing.Trace { *; }

# Instrumentation and the app share these runtime APIs across separate APKs.
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }
-keep class dev.remotegpt.client.** { *; }
-keep class kotlinx.serialization.** { *; }
