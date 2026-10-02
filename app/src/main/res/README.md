# res

路径：`app/src/main/res/`。

## 用途

Android 图标、字体、主题、启动画面和尺寸限定资源。

## 内容
- `drawable/`
- `drawable-nodpi/`
- `font/`
- `mipmap-anydpi-v26/`
- `mipmap-hdpi/`
- `mipmap-mdpi/`
- `mipmap-xhdpi/`
- `mipmap-xxhdpi/`
- `mipmap-xxxhdpi/`
- `raw/`
- `values/`
- `values-sw600dp/`
- `values-sw840dp/`
- `xml/`

## 维护提示

Android 资源工具会扫描下方每个资源 qualifier 目录，目录用途集中记录在此，不能在这些目录内放 Markdown 文件。随 APK 打包的生产代码和资源应通过 `:app:assembleRelease` 验证；测试 fixtures 放在对应测试源集。
