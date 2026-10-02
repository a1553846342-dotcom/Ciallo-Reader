# example

路径：`app/src/main/java/com/example/`。

## 用途

应用业务代码包，按数据、书源、书库、下载、阅读 UI 与翻译职责组织。

## 内容
- `data/`
- `download/`
- `god/`
- `library/`
- `mangatranslate/`
- `source/`
- `ui/`
- `MainActivity.kt`
- `MainViewModel.kt`
- `README.md`

## 维护提示

随 APK 打包的生产代码和资源应通过 `:app:assembleRelease` 验证；测试 fixtures 放在对应测试源集。
