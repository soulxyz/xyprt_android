<?php

declare(strict_types=1);

function xyprt_config(): array
{
    static $config = null;
    if ($config !== null) {
        return $config;
    }
    $config = require dirname(__DIR__) . '/config/config.php';
    $local = dirname(__DIR__) . '/config/config.local.php';
    if (is_file($local)) {
        $override = require $local;
        if (is_array($override)) {
            $config = array_replace($config, $override);
        }
    }
    $config['public_base_url'] = rtrim((string) $config['public_base_url'], '/');
    return $config;
}

function xyprt_json(array $payload, int $status = 200, array $headers = []): never
{
    http_response_code($status);
    header('Content-Type: application/json; charset=utf-8');
    header('X-Content-Type-Options: nosniff');
    foreach ($headers as $header) {
        header($header);
    }
    echo json_encode($payload, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_PRETTY_PRINT);
    exit;
}

function xyprt_require_method(string $method): void
{
    if (strtoupper($_SERVER['REQUEST_METHOD'] ?? 'GET') !== strtoupper($method)) {
        header('Allow: ' . strtoupper($method));
        xyprt_json(['ok' => false, 'error' => 'method_not_allowed'], 405);
    }
}
