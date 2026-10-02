# zlibrary

路径：`app/src/main/java/com/example/source/zlibrary/`。

## 用途

Z-Library 登录、节点、反挑战和专用请求流程。

## 内容
- `network/`
- `parser/`
- `DiamWallInterceptor.kt`
- `EncryptedCookieJar.kt`
- `EndpointHealthChecker.kt`
- `README.md`
- `RemoteEndpointConfig.kt`
- `RemoteEndpointProvider.kt`
- `ZLibraryAccessChecker.kt`
- `ZLibraryCredentialStorage.kt`
- 其余 6 项按名称和同层模块组织。

## 维护提示

随 APK 打包的生产代码和资源应通过 `:app:assembleRelease` 验证；测试 fixtures 放在对应测试源集。
