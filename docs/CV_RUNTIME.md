# PocketPrint CV Runtime

口袋小印的 Android 扫描基础能力使用冻结的 **PocketPrint Minimal OpenCV r1**，而不是把完整 OpenCV Android AAR 直接打进每个安装包。

## r1 能力边界

- OpenCV 4.13.0
- Android API 26
- ABI：`arm64-v8a`、`armeabi-v7a`
- OpenCV modules：`core`、`imgproc`、`java`
- arm64：启用 KleidiCV 0.7.0
- armeabi-v7a：不启用 KleidiCV（0.7.0 为 AArch64 路径）
- Java API 与 Android Bitmap bridge 保持 OpenCV 4.13.0 兼容

当前 AAR SHA-256：

```text
4d9cc797cb2bafb685dc2953aaf9ac9f983b9c7b98cf68fbc701d4490556ebf7
```

## 为什么不把完整 OpenCV 放进 Git

Git 仓库保存我们维护的源码、构建配方、版本锁和校验值。第三方大二进制与离线依赖进入单独的 Deps Vault / Toolchain Vault，避免 Git 历史长期膨胀。

正常 App 开发只消费冻结的 AAR，不需要 NDK，也不需要每次重新编 OpenCV。

## 什么时候需要 r2

只有以下情况才应重建 CV Runtime，而不是普通 App 发版时重建：

- OpenCV 版本变化；
- NDK / ABI / native 兼容要求变化；
- 新代码开始依赖 r1 以外的 OpenCV module；
- r1 的 ABI 或 JNI 能力不足。

新增 `core` / `imgproc` 内已有 API 通常不需要重建。合并 OpenCV 相关代码前运行：

```bash
python3 tools/verify-opencv-min-r1-usage.py app/src
```

如果出现超出 r1 闭包的 import，先评估是否能用现有能力实现；确实需要新 module 时再开新的 Runtime revision。

## 重建

可复现配方在：

```text
tools/build-opencv-minimal-r1.sh
```

重建需要精确输入：OpenCV 4.13.0、KleidiCV 0.7.0、NDK r29、CMake 3.31.12、Ninja 1.13.2、Android SDK，以及官方 OpenCV 4.13.0 AAR（仅复用 Java/Manifest 层）。这些大型输入不属于日常 Git 仓库，具体 SHA 和恢复方式随 Deps/Toolchain Vault 归档。
