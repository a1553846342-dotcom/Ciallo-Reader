# main

路径：`app/src/main/`。

## 用途

应用正式构建使用的 Kotlin/Java 代码、Android 资源和内置数据。

## 内容
- `assets/`：Venera 脚本、JS 兼容 / 安全处理、内置数据和动画资源；子目录用途见下表
- `java/`
- `res/`：图标、主题、字体、启动画面、布局限定及 XML 配置；子目录索引见 `res/README.md`
- `AndroidManifest.xml`
- `README.md`

| assets 子目录 | 用途 |
| --- | --- |
| `ambient/` | 阅读环境音与对应授权说明 |
| `js_extra/` | Venera 漫画源的站点适配脚本 |
| `js_safety/` | QuickJS 执行保护插桩及 Acorn 解析器 |
| `mt/` | 漫画翻译提示词与 OCR 字符表 |
| `venera/` | Venera 源运行时与漫画元数据规则 |

## 维护提示

Android 资源目录和 assets 会被 Gradle / Android 资源工具直接扫描，因此不在其子目录放 Markdown：否则会造成资源合并失败，或把说明文档打进 APK。目录说明集中在本文件和 `res/README.md`；其余源码子目录保留各自的 `README.md`。随 APK 打包的生产代码和资源应通过 `:app:assembleRelease` 验证；测试 fixtures 放在对应测试源集。
