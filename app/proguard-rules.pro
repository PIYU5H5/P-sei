# Minimal Launcher R8 / ProGuard Optimization Rules

# Keep ViewBinding generated classes
-keepclassmembers class * implements androidx.viewbinding.ViewBinding {
    public static * inflate(...);
    public static * bind(...);
}

# Keep our Activities, Services, Application, and Receivers
-keep public class com.psei.launcher.** extends android.app.Activity
-keep public class com.psei.launcher.** extends android.app.Application
-keep public class com.psei.launcher.** extends android.app.Service
-keep public class com.psei.launcher.** extends android.content.BroadcastReceiver
-keep public class com.psei.launcher.service.LockAccessibilityService
-keep public class com.psei.launcher.service.LauncherAdminReceiver

# Keep data models used in serialization or state
-keepclassmembers class com.psei.launcher.data.AppModel {
    <fields>;
    <methods>;
}

# Strip all android.util.Log calls in release builds for maximum speed and zero information leakage
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
}

