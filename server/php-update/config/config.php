<?php
/**
 * 错题小印更新网关默认配置（PHP 8.0+）
 *
 * 第三方 GitHub 镜像随时可能变化；这里的列表只是候选源。
 * 网关会并发探测、严格校验响应并自动丢弃不可用源。
 * 服务器私有覆盖项请写 config.local.php（已在 .gitignore 中忽略）。
 */
return [
    'repo' => 'soulxyz/xyprt_android',
    'public_base_url' => 'https://api.xyprt.5am.top',

    // 元数据很小，短缓存可以避免每个 App 启动都打一次上游。
    'metadata_cache_ttl' => 60,
    'stale_cache_max_age' => 7 * 24 * 3600,
    'source_timeout_ms' => 4200,
    'connect_timeout_ms' => 1600,
    'download_probe_timeout_ms' => 2200,
    'cache_dir' => dirname(__DIR__) . '/var/cache',

    // GitHub latest-release API 的候选入口。{url} = 官方 API 完整 URL。
    // 若某个公共镜像不支持 API，会自动被判定为无效，不影响其他源。
    'release_sources' => [
        ['name' => 'GitHub API',      'template' => '{url}',                                'priority' => 100],
        ['name' => 'ghfast.top',      'template' => 'https://ghfast.top/{url}',             'priority' => 90],
        ['name' => 'gh-proxy.com',    'template' => 'https://gh-proxy.com/{url}',           'priority' => 80],
        ['name' => 'ghproxy.net',     'template' => 'https://ghproxy.net/{url}',            'priority' => 70],
        ['name' => 'houlang mirror',  'template' => 'https://mirror.houlang.cloud/{url}',  'priority' => 60],
        ['name' => 'gh.ddlc.top',     'template' => 'https://gh.ddlc.top/{url}',             'priority' => 50],
    ],

    // APK 下载候选。网关只做 HEAD 探测和 302 跳转，不转发 APK 文件。
    'download_sources' => [
        ['name' => 'ghfast.top',      'template' => 'https://ghfast.top/{url}',             'priority' => 100],
        ['name' => 'gh-proxy.com',    'template' => 'https://gh-proxy.com/{url}',           'priority' => 90],
        ['name' => 'ghproxy.net',     'template' => 'https://ghproxy.net/{url}',            'priority' => 80],
        ['name' => 'houlang mirror',  'template' => 'https://mirror.houlang.cloud/{url}',  'priority' => 70],
        ['name' => 'gh.ddlc.top',     'template' => 'https://gh.ddlc.top/{url}',             'priority' => 60],
        ['name' => 'GitHub direct',   'template' => '{url}',                                'priority' => 10],
    ],

    // 你可以在 config.local.php 针对某个版本手工指定下载地址。
    // 手工地址优先级最高，适合阿里云 OSS / CDN / 自建对象存储。
    // 示例：
    // 'download_overrides' => [
    //     '1.2.3' => [
    //         ['name' => 'Aliyun OSS', 'url' => 'https://bucket.example.com/xyprt-1.2.3.apk', 'priority' => 1000],
    //     ],
    // ],
    'download_overrides' => [],

    // 可选：部署后填一个随机 token，用于手工清缓存。
    'admin_token' => '',
];
