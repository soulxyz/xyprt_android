# Android 可复现构建

本仓库的默认构建是 OpenSource 版本；ONNX 增强扫描仅在共创构建显式启用。

## 固定版本

- JDK: 17
- Gradle Wrapper: 8.11.1
- Android Gradle Plugin: 8.9.2
- Kotlin: 2.0.20
- compileSdk / targetSdk: 36
- Build Tools: 36.1.0
- minSdk: 26
- OpenCV: 4.13.0
- ONNX Runtime（共创版）: 1.24.1

## 第一次在新机器构建

1. 安装 JDK 17 与 Android SDK 36 / Build Tools 36.1.0。
2. 保留本 PRIVATE 完整包中的 `.local-build/`。它包含已验证的大型本地 AAR/JAR；普通 Git 不追踪该目录是为了避免 GitHub 100 MB 单文件限制。
3. 先验证本地二进制：

```bash
python3 tools/verify-local-build.py
```

4. OpenSource 调试构建：

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

Windows 使用：

```bat
gradlew.bat :app:assembleDebug :app:testDebugUnitTest
```

5. OpenSource Release：

```bash
./gradlew :app:assembleRelease :app:bundleRelease
```

6. 共创版（启用 ONNX）：

```bash
./gradlew -PXYPRT_INCLUDE_ONNX=true :app:assembleRelease :app:bundleRelease
```

7. ABI 定向包示例：

```bash
./gradlew -PXYPRT_ABIS=arm64-v8a :app:assembleRelease
```

## 完全离线构建

2026-08-17 的离线环境已经包含 JDK、SDK、Gradle、本地 Maven 仓库和 Build Tools。不要直接运行 `./gradlew --offline`：Gradle Wrapper 的 distribution 缓存不是完整恢复介质，AGP 还会尝试从 Maven 解析 aapt2。仓库已经把正确恢复动作封装为脚本：

Linux / macOS：

```bash
./tools/build-with-offline-env.sh /path/to/android_offline_build_environment_v1
```

Windows PowerShell：

```powershell
.\tools\build-with-offline-env.ps1 C:\path\to\android_offline_build_environment_v1
```

脚本会使用离线环境自带 Gradle 8.11.1，并显式复用 SDK `build-tools/36.1.0/aapt2`，同时设置 `ANDROID_OFFLINE_MAVEN_REPO`。不传任务时默认执行 Kotlin 编译和 JVM 单测。

顶层 `BUILD_ENVIRONMENT_SHA256SUMS.txt` 固化了 2026-08-17 验收用的六个离线环境分卷哈希。

当前验收用离线 Maven 镜像缺少 `com.android.tools.lint:lint-gradle:31.9.2`，因此**严格 Release lint**需要联网补齐该依赖，或补进离线 Maven 镜像。仅做受控的离线恢复 Release 时可以显式：

```bash
./tools/build-with-offline-env.sh /path/to/android_offline_build_environment_v1 \
  -PXYPRT_OFFLINE_SKIP_LINT=true :app:assembleRelease :app:bundleRelease
```

项目本身没有永久关闭 Release lint。

## 私密文件

`private-signing/`、`private-ml/` 不应推到公开 GitHub。PRIVATE 归档可以用于灾难恢复，但生产签名/密钥应另有加密备份。
