# 依赖与本地归档

## 日常交换

Git 源码包不携带 `.local-build/`。固定二进制依赖只在版本变化时交换一次，以 Deps Vault 保存。

当前 `.local-build` 结构：

```text
.local-build/
├── aar/
│   ├── opencv-4.13.0.aar
│   └── onnxruntime-android-1.24.1.aar
└── jars/
    ├── core-3.5.3.jar
    ├── kotlin-serialization-compiler-plugin.jar
    ├── kotlinx-serialization-json-jvm-1.6.2.jar
    └── zxing-compile-stubs.jar
```

恢复：

```bash
python3 tools/restore-local-build.py /path/to/PocketPrint_DepsVault.zip
python3 tools/verify-local-build.py
```

## 三类东西不要混在一起

- **Git Source**：项目源码、Git 历史、构建配方、版本锁、公开文档。
- **Deps Vault**：App 构建直接消费的固定 AAR/JAR，以及为关键自编依赖保留的源码/配方/校验材料。
- **Toolchain Vault**：NDK、Android SDK、CMake、Ninja、离线 Maven/Gradle 环境等大型工具链。

工具链不是每次交付内容。版本没变时只在本地长期备份，不重复交换。
