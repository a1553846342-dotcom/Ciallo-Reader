# mangatranslate

路径：`app/src/main/java/com/example/mangatranslate/`。

## 用途

漫画 OCR、气泡检测、机器翻译及模型内存控制。

## 内容
- `BubbleDetector.kt`
- `BubblePipeline.kt`
- `LlmBubbleTranslator.kt`
- `MangaOcr.kt`
- `MangaTranslationCore.kt`
- `PageMemoryBudget.kt`
- `PageRegionDetector.kt`
- `PageRegionTiling.kt`
- `README.md`
- `TextBlockMerger.kt`
- 其余 3 项按名称和同层模块组织。

## 维护提示

随 APK 打包的生产代码和资源应通过 `:app:assembleRelease` 验证；测试 fixtures 放在对应测试源集。
