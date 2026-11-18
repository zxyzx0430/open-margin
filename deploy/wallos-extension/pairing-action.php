<?php
/** Explicit owner-only QR reveal. Never log or persist the device token. */
ob_start();
require_once 'includes/header.php';
require_once 'ledger-common.php';
ledger_require_owner();
ob_end_clean();

header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: private, no-store, max-age=0');
header('Pragma: no-cache');
header('Referrer-Policy: no-referrer');
header('X-Content-Type-Options: nosniff');

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    http_response_code(405);
    echo '{"ok":false,"message":"仅允许主动生成配对二维码"}';
    exit;
}
try {
    $raw = file_get_contents('php://input');
    if ($raw === false || strlen($raw) > 2048) {
        throw new InvalidArgumentException('Invalid request size');
    }
    $request = json_decode($raw, true, 8, JSON_THROW_ON_ERROR);
    $provided = is_array($request) ? ($request['csrf'] ?? '') : '';
    $expected = $_SESSION['finance_csrf'] ?? '';
    if (!is_string($provided) || !is_string($expected) || strlen($expected) < 32 ||
        !hash_equals($expected, $provided)) {
        http_response_code(403);
        echo '{"ok":false,"message":"身份验证已过期，请刷新页面"}';
        exit;
    }
    $payload = ledger_request('POST', 'pairing', []);
    echo json_encode(['ok'=>true,'payload'=>$payload],
        JSON_UNESCAPED_UNICODE | JSON_THROW_ON_ERROR);
} catch (Throwable $e) {
    // No exception details: the upstream might include secrets.
    error_log('[Open Margin pairing] Unable to display pairing QR: '.get_class($e));
    http_response_code(503);
    echo '{"ok":false,"message":"无法生成配对二维码，请检查服务器配置"}';
}
