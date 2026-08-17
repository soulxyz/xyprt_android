# XYDLTA1 APK 增量更新

`gen_xy_delta.py` 从“旧的正式签名 APK”和“新的正式签名 APK”生成 `.xydelta`；`apply_xy_delta.py` 用旧 APK + patch 重建目标 APK。

设计原则：

1. patch 只是传输优化，不是新的信任根。
2. 输入必须是最终 zipalign + 签名后的 APK，以目标 APK 的 SHA-256 为最终标准。
3. 客户端重建后必须逐字节得到服务器正式 APK；SHA 不一致立即废弃并下载 full APK。
4. 生成结果是确定性的：同一对旧/新 APK 无论输出文件名是什么，patch 字节和 SHA-256 都一致。
5. 当 patch 大于完整 APK 的约 70% 时，客户端不使用增量。
6. native runtime / 大资源不变化时收益最大；升级 OpenCV/ONNX Runtime 时可能自动退回 full APK。

实测：

- 1020300 → 1020301：29,670,793 bytes 的目标 APK，对应 patch 4,388,177 bytes（14.79%）。
- 1020301 → 1020302：29,670,793 bytes 的目标 APK，对应 patch 3,653,695 bytes（12.31%）。
