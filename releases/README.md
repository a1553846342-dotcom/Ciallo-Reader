# releases

路径：`releases/`。

## 用途

本机生成的 Android APK 交付副本；版本与架构以包元数据为准。此目录不代表文件已发布到 GitHub Releases。

## 当前版本

| 文件 | 版本 | 架构 | 大小 | SHA-256 |
| --- | --- | --- | ---: | --- |
| [`Ciallo-Reader-v1.2.0.apk`](Ciallo-Reader-v1.2.0.apk) | 1.2.0 / 201 | arm64-v8a | 23,307,407 B | `b5721f43717f47c4a0397d9a9f7b4b8847648001965bcb7a3b2c4397acf6e64c` |

构建产物已通过 APK v2 签名校验和 ZIP CRC 校验；本次未配置发布 keystore，当前文件由仓库调试证书签名。正式分发时请使用与预期更新渠道一致的签名密钥重新构建。

## 内容
- `Ciallo-Reader-v1.2.0.apk` — 当前 Release 构建
- `app-release-pufei-1.0.2.apk`
- `app-release-pufei-1.0.3.apk`
- `app-release-pufei-1.0.4.apk`
- `README.md`
