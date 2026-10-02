# JS 漫画源搜索初始化故障修复（2026-09-30）

用户反馈 MangaDex 能搜索，其余 JS 漫画源报 `JS 搜索失败: TypeError: cannot convert symbol to string`。

## 原因与修复

`JsSourceEngine.ensureReadyLocked()` 安装脚本执行预算的保护属性时，最后一条 `Object.defineProperty(globalThis, ...)` 返回了整个 JS 全局对象。QuickJS 的 JS → Kotlin 值转换器尝试转换这个对象，遇到 Symbol 即抛异常。错误发生在加载源脚本、发出站点请求之前，因此共同使用此引擎的源都受影响。MangaDex 不使用这个初始化路径。

在保护属性安装结束后增加 `void 0;`，让初始化不返回全局对象；仍安装原有的执行预算保护。增加 `runtime_guards.js` 文件名以便定位初始化堆栈。

## 验证

新增 `app/src/androidTest/java/com/example/source/js/JsSourceEngineCompatibilityTest.kt`，运行应用实际使用的 Android native QuickJS 库。

- 修复前，不访问网络的模拟源搜索也复现完全相同的 Symbol 错误，1 项测试失败。
- 修复后，Android 35 x86_64 模拟器上 3 项测试全部通过：模拟源首次及重复搜索、内置 bilimanga 初始化、内置 vomic 初始化。
- 内置源测试包含实际资产脚本、Venera 运行时、同步/异步 Kotlin 桥，以及应用相同的 `call("null")` 预初始化路径。
- 最终交付 ARM64 调试 APK 安装到同一 Android 35 模拟器后，包管理器确认 `primaryCpuAbi=arm64-v8a`，同一组 3 项测试再次全部通过（1.128 秒）。该模拟器通过 ARM 原生库兼容层运行，不是 ARM 真机测试。

交付文件为 `app/build/outputs/apk/debug/app-debug.apk`，45,048,045 字节，签名校验通过，仅含 ARM64 原生库。发布包 R8 压缩持续耗时较长，已取消该构建；本轮没有生成新的 release APK。最终调试包构建成功（23 秒），日志为 `.js-source-arm64-build.log`；最终原生测试记录为 `.js-source-arm64-tests.log`。

这些是公共 JS 引擎兼容性测试，不代替每个源站的实时搜索/登录/反爬验证。

构建模拟器测试包：

```powershell
./gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest -PincludeX86
```

安装两个 APK 后运行：

```powershell
adb shell am instrument -w -r -e class com.example.source.js.JsSourceEngineCompatibilityTest com.aistudio.novelreader.kxmpzq.test/androidx.test.runner.AndroidJUnitRunner
```
