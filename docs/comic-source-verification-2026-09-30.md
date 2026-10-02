# 漫画源实测与加载策略验证（更新于 2026-10-01）

## 验收范围与结果

应用提供的 19 个漫画源均已实际测试；累计 **19 个通过搜索 → 目录 → 章节图片 → 首图正常显示 → 整章下载 → 本地书架 → 离线阅读**，合计验证 **1,800 页**。Picacg、Vomic 使用正常账号登录；MYComic、NHentai 的最新通过记录均未预先进行手动站点验证。

使用 Android 35 测试模拟器，正常应用 JS 引擎、实际 ComicDownloadManager/Worker 与 Room 数据库。关闭画质增强。下载完成后核对整章页数、每页文件尺寸，并用本地阅读器解码首、中、末页，验证不是全黑。此前 ARM 转译测试已通过；可见验证页面测试另用带 x86 库的调试包，正式手机包仍为 ARM64。

下表是各源已取得的有效全链路证据；跨轮次汇总，部分下载会续传或命中缓存，不能把耗时作为冷网络速度基准。一次章节通过不保证所有作品及网络环境永久可用。

| 源 | 整章页数 | 全链路结果 |
|---|---:|---|
| 拷贝漫画 | 29 | 通过 |
| 紳士漫畫（WNACG） | 26 | 通过 |
| Picacg | 352 | 正常登录后通过 |
| 禁漫天堂 | 22 | 通过 |
| EHentai | 10 | 通过 |
| Hitomi | 6 | 通过 |
| 漫蛙吧 | 26 | 通过 |
| Comick | 46 | 通过 |
| GoDa | 36 | 通过 |
| 漫画人 | 46 | 通过 |
| H-Comic | 6 | 通过 |
| 漫小肆 | 397 | 通过 |
| 热辣漫画 | 193 | 通过 |
| 嗶哩漫畫 | 22 | 通过 |
| Vomic | 30 | 正常登录后通过，未强制测试路由 |
| MangaDex | 19 | 通过 |
| 爱看漫 | 480 | 通过，采用官方当前入口和读图接口 |
| MYComic | 28 | 清除本站会话后直接通过，无手动验证 |
| NHentai | 26 | 匿名直接通过，无手动验证 |

有效证据汇总：`.source-verification/passed-source-evidence.json`，原始记录来自 `final-all-results-33.jsonl`、`ikm-entry-results34.jsonl`、`jp-ikm36.jsonl`、`mycomic-fresh-foreground-results.jsonl`、`nhentai-complete-original-route-results.jsonl`。不同轮次的瞬时连接断开/超时另有保留，未删去失败结果。当前原生回归记录为 `history-native-regression-final.log`，Cookie 修复的先前原生记录为 `cookie-expiry-regression36.log`。

## 本轮修复

- 修复 JS 启动时将 Symbol/全局对象转换成字符串的回归；普通 Cookie 对象通过 JSON 进入 JS，保留值中的等号和父域 Cookie。结构化 POST 请求体实际传送 UTF-8 JSON。
- Cookie 现在处理服务器的过期、Max-Age=0 删除、域、路径和 Secure 属性。旧爱看漫 `ss_search_delay` 限流 Cookie 原本只有 1 秒寿命，旧桥却永久保存；迁移时仅清除此限流标记，保留旧登录会话。并发 Cookie 更新使用共同锁。
- Comick 全量目录分页每批最多 4 个请求，保留顺序和全部条目；失败显式报错。成功目录短期缓存并合并同源并发请求；登录/退出会使目录及图片配置失效。
- 漫小肆保留站点返回的实际图床域名；WNACG 域名发现排除发布页；嗶哩漫畫每次搜索重新兑换一次性凭证，静态防护资源短期复用。
- Vomic 正常登录同时恢复 API Bearer 与网页 Cookie，兼容章节标识。Picacg 和 Vomic 的有效账号只用于测试设备私有存储，临时凭据文件读取后立即删除；源码、测试资源、报告和 APK 不包含账号密码。
- 爱看漫参考维护中的 Keiyoushi 适配器，默认入口迁移到 `ymcdnyfqdapp.ikmmh.com`，保留用户自定义域名。沿用官方搜索、目录及 `/api/comic/read/pics`；先建立 PHP 会话，按每批 10 页获取完整图片列表。每组最多 2 个请求、间隔至少 500 ms，控制请求峰值；严格核对全章页数，不截断目录或图片。480 页列表在最终原生轮耗时约 28 秒；首次下载测试达到约 97% 后超时，之后正常续传并通过全部 480 页的文件及离线验证。
- 显式系统代理存在时优先用于图片下载，复用连接和已成功路由；失败路由失效，回退请求跟随原请求取消。下载保留实际请求头，允许 HTTP/2；续传只扫描目录一次，按页校验已有文件，避免逐页重复扫描。
- MYComic 普通请求恢复源脚本原本的桌面 User-Agent 与 Client Hints；只有已完成的内部 WebView 验证会话才保存对应标识。书源管理移除 MYComic/NHentai 新增的验证按钮，正常搜索、阅读、下载不要求用户点击验证；后台自动处理可完成的站点挑战。
- NHentai 已配置的可选 API Key 也会进入搜索、列表和配置接口的请求头；此前只有部分鉴权调用使用它。没有 API Key 时保持匿名请求。

当前源兼容补丁版本为 36，原始脚本缓存格式 V28 保持兼容。

## 历史对照与在线复测

用户 GitHub 发布的 `v1.1.0` 对应提交 `243d394d66ca4e71c8a6bc606454ec0f2241d9bd`，当前 GitHub main 为 `322657bc9bfcfdd2eb2ff64d371ac2b26b7d624c`；这两版的 JS 引擎、请求桥及 JS 源适配器没有差异。已定位并修复本次公共 JS 启动时的全局对象/Symbol 转换回归，以及正则拦截把 MYComic 的合法 JavaScript 字符类交给 Java 正则解析造成的章节失败。首轮 MYComic 日志本来就有成功搜索结果，不能把它此前的章节错误归因于站点验证。

NHentai 当前原始脚本与上游 2026-07-26 的 `39b82d9` 完全一致（SHA-256 `0f68757f89d493926cbf4c039c256d34365d0639aaa6996acbcde7c162257982`）；MYComic 原始脚本与其 2026-07-26 初次引入的 `ac76299` 完全一致。NHentai 旧 1.0.6 使用网页解析，API v2 在上游 2026-04-01 已引入，不是本轮新增。历史比较记录为 `source-history-comparison.json`。

把发布版 1.1.0 的原版引擎、请求桥、代理辅助类及运行时复制到临时隔离测试夹具，与当前引擎在同一 Android 设备/系统代理上对照，二者均匿名搜索成功，均得到 25 个结果，各约 716 ms。记录为 `nhentai-baseline-comparison.jsonl`；临时夹具已删除，不纳入正式源码或安装包。

爱看漫对移动 UA、HTTP/1.1、会话和请求速率的要求来自[Keiyoushi 当前适配器](https://github.com/keiyoushi/extensions-source/blob/main/src/zh/ikmmh/src/eu/kanade/tachiyomi/extension/zh/ikmmh/Ikmmh.kt)，入口来自同目录的 build.gradle.kts。测试线路结果存在差异：部分出口明确返回 “The region has been denied”，另一些可完成整个章节；未修改用户电脑的 VPN 全局节点或规则。

NHentai 已核对 [NClientV3 官方 API v2 迁移](https://github.com/maxwai/NClientV3/pull/158)与[维护中的 NHentai 适配器](https://github.com/yuzono/cursed-manga-extensions/blob/master/src/all/nhentai/src/eu/kanade/tachiyomi/extension/all/nhentai/NHentai.kt)。此前多个线路 API 返回 522、浏览器显示源站连接超时，失败证据保留；现在使用电脑已有的 7897 代理复测成功，不能据此前观测推断该源永久不可用。本次正常源搜索约 960 ms，整章 26 页全部下载并加入本地书架，首/中/末页离线解码通过；下载约 8.74 秒，总流程约 12.64 秒，没有登录或手动验证。

MYComic 曾在部分轮次收到 403/验证页面，旧验证后的通过记录仅代表条件通过。恢复旧请求头后，最新测试清除 JS Cookie、WebView 本站 Cookie 和已保存验证标识，正常搜索得到 4 本、目录 446 章、所选章节 28 页；首图正常，整章文件、本地书架和离线阅读全部通过，没有打开验证页面。下载可复用之前的本地文件，不能把这轮耗时视为冷加载速度。此前一次后台下载启动被 Android 前台服务限制拒绝，该失败也保留。

## 加载策略与回归

加载策略仍保留，和画质增强开关无关。当前页优先、邻页预取等待、缓存复用、真实字节进度、渐进 JPEG 预览、失败重试和手势修复均保留。

最新原生桥与阅读器回归 **12 项通过，2.996 秒**：首次/重复搜索、内置源初始化、动态配置/正则、取消及恢复、Cookie 对象与父域、真实 HTTP JSON POST、并发目录与登录失效、短期 Cookie 过期/服务器删除、Cookie 路径域及旧会话迁移、MYComic 默认请求头保留/已验证会话恢复、增强关闭时的慢网渐进预览。慢网测试实际分段发送图片，完整尾部到达前显示预览，完整下载后得到最终位图，再读命中缓存；全程只有一个 HTTP 请求。

轻量合成验证 **7 项通过**，记录 `host-static-regression.json`：脚本/桥语法；NHentai 可选 API Key 请求头；72 页 4,303 条完整目录和最大 4 并发；目录异常；一次性搜索凭证每次更新；Cookie JSON；480 页图片批次顺序、最大 2 并发和不完整批次拒绝。合成测试不能替代在线结果。

渐进预览依赖源图编码；普通 PNG/WebP、加密或重排图片仍需收齐必要数据。未在安卓真机上测量不同网络下的冷加载速度，也未验证真机 GL 卷页的最终合成画面。

## 构建

本轮增量调试构建正常完成，最近几轮耗时 28–53 秒；包含 x86 的调试包仅用于模拟器。最终正常 `assembleRelease` 构建成功，保留 R8 混淆和资源精简，耗时 **2 分 37 秒**。构建前关闭模拟器，临时历史对照夹具与之前的测试代理配置已清理；未修改电脑 VPN 的全局节点或规则。

最新手机安装包：`app/build/outputs/apk/release/app-release.apk`，**23,192,608 字节**，仅包含 ARM64 原生库。ZIP 完整性及 APK v2 签名校验通过；沿用项目当前签名（证书 SHA-256 `d2115e3cc5880b210a120558aaab03eb30ee5de2952a80f3376dda7e45501146`）。未包含测试源审计资源、历史测试夹具、测试凭据或私有代理配置。

APK SHA-256：`45e0427c892451bcbf46b2240cc2b48912e3f5a21c1e1b2a2f183df9e3811f33`。构建记录 `history-final-release.log`，元数据 `history-release-metadata.json`。

此前 NHentai 可见 WebView 诊断超时的记录保留，但最新验收走正常匿名 API 请求，没有手动验证。19/19 是跨轮次的完整章节验证汇总，不能视为所有作品、所有网络下永久可用或一轮冷缓存速度测试。
