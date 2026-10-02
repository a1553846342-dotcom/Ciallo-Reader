# js

路径：`app/src/main/java/com/example/source/js/`。

## 用途

Venera JavaScript 源运行时、消息桥、DOM 和异步生命周期。

## 内容
- `CfWebViewSolver.kt`
- `JsComicMetadata.kt`
- `JsComicSource.kt`
- `JsCookieJar.kt`
- `JsHtmlStore.kt`
- `JsImageProcessor.kt`
- `JsMessageHandler.kt`
- `JsSourceEngine.kt`
- `JsSourceProxy.kt`
- `JsSourceRepo.kt`
- 其余 5 项按名称和同层模块组织。

## 维护提示

随 APK 打包的生产代码和资源应通过 `:app:assembleRelease` 验证；测试 fixtures 放在对应测试源集。
