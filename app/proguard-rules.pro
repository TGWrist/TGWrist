# ==========================================
# 1. 全局通用与调试配置
# ==========================================
# 自动保留所有包含 JNI native 方法的类和方法名 (防崩溃核心)
-keepclasseswithmembers class * {
    native <methods>;
}

# 保留自定义 View 的构造函数 (防 XML 解析崩溃)
-keepclasseswithmembers class * {
    public <init>(android.content.Context, android.util.AttributeSet);
}
-keepclasseswithmembers class * {
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# 让代码里的 @Keep 注解生效
-keep @androidx.annotation.Keep public class *
-keepclassmembers class * {
    @androidx.annotation.Keep *;
}

# 保留崩溃日志里的源文件名和行号 (Release 找 Bug 必备)
-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable

# ==========================================
# 2. 移除控制台输出 (Log 优化)
# ==========================================
# 移除 System.out.println (涵盖了 Java 和 Kotlin 的 println)
# 注意：此规则需要 R8 开启 optimization 才能彻底生效
-assumenosideeffects class java.io.PrintStream {
    public void println(java.lang.Object);
    public void println(java.lang.String);
    public void print(java.lang.Object);
    public void print(java.lang.String);
    public java.io.PrintStream printf(...);
}

# ==========================================
# 3. Telegram 核心底层库 (TDLib)
# ==========================================
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

# ==========================================
# 4. 音视频与语音通话 (TGCalls & WebRTC 精确规则)
# ==========================================
# TGCalls 核心 JNI 交互类
-keep class org.thunderdog.challegram.** { *; }

# WebRTC 混淆规则
-keep class org.webrtc.** { *; }

# ==========================================
# 5. 第三方库 & 消除警告
# ==========================================
# 解决 OkHttp 及相关网络库在 R8 打包时的警告
-dontwarn javax.annotation.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase
-dontwarn org.codehaus.mojo.animal_sniffer.*
-dontwarn okhttp3.internal.platform.ConscryptPlatform
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
