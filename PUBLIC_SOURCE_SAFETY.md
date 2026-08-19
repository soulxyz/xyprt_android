# 公开源码安全边界

这个仓库只保存可公开的源码、协议、示例配置和恢复说明。生产签名、密码、私有模型、模型/资源解密密钥和服务器本地配置不属于源码仓库。

提交前运行：

```bash
python3 tools/audit-public-source.py
```

CI 也会扫描当前跟踪文件和全部 Git 历史，阻止常见私钥/访问令牌特征以及以下私有路径进入仓库：

- Android：`.local-build/`、`private-signing/`、`private-ml/`、keystore / p12 / pem / key 等。
- PHP：`config/config.local.php`、`config/model_keys.local.php`、`config/asset_keys.local.php`、`private-models/`、`.env` 等。

`.example` 文件只能放占位值，不能放真实凭据。如果真实密钥曾经被提交，单纯删除文件并不安全：必须先轮换密钥，再清理 Git 历史。

## Proprietary scan boundary

Public Git keeps the stable `EnhancedScanEngine` API and the OpenSource provider only. Model-specific runtime glue, tensor contracts, post-processing parameters and private models live under local `private-features/` and must never be tracked. `tools/audit-proprietary-boundary.py` is the dedicated guard. A clean public clone must build `assembleOpensourceRelease` without restoring Private Vault.
