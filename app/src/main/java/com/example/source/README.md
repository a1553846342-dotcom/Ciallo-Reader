# source

路径：`app/src/main/java/com/example/source/`。

## 用途

书源接口、能力声明、小说和漫画源实现。

## 内容
- `anilist/`
- `impl/`
- `importer/`
- `js/`
- `parser/`
- `storage/`
- `zlibrary/`
- `AuthenticationState.kt`
- `BookFormat.kt`
- `BookSource.kt`
- 其余 18 项按名称和同层模块组织。

## 维护提示

随 APK 打包的生产代码和资源应通过 `:app:assembleRelease` 验证；测试 fixtures 放在对应测试源集。
