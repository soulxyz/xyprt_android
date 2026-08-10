# 错题小印

面向 BY-288 / 错题小印 X1 的离线 Android 热敏打印应用。

作者：**Soulxyz**

本项目基于开源项目 [LaBLEr](https://github.com/toolicious/labler) 继续开发，并遵循 GPL-3.0-or-later 许可。

## 主要功能

- Bluetooth Classic SPP 直连 BY-288
- 文字、图片、PDF 快速打印
- 从微信、图库、文件管理器等应用分享/打开后直接打印
- 自由排版：文字、图片、二维码、条码、边框、符号
- 图片处理：线稿、黑白阈值、照片细腻、照片清晰、对比度、反色、旋转、缩放
- PDF 高清渲染、自动去白边、打印效果调整
- 文档模板、收藏、历史重打、备份恢复
- 完全离线，不申请 INTERNET 权限

## 构建

推荐使用随项目验证过的 Android 离线环境：Gradle 8.11.1、AGP 8.9.2、Kotlin 2.0.20、JDK 17、SDK 36。

由于当前离线仓库未包含完整 ZXing runtime，本项目编译阶段使用 stubs；最终打包脚本会从官方 LaBLEr 1.1.0 APK 中提取其已包含的开源 ZXing classes 并注入 APK。

`signing/by288-test.jks` 仅用于连续测试安装，禁止用于正式商店发布。
