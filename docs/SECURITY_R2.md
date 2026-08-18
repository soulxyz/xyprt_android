# Security r2 客户端边界

Security r2 不替换现有 AES-256-GCM、Download Ticket、CDN、SHA-256、差分更新、资源缓存和离线打印；它只补齐受保护资源的**设备认证**与服务端授权边界。

## 双 Key 设备身份

`DeviceIdentity` 维护两类 Android Keystore Key：

- RSA encryption key：只接收服务器按设备包装的 Content Key；
- EC P-256 authentication key：只用于 `SHA256withECDSA` 的 challenge / request proof。

`installationId` 只是随机设备标识，不是密码，也不是 Sponsor Secret。

## DeviceAuth V1

受保护请求统一由 `ServerApi.signedGet/signedPost` 添加：

```text
X-Device-Id
X-Device-Time
X-Device-Nonce
X-Device-Key-Version
X-Device-Signature
```

请求 canonical form：

```text
XYPRT-DEVICE-AUTH-V1
<METHOD>
<path?query>
<unix timestamp seconds>
<nonce>
<SHA256(body)>
<installationId>
<keyVersion>
```

首次绑定和 Recovery 使用独立 challenge canonical form。协议实现集中在 `DeviceIdentity` / `ServerApi`，Repository 不自己发明签名算法。

## Authorization

客户端缓存的 `active / expiresAt / edition` 只用于 UI。真正的 Sponsor/共创 entitlement 永远由服务器判断。

修改 APK 把 `active=true` 或复制 `installationId/wrappedKey` 只能改变本地显示；没有原设备 EC 私钥无法签请求，没有原设备 RSA 私钥无法解该设备的 wrapped Content Key。

## Rotation / Recovery

- 正常 rotation：旧 EC 私钥签整个 rotation 请求，新 Key version 必须递增；服务器验证后客户端才 commit 新 Key。
- Keystore 丢失 / 重装：走独立 Recovery，重新输入 Sponsor code，并用 AndroidIdHash 等历史信号唯一匹配原激活记录；歧义进入人工处理，不做 installationId-only 换 Key。

## Public / Offline

公开 update-check、公开素材清单/下载、社区核心打印、已缓存资源和离线打印不要求 DeviceAuth 在线。普通社区用户不会仅因为 App 启动或检查公开更新就自动建立设备认证绑定；进入共创/增强能力或访问受保护内容时才按需建立 DeviceAuth。

受保护模型、Key envelope、Sponsor Release 等边界才要求设备签名。

DeviceAuth header 只发给配置的第一方 API origin；跟随 CDN 重定向时不会把设备签名泄漏给 CDN。

## App integrity

当前仅上报安装 APK 的当前签名证书 SHA-256 作为 Shadow risk signal；它不是授权条件，也不会导致 Root / 修改签名即闪退。真正授权仍是 DeviceAuth + server entitlement。

## 本地明文边界

模型/资源 AES Key 只在 unwrap 后短时间存在并在使用后 `fill(0)`。完整模型明文在本地推理前最终仍可能被合法 Root 设备 Hook，这是本地推理的现实边界；因此服务器 Content Key 应按模型版本或资源族隔离，避免单次泄露扩散为 Sponsor-wide master key 泄露。


## Release Overlay Hook

公开源码只提供中性的 `BUILD_CONTRACT_ID` 与可选 `XYPRT_RELEASE_OVERLAY_DIR` 构建入口；默认值为 `source` 且不影响独立公开构建。正式 Release 的随机 Canary、诱饵资源/模型/Endpoint 由 PRIVATE Release 工具生成，不能把真实生成规则或 token 提交到公开 Android Git。
