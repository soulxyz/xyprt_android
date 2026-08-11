# 错题小印

面向 **BY-288 / 错题小印 X1** 的 Android 打印应用。

支持文字、图片、PDF、拍照裁剪和自由排版，也可以从其他 Android 应用直接分享内容打印。

- 项目主页：https://github.com/soulxyz/xyprt/
- Android 包名：`io.github.soulxyz.xyprt`
- 当前版本：`1.1.5`
- 作者：**Soulxyz**

## 主要功能

- Bluetooth Classic SPP 连接 BY-288
- 图片、PDF、文字快速打印
- 拍照后裁剪需要的区域再打印
- 自由排版：文字、图片、表格、二维码、条码、边框和符号
- 线稿、黑白、细腻、清晰等打印效果
- 自动长度、PDF 去白边、旋转和缩放
- 最近打印、历史重打、文档和备份恢复
- 支持从其他 Android 应用分享或打开内容

## 构建

建议环境：

- JDK 17
- Android SDK 36
- Gradle 8.11.1
- Android Gradle Plugin 8.9.2
- Kotlin 2.0.20

```bash
./gradlew assembleDebug
```

本地离线依赖、测试签名、历史 APK 等构建资产不纳入 Git。

## 版本历史

见 [CHANGELOG.md](CHANGELOG.md)。仓库为主要阶段保留了对应 Git tag。

## 上游

本项目基于 **[LaBLEr](https://github.com/toolicious/labler)** 继续开发。感谢上游作者 **[toolicious](https://github.com/toolicious)** 与所有贡献者。

为便于审阅修改，Git 历史中保留了 `upstream-labler-1.1.0` 上游基线。更多说明见 [UPSTREAM.md](UPSTREAM.md)。

## 许可证

本项目继承上游许可，按照 **GPL-3.0-or-later** 发布。详见 [LICENSE](LICENSE)。第三方组件说明见 [THIRD_PARTY_NOTICES_BY288.md](THIRD_PARTY_NOTICES_BY288.md)。
