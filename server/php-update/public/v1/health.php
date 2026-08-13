<?php

declare(strict_types=1);
require dirname(__DIR__, 2) . '/src/bootstrap.php';
xyprt_require_method('GET');
xyprt_json([
    'ok' => true,
    'service' => 'xyprt-update-gateway',
    'version' => 1,
    'php' => PHP_VERSION,
    'curlMulti' => function_exists('curl_multi_init'),
    'time' => gmdate('c'),
], 200, ['Cache-Control: no-store']);
