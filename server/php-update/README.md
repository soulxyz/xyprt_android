# 口袋小印更新网关（PHP 8.0+）

目标：App 只访问 `https://api.xyprt.5am.top`；PHP 服务器只处理少量 JSON、健康探测和 302，不转发 APK 大文件。

## 部署

1. 服务器安装 PHP 8.0+，**推荐启用 ext-curl**。没有 ext-curl 也可运行，但多个上游会退化为串行请求。
2. 将本目录上传到服务器，例如 `/www/wwwroot/api.xyprt.5am.top/xyprt-update/`。
3. 将站点 DocumentRoot 指向 `server/php-update/public`。
4. 配置 HTTPS。App 正式版只使用 HTTPS。
5. 确保 PHP 对 `var/cache/` 有写权限。
6. 如需自定义 OSS / CDN 下载链接：复制 `config/config.local.php.example` 为 `config/config.local.php` 后修改。该文件不会进入 Git。

无需 URL Rewrite，客户端直接访问：

- `GET /v1/health.php`
- `GET /v1/update/latest.php`
- `GET /v1/update/download.php?tag=v1.2.3`

## 工作方式

### 更新元数据

缓存过期后的第一个请求会同时访问多个 GitHub API 候选源。每个响应必须满足：

- HTTPS；
- `github.com/soulxyz/xyprt_android` 的 Release 页面；
- 不是 draft / prerelease；
- APK 资产地址必须属于同一仓库、同一 tag；
- 版本号必须可解析。

有效结果中先选版本号最高，再优先选多个来源一致的结果。全部上游暂时不可用时，可在 7 天内退回上一次成功缓存。

### APK 下载

网关不会代理 APK 文件。`download.php` 只对多个镜像做 HEAD 探测，然后返回 `302 Location`。因此小带宽服务器几乎不承担 APK 流量。

你可以在 `config.local.php` 中给指定版本添加阿里云 OSS / CDN 地址，它会获得最高优先级。

## 安全

- 不接受客户端传入任意 URL，避免把服务做成开放反代 / SSRF 入口。
- 全部上游保持 TLS 证书校验。
- APK 最终仍受 Android App Signing 保护；镜像无法伪造能覆盖安装的正式 APK。
- `config.local.php`、缓存和签名资产都不进入 Git。
