# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# -------------------------------------------------------------------------
# 1. 移除 Android 系统 Log (Log.d, Log.e, etc.)
# -------------------------------------------------------------------------
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int i(...);
    public static int w(...);
    public static int d(...);
    public static int e(...);
    public static int println(...);
}

# -------------------------------------------------------------------------
# 2. 移除 System.out.println (涵盖了 Java 和 Kotlin 的 println)
# -------------------------------------------------------------------------
-assumenosideeffects class java.io.PrintStream {
    public void println(java.lang.Object);
    public void println(java.lang.String);
    public void print(java.lang.Object);
    public void print(java.lang.String);
    public java.io.PrintStream printf(...);
}

# -------------------------------------------------------------------------
# 3. 保留 TDLib 不混淆
# -------------------------------------------------------------------------
-keep class org.drinkless.tdlib.TdApi { *; }
-keep class org.drinkless.tdlib.TdApi$* { *; }
-keepclassmembers class org.drinkless.tdlib.TdApi { *; }
-keepclassmembers class org.drinkless.tdlib.Client$LogMessageHandler {
    *;
}
-keep,allowoptimization interface org.drinkless.tdlib.Client$LogMessageHandler
-keep class org.drinkless.tdlib.Client {
    native <methods>;
}
