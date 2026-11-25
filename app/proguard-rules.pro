-keepattributes *Annotation*, InnerClasses
-dontwarn kotlinx.coroutines.**
-dontwarn net.lingala.zip4j.**
-dontwarn com.github.junrar.**

# kotlinx-serialization: 保留生成的 serializer / Companion（v0.1.63 release 曾因缺类崩溃）
-dontnote kotlinx.serialization.AnnotationsKt
-keep class **$$serializer { *; }
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Mihon/Tachiyomi 扩展运行环境：扩展 APK 在运行时按 FQCN 继承这些类，类名/方法名/字段名都不能改
-keep class eu.kanade.tachiyomi.** { *; }
-keep class keiyoushi.** { *; }
-keep class uy.kohesive.injekt.** { *; }
-keep class ireader.core.** { *; }
-keepclassmembers class eu.kanade.tachiyomi.source.online.HttpSource { *; }

# 扩展 APK 是外部编译的，按原始类名引用 Kotlin/OkHttp/Jsoup/协程等公共库。
# release 混淆若把这些类改名（例如 kotlin.text.Regex -> u0），扩展一加载就 NoClassDefFoundError。
-keep class kotlin.** { *; }
-keep class kotlinx.** { *; }
-keep class okhttp3.** { *; }
-keep class okhttp3.zstd.** { *; }
# okhttp-zstd 5.x 运行时反射/按名调用 zstd-kmp 的压缩器，R8 会误裁导致 ClassNotFoundException: com.squareup.zstd.ZstdCompressor
-keep class com.squareup.zstd.** { *; }
-keep class okio.** { *; }
-keep class org.jsoup.** { *; }
-keep class org.json.** { *; }
-keep class rx.** { *; }
-keep class com.fleeksoft.** { *; }
-keep class io.ktor.** { *; }
-keep class androidx.preference.** { *; }
-dontwarn kotlin.**
-dontwarn kotlinx.**
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.jsoup.**
-dontwarn org.json.**
-dontwarn rx.**
-dontwarn com.fleeksoft.**
-dontwarn io.ktor.**

# 书源 Provider 反射/按 JSON 路由
-keep class com.yg.comic.source.** { *; }

# JNI: source_engine.so 按名字回调 Kotlin external 方法，必须保留
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class com.yg.comic.source.SourceEngine { *; }

# 超分 ncnn JNI：native 库按固定类名 Java_mihon_core_superresolution_* 回调，类名与方法名均不可改名/裁剪
-keep class mihon.core.superresolution.RealESRGANProcessor { *; }
-keep class mihon.core.superresolution.VulkanHelper { *; }
-dontwarn mihon.core.superresolution.**

# R8 missing classes from optional 7z/xz + slf4j bindings (suppress only, no runtime use)
-dontwarn org.slf4j.impl.StaticLoggerBinder
-dontwarn org.slf4j.impl.**
-dontwarn org.tukaani.xz.**
-dontwarn org.tukaani.xz.ARMOptions
-dontwarn org.tukaani.xz.ARMThumbOptions
-dontwarn org.tukaani.xz.DeltaOptions
-dontwarn org.tukaani.xz.FilterOptions
-dontwarn org.tukaani.xz.IA64Options
-dontwarn org.tukaani.xz.LZMA2InputStream
-dontwarn org.tukaani.xz.LZMA2Options
-dontwarn org.tukaani.xz.LZMAInputStream
-dontwarn org.tukaani.xz.PowerPCOptions
-dontwarn org.tukaani.xz.SPARCOptions
-dontwarn org.tukaani.xz.X86Options
