<?php

declare(strict_types=1);
require dirname(__DIR__, 2) . '/src/bootstrap.php';
require dirname(__DIR__, 2) . '/src/UpdateGateway.php';
xyprt_require_method('POST');
$config = xyprt_config();
$expected = (string) ($config['admin_token'] ?? '');
$provided = (string) ($_SERVER['HTTP_X_XYPRT_ADMIN_TOKEN'] ?? '');
if ($expected === '' || !hash_equals($expected, $provided)) {
    xyprt_json(['ok' => false, 'error' => 'unauthorized'], 401, ['Cache-Control: no-store']);
}
$gateway = new XyprtUpdateGateway($config);
$gateway->clearCache();
xyprt_json(['ok' => true, 'cleared' => true], 200, ['Cache-Control: no-store']);
