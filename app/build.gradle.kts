// Gradle build config

plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.android")
  id("org.jetbrains.kotlin.plugin.compose")
  id("com.google.devtools.ksp")
  alias(libs.plugins.roborazzi)
}

// 本地调试密钥库：不在仓库提交，首次构建自动生成（保证 clone 后可直接编译）
val debugKeystoreFile = file("${rootDir}/debug.keystore")
if (!debugKeystoreFile.exists()) {
  val javaHome = System.getProperty("java.home")
  val keytoolName = if (System.getProperty("os.name").lowercase().contains("win")) "keytool.exe" else "keytool"
  val keytool = File(javaHome, "bin/$keytoolName")
  val pb = ProcessBuilder(
    keytool.absolutePath,
    "-genkeypair", "-v",
    "-keystore", debugKeystoreFile.absolutePath,
    "-alias", "androiddebugkey",
    "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000",
    "-storepass", "android", "-keypass", "android",
    "-dname", "CN=Android Debug,O=Android,C=US"
  )
  pb.redirectErrorStream(true)
  pb.start().waitFor()
}

android {
  namespace = "com.example"
  compileSdk = 35

  defaultConfig {
    applicationId = "com.aistudio.novelreader.kxmpzq"
    minSdk = 24
    targetSdk = 35
    versionCode = 201
    versionName = "1.2.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    // 极致瘦身：当前目标设备均为 arm64（华为/主流手机）；保留其它 ABI 会多出约 16MB 原生库
    ndk {
      // 2026-09-21 改动：原先只有 release 是纯 arm64，debug 永远额外打一份 x86_64
      // —— 这就是 debug 包 62MB、release 包 23MB 的全部落差（x86_64 的 onnxruntime
      // 原生库约 38MB）。
      // 现在统一：默认只打 arm64（真机全是 arm64）。需要在 x86_64 模拟器上跑
      // ONNX 漫画翻译时显式加 -PincludeX86，因为走 ARM 翻译层执行 onnxruntime
      // 会 SIGSEGV（第十五轮实测）。
      abiFilters += if (project.hasProperty("includeX86")) {
        listOf("arm64-v8a", "x86_64")
      } else {
        listOf("arm64-v8a")
      }
    }

    // 仅保留中英文资源，去掉无用的 locale 资源
    resConfigs("zh-rCN", "en")
  }

  signingConfigs {
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
      storeFile = file(keystorePath)
      storePassword = System.getenv("STORE_PASSWORD")
      keyAlias = "upload"
      keyPassword = System.getenv("KEY_PASSWORD")
    }
    create("debugConfig") {
      storeFile = file("${rootDir}/debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
  }

  buildTypes {
    release {
      isCrunchPngs = true
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      val keystoreFile = file("${rootDir}/my-upload-key.jks")
      signingConfig = if (keystoreFile.exists() && System.getenv("STORE_PASSWORD") != null) {
        signingConfigs.getByName("release")
      } else {
        signingConfigs.getByName("debugConfig")
      }
    }
    debug {
      signingConfig = signingConfigs.getByName("debugConfig")
      // 2026-09-21：debug 体积的主要来源已通过统一 arm64 解决（62MB → 41.8MB）。
      // 剩余与 release 的差距全部来自「不做混淆 / 资源精简」。
      // 需要更小体积时显式开启：./gradlew assembleDebug -PminifyDebug
      // （默认关闭 —— 混淆后断点与堆栈会难以阅读，日常调试得不偿失）
      isMinifyEnabled = project.hasProperty("minifyDebug")
    }
  }

  bundle {
    density {
      enableSplit = true
    }
    abi {
      enableSplit = true
    }
    language {
      enableSplit = true
    }
  }
  packaging {
    // 2026-09-21 瘦身：YOLO 气泡分割模型（4.0MB，压缩后约 2.3MB）不再打进 APK。
    // 实现方式：把文件从 app/src/main/assets/mt/ 移到仓库根的 models/ —— 它仍在 git 里
    // （jsDelivr CDN 需要），但不在任何 assets sourceSet 中，因此不会打进 APK。
    // （AGP 的 packaging {} 没有 assets 块，无法用 excludes 排除，故采用移目录方案。）
    // 运行时首次开启漫画翻译时与 det/rec 同批下载，源见 TranslateModelManager.bubbleModel。
    jniLibs {
      // ⚠️ 本值实测结论（2026-09-21）：**必须为 true**。
      //
      // true  = 原生库在 APK 内以**压缩**形式存储，安装时解压到文件系统
      //         （extractNativeLibs=true）→ APK 最小。实测 release 21.9MB。
      // false = 系统要直接从 APK 内 mmap 原生库，因此要求 .so **未压缩且按页对齐**
      //         （Android 官方文档对 extractNativeLibs=false 的硬性要求）。
      //         实测 release 直接涨到 42.2MB——libonnxruntime.so 未压缩 28.6MB 全计入。
      //
      // 中途曾按"false 才是压缩"的错误理解改成 false，实测体积翻倍后已改回。
      // 结论：想让 APK 小就用 true；false 换来的是安装后设备占用略小，代价是 APK 翻倍。
      useLegacyPackaging = true
    }
  }
  packagingOptions {
    jniLibs.useLegacyPackaging = true
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  kotlinOptions {
    jvmTarget = "11"
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
}

// P5 防回归：需要 Compose 可组合项编译指标（跳过率/可重启性）时用
// `gradlew assembleDebug -PcomposeMetrics=true`，输出到 build/compose-metrics 与
// build/compose-reports。平时不开，不影响正常构建速度。
if (project.findProperty("composeMetrics") == "true") {
  composeCompiler {
    metricsDestination.set(layout.buildDirectory.dir("compose-metrics").get().asFile)
    reportsDestination.set(layout.buildDirectory.dir("compose-reports").get().asFile)
  }
}

dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(project(":liquidglass-compose"))
  // 神回设置（排行榜风格 / 提示胶囊 / 陀螺仪视差）：小型 KV，与既有
  // SharedPreferences 体系并存，互不干扰。
  implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.core.splashscreen)
  implementation(libs.androidx.security.crypto)
  implementation(libs.androidx.work.runtime.ktx)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.navigation.compose)
  implementation("dev.chrisbanes.haze:haze:1.1.1")
    implementation(project(":backdrop"))
  // 第十一轮瘦身：flexible-bottomsheet-material3 引入后从未使用（全项目零 import），移除
  implementation("org.brotli:dec:0.1.2")
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  implementation(libs.coil.compose)
  implementation(libs.jsoup)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  // 第十一轮瘦身：moshi-kotlin（连带 kotlin-reflect）已移除——仅有的两个序列化点
  //（BackupManager / RemoteEndpointProvider）改为 org.json 手写，JSON 兼容不变
  implementation(libs.okhttp)
  // 漫画翻译（第十五轮）：ONNX Runtime 跑 PP-OCR 检测/识别（纯 CPU，arm64）
  // 2026-09-21 瘦身尝试记录：官方精简版 `onnxruntime-mobile` 在 Maven 上没有 1.28.0
  //（其版本号与 android 版不同步），无法直接替换，保持完整版。
  // 若将来要继续瘦身，这是唯一的「大块」入口：换 mobile 版并实测 PP-OCR + YOLO 分割。
  implementation("com.microsoft.onnxruntime:onnxruntime-android:1.28.0")
  // 第十七轮：ML Kit 移除（国内设备普遍无谷歌服务）——在线兜底改腾讯交互翻译（国内直连免费）
  implementation(libs.quickjs.kt)
  implementation(libs.cronet.api)
  implementation(libs.cronet.embedded)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  testImplementation(libs.roborazzi)
  testImplementation(libs.roborazzi.compose)
  testImplementation(libs.roborazzi.junit.rule)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  "ksp"(libs.androidx.room.compiler)
}



// Keep the actual Room schema under version control for subsequent migrations.
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
