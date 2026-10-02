# data

路径：`app/src/main/java/com/example/data/`。

## 用途

Room 数据模型、导入解析、备份、偏好设置和阅读记录。

## 内容
- `favorite/`
- `remote/`
- `AppDatabase.kt`
- `BackupArchive.kt`
- `BackupManager.kt`
- `Book.kt`
- `BookRepository.kt`
- `ChapterMerger.kt`
- `CharsetSniffer.kt`
- `ComicParser.kt`
- 其余 16 项按名称和同层模块组织。

## 维护提示

随 APK 打包的生产代码和资源应通过 `:app:assembleRelease` 验证；测试 fixtures 放在对应测试源集。
