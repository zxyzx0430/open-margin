<?php
/** Wallos extension transport to the existing personal ledger.
 * The secret is server-side only and cannot be read via HTTP.
 */
function ledger_escape($value): string {
    return htmlspecialchars((string)$value, ENT_QUOTES | ENT_SUBSTITUTE, 'UTF-8');
}

function ledger_request(string $method, string $path, ?array $body = null): array {
    if (!preg_match('#^(month\?month=\d{4}-\d{2}(?:&(day|category|direction|q|offset)=[A-Za-z0-9._%~-]*){0,5}|manual|review/[0-9]+|budget/\d{4}-\d{2})$#', $path)) {
        throw new RuntimeException('Invalid ledger route');
    }
    $secretPath = '/run/secrets/wallos-ledger.env';
    $settings = @parse_ini_file($secretPath, false, INI_SCANNER_RAW);
    $token = $settings['FINANCE_WALLOS_LEDGER_TOKEN'] ?? '';
    if (!is_string($token) || strlen($token) < 30) {
        throw new RuntimeException('The finance link is not configured');
    }
    $endpoint = $settings['FINANCE_LEDGER_API_URL'] ?? '';
    if (!is_string($endpoint) || !preg_match('#^https?://[^/\s]+/api/v1/wallos-ledger/?$#', $endpoint)) {
        throw new RuntimeException('FINANCE_LEDGER_API_URL is missing or invalid');
    }
    $url=rtrim($endpoint,'/').'/'.$path;
    $ch=curl_init($url);
    curl_setopt_array($ch, [
        CURLOPT_RETURNTRANSFER=>true,
        CURLOPT_CONNECTTIMEOUT=>3,
        CURLOPT_TIMEOUT=>10,
        CURLOPT_HTTPHEADER=>[
            'X-Wallos-Ledger-Token: '.$token,
            'Content-Type: application/json',
            'Accept: application/json',
        ],
        CURLOPT_SSL_VERIFYPEER=>true,
        CURLOPT_SSL_VERIFYHOST=>2,
        CURLOPT_FOLLOWLOCATION=>false,
        CURLOPT_CUSTOMREQUEST=>$method,
    ]);
    if ($body !== null) {
        curl_setopt($ch,CURLOPT_POSTFIELDS,json_encode($body,JSON_UNESCAPED_UNICODE|JSON_THROW_ON_ERROR));
    }
    $raw=curl_exec($ch);
    $http=curl_getinfo($ch,CURLINFO_RESPONSE_CODE);
    $error=curl_error($ch);
    curl_close($ch);
    if ($raw===false || $http!==200) {
        error_log('[Wallos daily-ledger] HTTP '.$http.' '.substr($error,0,100));
        throw new RuntimeException('Ledger request failed ('.$http.')');
    }
    $data=json_decode($raw,true);
    if (!is_array($data)) {
        throw new RuntimeException('Ledger response was invalid');
    }
    return $data;
}
function ledger_require_owner(): void {
    $settings = @parse_ini_file('/run/secrets/wallos-ledger.env', false, INI_SCANNER_RAW);
    $expected = (int)($settings['FINANCE_WALLOS_OWNER_ID'] ?? 1);
    if ($expected < 1 || !isset($_SESSION['userId']) || (int)$_SESSION['userId'] !== $expected) {
        // Drop any buffered Wallos navigation markup before denying access.
        if (ob_get_level() > 0) ob_end_clean();
        http_response_code(403);
        exit('The daily ledger is restricted to the configured owner.');
    }
}

function ledger_money(int $cents, string $currency): string {
    $code=$currency === 'CNY' ? '¥' : ($currency === 'USD' ? '$' : ($currency === 'HKD' ? 'HK$' : $currency.' '));
    return $code.number_format($cents/100,2,'.',',');
}
