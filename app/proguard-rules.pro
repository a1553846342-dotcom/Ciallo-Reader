# ProGuard / R8 Rules for Ciallo阅读

# Keep Room Database and Entities
-keep class * extends androidx.room.RoomDatabase
-keepclassmembers class * {
    @androidx.room.Dao *;
}
# 第十一轮瘦身：data 包为 Room 编译期生成代码 + 普通 Kotlin，无运行时反射，
# 整包 keep 会阻止 R8 移除未用代码；Moshi 反射只作用于下方显式保留的备份模型

# 第十一轮瘦身：Moshi/kotlin-reflect 已整体移除（序列化改 org.json），
# 相关 keep（Backup* 模型 / com.squareup.moshi）随之删除
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# Keep Kotlinx Serialization & Serializable Models
-keepclassmembers class * {
    @kotlinx.serialization.Serializable *;
}
-keepclassmembers class * {
    *** Companion;
}
-keep class kotlinx.serialization.** { *; }
# 第十一轮瘦身：source/download 包为普通 Kotlin（网络/解析/导入逻辑，无运行时反射；
# QuickJS 相关的 source.js 单独保留），整包 keep 阻止 R8 裁剪未用代码

# 第十一轮瘦身：coroutines / coil 自带 consumer 规则，R8 可正常裁剪，
# 整包 keep 只会保住大量未用 API（曾使 dex 膨胀 ~1MB）

# QuickJS JS 引擎（Venera 源）
-keep class com.dokar.quickjs.** { *; }
-keep class com.example.source.js.** { *; }
-keep class org.chromium.net.** { *; }

# DataStore（神回设置项：排行榜风格 / 提示胶囊 / 陀螺仪视差）
# 它是神回引入的新依赖。datastore-core 内部通过 okio + 协程做文件读写，
# 且 DataStore 单例 / Serializer 走的是成套的类引用；R8 若裁掉其中任一类，
# 首次访问 DataStore 时会 NoClassDefFoundError（表现为冷启动即崩）。
# 这几个包体积极小，整包 keep 对 APK 体积基本无影响。
-keep class androidx.datastore.** { *; }
-keep class okio.** { *; }
-dontwarn androidx.datastore.**
-dontwarn okio.**

# onnxruntime：native 层通过 JNI 按名/签名回调构造 Java 对象
# （NodeInfo/ValueInfo 等），混淆改名后 NoSuchMethodError 直接 SIGABRT。
# Java API 体积很小（大头是 27MB 的 .so），整包 keep 无瘦身代价。
-keep class ai.onnxruntime.** { *; }

# R8 Optimization & Obfuscation
-repackageclasses ''
-allowaccessmodification
-dontwarn **
