# Android 应用模块

`app` 是 Ciallo 阅读的 Android 应用模块，负责小说与漫画书库、在线书源、本地导入、下载、阅读器、阅读记录和设置。入口为 `src/main/java/com/example/MainActivity.kt`；Compose 页面通过 ViewModel 与 repository 协作，Room 保存书籍、进度、书签和统计数据。

## 技术与数据流

```text
Compose UI → ViewModel → Repository / Source
                       ↘ Room / WorkManager
在线源：OkHttp / Cronet → Jsoup / QuickJS
漫画图像：页面调度 → 解码与缓存 → 阅读、增强或翻译
```

- Kotlin、Jetpack Compose Material 3、Navigation Compose、Coroutines / StateFlow
- Room / KSP 保存书库与阅读数据；WorkManager 承载长时间下载任务
- OkHttp、Cronet、Jsoup 与 QuickJS 为原生书源和 Venera JavaScript 源提供网络及解析能力
- Coil 负责封面与网络图片；ONNX Runtime 提供按需下载的漫画 OCR 模型
- Gradle 以 arm64-v8a 为默认 Release ABI；模型文件不随 APK 打包

## 主要源码包

| 目录 | 职责 |
| --- | --- |
| `src/main/java/com/example/data/` | Room、书籍导入解析、备份、设置、阅读统计与图片 token |
| `src/main/java/com/example/download/` | 小说下载、续传策略、任务状态与归档处理 |
| `src/main/java/com/example/library/` | 书库聚合搜索、小说/漫画详情、在线阅读接线及漫画下载 |
| `src/main/java/com/example/source/` | 书源接口、Legado/JSON/Venera 适配、Z-Library 与 AniList |
| `src/main/java/com/example/ui/` | Compose 页面、读者、缓存管理、书源管理及复用组件 |
| `src/main/java/com/example/god/` | 漫画神回数据、编辑和排行榜展示 |
| `src/main/java/com/example/mangatranslate/` | OCR、气泡检测、翻译、模型和页面内存预算 |
| `src/main/java/fi/harism/curl/` | 仿真卷页使用的 GL 页面渲染器（vendored Java） |
| `src/main/assets/` | Venera JS 源、JS 安全插桩、动画与源运行时数据 |
| `src/main/res/` | Android 图标、字体、主题、启动画面和资源配置 |
| `src/test/`、`src/androidTest/` | JVM/Robolectric 单元回归与 Android 设备测试 |
| `schemas/` | Room 导出的 schema 历史，用于数据库迁移和升级检查 |

各源码目录下的 `README.md` 说明该目录更细的内容。Android `assets/` 和 `res/` 子目录由构建工具直接扫描，因此它们的目录说明集中放在 `src/main/README.md` 与 `src/main/res/README.md`，避免文档被打进 APK 或让资源合并失败。完整功能与用户操作见仓库根目录 [README](../README.md)；逐源码设计与历史工作记录保存在仓库外的 `PROJECT_GUIDE.md`（本地开发工作区文件，不随仓库发布）。

## Release 构建

需要 JDK 17+ 和 Android SDK（compileSdk 35）：

```bash
./gradlew :app:assembleRelease
```

APK 输出在 `app/build/outputs/apk/release/`。本地没有配置发布 keystore 时，Gradle 会按工程设置使用 `debug.keystore`；向应用商店发布前应配置组织自己的 Release 签名密钥。
