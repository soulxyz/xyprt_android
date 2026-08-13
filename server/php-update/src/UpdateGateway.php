<?php

declare(strict_types=1);

final class XyprtUpdateGateway
{
    public function __construct(private array $config)
    {
        $dir = (string) $this->config['cache_dir'];
        if (!is_dir($dir)) {
            @mkdir($dir, 0775, true);
        }
    }

    public function latest(bool $force = false): array
    {
        $cache = $this->readCache('latest-release.json');
        $ttl = (int) $this->config['metadata_cache_ttl'];
        if (!$force && $cache !== null && (time() - (int) ($cache['_cachedAt'] ?? 0)) <= $ttl) {
            $cache['_cache'] = 'hit';
            return $cache;
        }

        $repo = (string) $this->config['repo'];
        $official = 'https://api.github.com/repos/' . $repo . '/releases/latest';
        $requests = [];
        foreach ($this->config['release_sources'] as $source) {
            $requests[] = [
                'name' => (string) $source['name'],
                'url' => str_replace('{url}', $official, (string) $source['template']),
                'priority' => (int) ($source['priority'] ?? 0),
            ];
        }

        $responses = $this->parallelFetch($requests, false, (int) $this->config['source_timeout_ms']);
        $valid = [];
        foreach ($responses as $response) {
            $normalized = $this->normalizeRelease($response);
            if ($normalized !== null) {
                $valid[] = $normalized;
            }
        }

        if ($valid === []) {
            if ($cache !== null && (time() - (int) ($cache['_cachedAt'] ?? 0)) <= (int) $this->config['stale_cache_max_age']) {
                $cache['_cache'] = 'stale';
                $cache['_warning'] = 'all_upstreams_failed';
                return $cache;
            }
            throw new RuntimeException('没有可用的 Release 元数据源');
        }

        // 先选版本号最高的一组；同版本再按“相同 release 身份的来源数量”做共识。
        $maxCode = max(array_column($valid, 'versionCode'));
        $newest = array_values(array_filter($valid, static fn(array $x): bool => $x['versionCode'] === $maxCode));
        $groups = [];
        foreach ($newest as $candidate) {
            $identity = hash('sha256', $candidate['tag'] . "\n" . ($candidate['assetUrl'] ?? '') . "\n" . ($candidate['sha256'] ?? ''));
            $groups[$identity][] = $candidate;
        }
        uasort($groups, static function (array $a, array $b): int {
            if (count($a) !== count($b)) return count($b) <=> count($a);
            $bestA = min(array_column($a, 'elapsedMs'));
            $bestB = min(array_column($b, 'elapsedMs'));
            return $bestA <=> $bestB;
        });
        $winningGroup = reset($groups);
        if (!is_array($winningGroup) || $winningGroup === []) {
            throw new RuntimeException('Release 共识计算失败');
        }
        usort($winningGroup, static function (array $a, array $b): int {
            if ($a['priority'] !== $b['priority']) return $b['priority'] <=> $a['priority'];
            return $a['elapsedMs'] <=> $b['elapsedMs'];
        });
        $winner = $winningGroup[0];

        $this->writeCache('latest-release-private.json', [
            'assetUrl' => $winner['assetUrl'],
            'tag' => $winner['tag'],
            'version' => $winner['version'],
            '_cachedAt' => time(),
        ]);

        $result = [
            'apiVersion' => 1,
            'repo' => $repo,
            'version' => $winner['version'],
            'versionCode' => $winner['versionCode'],
            'tag' => $winner['tag'],
            'title' => $winner['title'],
            'notes' => $winner['notes'],
            'releaseUrl' => $winner['releaseUrl'],
            'downloadUrl' => $winner['assetUrl'] !== null
                ? $this->config['public_base_url'] . '/v1/update/download.php?tag=' . rawurlencode($winner['tag'])
                : $winner['releaseUrl'],
            'assetName' => $winner['assetName'],
            'assetSize' => $winner['assetSize'],
            'sha256' => $winner['sha256'],
            'publishedAt' => $winner['publishedAt'],
            'checkedVia' => '口袋小印更新服务',
            'upstreamSource' => $winner['source'],
            'upstreamAgreement' => count($winningGroup),
            'validUpstreams' => count($valid),
            '_cachedAt' => time(),
            '_cache' => 'miss',
        ];
        $this->writeCache('latest-release.json', $result);
        return $result;
    }

    public function chooseDownload(array $release): array
    {
        $sourceUrl = (string) ($release['_assetUrl'] ?? '');
        if ($sourceUrl === '') {
            // cached public payload intentionally does not expose _assetUrl; reconstruct from private cache copy if needed
            $raw = $this->readCache('latest-release-private.json');
            $sourceUrl = (string) ($raw['assetUrl'] ?? '');
        }
        if ($sourceUrl === '') {
            // Refresh once; normalizeRelease stores private asset separately.
            $this->latest(true);
            $raw = $this->readCache('latest-release-private.json');
            $sourceUrl = (string) ($raw['assetUrl'] ?? '');
        }
        if ($sourceUrl === '') {
            throw new RuntimeException('最新 Release 没有 APK 资产');
        }

        $version = (string) $release['version'];
        $tag = (string) $release['tag'];
        $asset = (string) ($release['assetName'] ?? basename(parse_url($sourceUrl, PHP_URL_PATH) ?: 'xyprt.apk'));
        $candidates = [];

        foreach (($this->config['download_overrides'][$version] ?? []) as $item) {
            if (!is_array($item) || empty($item['url'])) continue;
            $candidates[] = [
                'name' => (string) ($item['name'] ?? 'override'),
                'url' => $this->expandDownloadTemplate((string) $item['url'], $sourceUrl, $version, $tag, $asset),
                'priority' => (int) ($item['priority'] ?? 1000),
            ];
        }
        foreach ($this->config['download_sources'] as $item) {
            $candidates[] = [
                'name' => (string) $item['name'],
                'url' => $this->expandDownloadTemplate((string) $item['template'], $sourceUrl, $version, $tag, $asset),
                'priority' => (int) ($item['priority'] ?? 0),
            ];
        }

        $dedup = [];
        foreach ($candidates as $item) {
            if (!$this->isHttpsUrl($item['url'])) continue;
            $dedup[$item['url']] = $item;
        }
        $candidates = array_values($dedup);
        if ($candidates === []) {
            throw new RuntimeException('没有可用的下载候选');
        }

        // 只发 HEAD，不搬运 APK。能确认在线的源优先；都探测失败时仍返回最高优先级候选。
        $probes = $this->parallelFetch($candidates, true, (int) $this->config['download_probe_timeout_ms']);
        $healthy = array_values(array_filter($probes, static fn(array $r): bool => $r['ok']));
        $pool = $healthy !== [] ? $healthy : $candidates;
        usort($pool, static function (array $a, array $b): int {
            $pa = (int) ($a['priority'] ?? 0);
            $pb = (int) ($b['priority'] ?? 0);
            if ($pa !== $pb) return $pb <=> $pa;
            return ((int) ($a['elapsedMs'] ?? PHP_INT_MAX)) <=> ((int) ($b['elapsedMs'] ?? PHP_INT_MAX));
        });
        return $pool[0];
    }

    public function clearCache(): void
    {
        foreach (['latest-release.json', 'latest-release-private.json'] as $name) {
            @unlink($this->cachePath($name));
        }
    }

    private function normalizeRelease(array $response): ?array
    {
        if (!$response['ok'] || !is_string($response['body'])) return null;
        $root = json_decode($response['body'], true);
        if (!is_array($root)) return null;
        if (($root['draft'] ?? false) === true || ($root['prerelease'] ?? false) === true) return null;

        $tag = isset($root['tag_name']) ? trim((string) $root['tag_name']) : '';
        $version = ltrim($tag, 'vV');
        $versionCode = self::semanticVersionCode($version);
        if ($tag === '' || $versionCode <= 0) return null;

        $repo = (string) $this->config['repo'];
        $releaseUrl = isset($root['html_url']) ? (string) $root['html_url'] : '';
        if (!$this->isExpectedGithubReleaseUrl($releaseUrl, $repo)) return null;

        $asset = null;
        foreach (($root['assets'] ?? []) as $candidate) {
            if (!is_array($candidate)) continue;
            $name = (string) ($candidate['name'] ?? '');
            if (str_ends_with(strtolower($name), '.apk')) {
                $asset = $candidate;
                break;
            }
        }
        $assetUrl = $asset ? (string) ($asset['browser_download_url'] ?? '') : null;
        if ($assetUrl !== null && $assetUrl !== '' && !$this->isExpectedGithubAssetUrl($assetUrl, $repo, $tag)) return null;
        if ($assetUrl === '') $assetUrl = null;

        $digest = $asset ? (string) ($asset['digest'] ?? '') : '';
        $sha256 = str_starts_with(strtolower($digest), 'sha256:') ? substr($digest, 7) : null;
        if ($sha256 !== null && !preg_match('/^[a-f0-9]{64}$/i', $sha256)) $sha256 = null;

        return [
            'version' => $version,
            'versionCode' => $versionCode,
            'tag' => $tag,
            'title' => trim((string) ($root['name'] ?? '')) ?: ('口袋小印 ' . $version),
            'notes' => $this->limitText(trim((string) ($root['body'] ?? '')), 12000),
            'releaseUrl' => $releaseUrl,
            'assetUrl' => $assetUrl,
            'assetName' => $asset ? (string) ($asset['name'] ?? '') : null,
            'assetSize' => $asset && isset($asset['size']) ? (int) $asset['size'] : null,
            'sha256' => $sha256,
            'publishedAt' => (string) ($root['published_at'] ?? ''),
            'source' => (string) $response['name'],
            'priority' => (int) ($response['priority'] ?? 0),
            'elapsedMs' => (int) $response['elapsedMs'],
        ];
    }

    private function parallelFetch(array $requests, bool $headOnly, int $timeoutMs): array
    {
        if (function_exists('curl_multi_init')) {
            return $this->parallelFetchCurl($requests, $headOnly, $timeoutMs);
        }
        // 极简虚拟主机若没装 ext-curl 仍可运行，只是退化成串行。
        $out = [];
        foreach ($requests as $request) {
            $out[] = $this->streamFetch($request, $headOnly, $timeoutMs);
        }
        return $out;
    }

    private function parallelFetchCurl(array $requests, bool $headOnly, int $timeoutMs): array
    {
        $mh = curl_multi_init();
        $handles = [];
        foreach ($requests as $i => $request) {
            $ch = curl_init((string) $request['url']);
            curl_setopt_array($ch, [
                CURLOPT_RETURNTRANSFER => true,
                CURLOPT_FOLLOWLOCATION => true,
                CURLOPT_MAXREDIRS => 5,
                CURLOPT_CONNECTTIMEOUT_MS => (int) $this->config['connect_timeout_ms'],
                CURLOPT_TIMEOUT_MS => $timeoutMs,
                CURLOPT_USERAGENT => 'xyprt-update-gateway/1.0',
                CURLOPT_HTTPHEADER => ['Accept: application/vnd.github+json, application/json'],
                CURLOPT_SSL_VERIFYPEER => true,
                CURLOPT_SSL_VERIFYHOST => 2,
                CURLOPT_NOBODY => $headOnly,
            ]);
            curl_multi_add_handle($mh, $ch);
            $handles[$i] = ['handle' => $ch, 'request' => $request, 'start' => microtime(true)];
        }

        do {
            $status = curl_multi_exec($mh, $active);
            if ($active) curl_multi_select($mh, 0.25);
        } while ($active && $status === CURLM_OK);

        $out = [];
        foreach ($handles as $item) {
            $ch = $item['handle'];
            $code = (int) curl_getinfo($ch, CURLINFO_RESPONSE_CODE);
            $error = curl_error($ch);
            $body = $headOnly ? '' : curl_multi_getcontent($ch);
            $out[] = array_merge($item['request'], [
                'ok' => $error === '' && $code >= 200 && $code < 400,
                'httpCode' => $code,
                'body' => $body,
                'error' => $error,
                'elapsedMs' => (int) round((microtime(true) - $item['start']) * 1000),
            ]);
            curl_multi_remove_handle($mh, $ch);
            curl_close($ch);
        }
        curl_multi_close($mh);
        return $out;
    }

    private function streamFetch(array $request, bool $headOnly, int $timeoutMs): array
    {
        $start = microtime(true);
        $method = $headOnly ? 'HEAD' : 'GET';
        $ctx = stream_context_create(['http' => [
            'method' => $method,
            'timeout' => max(1.0, $timeoutMs / 1000),
            'ignore_errors' => true,
            'follow_location' => 1,
            'max_redirects' => 5,
            'header' => "User-Agent: xyprt-update-gateway/1.0\r\nAccept: application/vnd.github+json, application/json\r\n",
        ]]);
        $body = @file_get_contents((string) $request['url'], false, $ctx);
        $headers = $http_response_header ?? [];
        $code = 0;
        foreach ($headers as $header) {
            if (preg_match('/^HTTP\/\S+\s+(\d{3})/', $header, $m)) $code = (int) $m[1];
        }
        return array_merge($request, [
            'ok' => $body !== false && $code >= 200 && $code < 400,
            'httpCode' => $code,
            'body' => $headOnly ? '' : ($body === false ? '' : $body),
            'error' => $body === false ? 'stream_fetch_failed' : '',
            'elapsedMs' => (int) round((microtime(true) - $start) * 1000),
        ]);
    }

    private function limitText(string $value, int $limit): string
    {
        if (function_exists('mb_substr')) return mb_substr($value, 0, $limit, 'UTF-8');
        return substr($value, 0, $limit);
    }

    private function expandDownloadTemplate(string $template, string $url, string $version, string $tag, string $asset): string
    {
        return strtr($template, [
            '{url}' => $url,
            '{version}' => $version,
            '{tag}' => $tag,
            '{asset}' => $asset,
        ]);
    }

    private function isExpectedGithubReleaseUrl(string $url, string $repo): bool
    {
        $parts = parse_url($url);
        if (!is_array($parts) || strtolower((string) ($parts['scheme'] ?? '')) !== 'https') return false;
        if (strtolower((string) ($parts['host'] ?? '')) !== 'github.com') return false;
        return str_starts_with((string) ($parts['path'] ?? ''), '/' . $repo . '/releases/tag/');
    }

    private function isExpectedGithubAssetUrl(string $url, string $repo, string $tag): bool
    {
        $parts = parse_url($url);
        if (!is_array($parts) || strtolower((string) ($parts['scheme'] ?? '')) !== 'https') return false;
        if (strtolower((string) ($parts['host'] ?? '')) !== 'github.com') return false;
        return str_starts_with((string) ($parts['path'] ?? ''), '/' . $repo . '/releases/download/' . rawurlencode($tag) . '/')
            || str_starts_with((string) ($parts['path'] ?? ''), '/' . $repo . '/releases/download/' . $tag . '/');
    }

    private function isHttpsUrl(string $url): bool
    {
        $parts = parse_url($url);
        return is_array($parts) && strtolower((string) ($parts['scheme'] ?? '')) === 'https' && !empty($parts['host']);
    }

    private function cachePath(string $name): string
    {
        return rtrim((string) $this->config['cache_dir'], DIRECTORY_SEPARATOR) . DIRECTORY_SEPARATOR . $name;
    }

    private function readCache(string $name): ?array
    {
        $path = $this->cachePath($name);
        if (!is_file($path)) return null;
        $decoded = json_decode((string) @file_get_contents($path), true);
        return is_array($decoded) ? $decoded : null;
    }

    private function writeCache(string $name, array $payload): void
    {
        $path = $this->cachePath($name);
        $tmp = $path . '.' . getmypid() . '.tmp';
        $json = json_encode($payload, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_PRETTY_PRINT);
        if ($json === false) return;
        if (@file_put_contents($tmp, $json, LOCK_EX) !== false) {
            @rename($tmp, $path);
        }
    }

    public static function semanticVersionCode(string $raw): int
    {
        $raw = ltrim(trim($raw), 'vV');
        $raw = explode('-', $raw, 2)[0];
        $parts = array_map('intval', array_pad(explode('.', $raw), 3, '0'));
        $major = max(0, min(99, $parts[0] ?? 0));
        $minor = max(0, min(99, $parts[1] ?? 0));
        $patch = max(0, min(99, $parts[2] ?? 0));
        return $major * 1000000 + $minor * 10000 + $patch * 100;
    }
}
