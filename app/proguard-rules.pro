# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# JSpecify annotations
-dontwarn org.jspecify.annotations.**
-keep class org.jspecify.annotations.** { *; }

# Kotlin serialization (if used in future)
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

# 崩溃堆栈可定位
# CrashHandler 会把完整堆栈写进 last_crash.log 并在调试日志页展示给用户用于反馈；
# 不保留这两个属性的话 release 包里收到的堆栈只有混淆名、没有行号，反馈链路等于失效。
# 每次发版记得归档 build/outputs/mapping/release/mapping.txt，否则仍然还原不回来。
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
