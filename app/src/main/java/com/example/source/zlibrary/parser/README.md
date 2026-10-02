# parser

路径：`app/src/main/java/com/example/source/zlibrary/parser/`。

## 用途

JSONPath 与 Legado 规则表达式的受限解析。

## 内容
- `BookcardLayoutParser.kt`
- `CoverExtractor.kt`
- `DesktopLayoutParser.kt`
- `GenericFallbackParser.kt`
- `LegacyLayoutParser.kt`
- `MobileLayoutParser.kt`
- `README.md`
- `ZLibraryLayoutParser.kt`
- `ZLibraryParserManager.kt`

## 维护提示

随 APK 打包的生产代码和资源应通过 `:app:assembleRelease` 验证；测试 fixtures 放在对应测试源集。
