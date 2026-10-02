# data

路径：`app/src/test/java/com/example/data/`。

## 用途

书籍导入、Room schema 迁移、章节、内嵌图片和阅读数据测试。

## 内容
- `favorite/`
- `AppDatabaseMigrationTest.kt`
- `BackendImportSafetyTest.kt`
- `ChapterBookmarkDataTest.kt`
- `ChapterBookmarkEndToEndTest.kt`
- `ImageBlockSplitTest.kt`
- `MultiLanguageSearchTest.kt`
- `NovelImageCacheTest.kt`
- `NovelInlineImagePipelineTest.kt`
- `NovelInlineImageRenderTest.kt`
- 其余 8 项按名称和同层模块组织。

## 维护提示

记录回归输入和边界；模拟器或单次网络样本不能泛化为所有设备和源站。
