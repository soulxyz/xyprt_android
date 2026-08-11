# 仓库说明

## 远程仓库

- `origin`：https://github.com/soulxyz/xyprt.git
- `upstream`：https://github.com/toolicious/labler.git

当前只配置远程地址，**不会自动推送**。

## 版本与提交

- 每个正式版本递增 `versionName` 和 `versionCode`。
- 主要版本使用 Git tag 标记。
- Commit 信息保持简短，只写用户能感知的主要变化。

## 本地构建资产

离线 Android 环境、签名文件、历史 APK 和本地依赖保存在仓库外的 `local-build/`，不纳入 Git。
