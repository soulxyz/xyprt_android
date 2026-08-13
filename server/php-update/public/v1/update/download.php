<?php

declare(strict_types=1);
require dirname(__DIR__, 3) . '/src/bootstrap.php';
require dirname(__DIR__, 3) . '/src/UpdateGateway.php';
xyprt_require_method('GET');
try {
    $gateway = new XyprtUpdateGateway(xyprt_config());
    $latest = $gateway->latest(false);
    $requestedTag = isset($_GET['tag']) ? trim((string) $_GET['tag']) : '';
    if ($requestedTag !== '' && $requestedTag !== (string) $latest['tag']) {
        $latest = $gateway->latest(true);
        if ($requestedTag !== (string) $latest['tag']) {
            xyprt_json(['ok' => false, 'error' => 'release_not_current'], 404, ['Cache-Control: no-store']);
        }
    }
    $target = $gateway->chooseDownload($latest);
    header('Cache-Control: no-store');
    header('X-Content-Type-Options: nosniff');
    header('X-XYPRT-Download-Source: ' . preg_replace('/[^A-Za-z0-9._ -]/', '', (string) $target['name']));
    header('Location: ' . $target['url'], true, 302);
    exit;
} catch (Throwable $e) {
    xyprt_json(['ok' => false, 'error' => 'download_unavailable', 'message' => $e->getMessage()], 503, ['Cache-Control: no-store']);
}
