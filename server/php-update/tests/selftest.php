<?php

declare(strict_types=1);
require dirname(__DIR__) . '/src/UpdateGateway.php';

$tests = [
    ['1.2.3', 1020300],
    ['v2.0.0', 2000000],
    ['1.2.3-beta1', 1020300],
];
foreach ($tests as [$input, $expected]) {
    $actual = XyprtUpdateGateway::semanticVersionCode($input);
    if ($actual !== $expected) {
        fwrite(STDERR, "FAIL semanticVersionCode($input): $actual != $expected\n");
        exit(1);
    }
}
$fixture = json_decode((string) file_get_contents(__DIR__ . '/fixtures/release.json'), true);
if (!is_array($fixture) || ($fixture['tag_name'] ?? '') !== 'v1.2.3') {
    fwrite(STDERR, "FAIL fixture\n");
    exit(1);
}
echo "PHP gateway selftest: OK\n";
