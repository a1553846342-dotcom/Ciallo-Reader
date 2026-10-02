# comic

路径：`app/src/main/java/com/example/ui/comic/`。

## 用途

漫画阅读页面、图像加载、缩放手势和仿真翻页实现。

## 内容
- `Anime4KCnn.kt`
- `Anime4KCnnWeights.kt`
- `ComicChapterEdgeGesture.kt`
- `ComicHarismCurl.kt`
- `ComicImagePipeline.kt`
- `ComicLoadSupport.kt`
- `ComicPageLayout.kt`
- `ComicPageLoader.kt`
- `ComicPanelDesign.kt`
- `ComicProcessedDiskCache.kt`
- 其余 11 项按名称和同层模块组织。

## 维护提示

随 APK 打包的生产代码和资源应通过 `:app:assembleRelease` 验证；测试 fixtures 放在对应测试源集。
