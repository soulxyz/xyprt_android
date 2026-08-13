<?php

declare(strict_types=1);
require dirname(__DIR__, 3) . '/src/bootstrap.php';
require dirname(__DIR__, 3) . '/src/UpdateGateway.php';
xyprt_require_method('GET');
try {
    $gateway = new XyprtUpdateGateway(xyprt_config());
    $latest = $gateway->latest(false);
    // 私有缓存字段不对客户端暴露。
    unset($latest['_cachedAt']);
    xyprt_json(['ok' => true, 'latest' => $latest], 200, ['Cache-Control: public, max-age=30']);
} catch (Throwable $e) {
    xyprt_json(['ok' => false, 'error' => 'update_unavailable', 'message' => $e->getMessage()], 503, ['Cache-Control: no-store']);
}
