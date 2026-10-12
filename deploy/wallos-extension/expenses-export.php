<?php
/** Export finance CSV using the same Wallos login as the ledger page.
 * The backend's read-only ledger token never reaches the browser.
 */
ob_start();
require_once 'includes/header.php';
ob_end_clean();
require_once 'ledger-common.php';
ledger_require_owner();
$month=$_GET['month']??'';
if (!is_string($month) || !preg_match('/^\d{4}-(0[1-9]|1[0-2])$/D',$month)) {
    http_response_code(422);
    exit('Invalid month');
}
$settings=@parse_ini_file('/run/secrets/wallos-ledger.env',false,INI_SCANNER_RAW);
$token=$settings['FINANCE_WALLOS_LEDGER_TOKEN']??'';
if(!is_string($token)||strlen($token)<30) {
    http_response_code(503);exit('Export unavailable');
}
$endpoint = $settings['FINANCE_LEDGER_API_URL'] ?? '';
if (!is_string($endpoint) || !preg_match('#^https?://[^/\s]+(?:/[A-Za-z0-9._~-]+)*/api/v1/wallos-ledger/?$#', $endpoint)) {
    http_response_code(503);exit('Export unavailable');
}
$ch=curl_init(rtrim($endpoint,'/').'/export?month='.$month);
curl_setopt_array($ch,[
    CURLOPT_RETURNTRANSFER=>true,
    CURLOPT_CONNECTTIMEOUT=>3,
    CURLOPT_TIMEOUT=>30,
    CURLOPT_SSL_VERIFYPEER=>true,
    CURLOPT_SSL_VERIFYHOST=>2,
    CURLOPT_HTTPHEADER=>['X-Wallos-Ledger-Token: '.$token],
]);
$csv=curl_exec($ch);
$status=curl_getinfo($ch,CURLINFO_RESPONSE_CODE);
curl_close($ch);
if($status!==200 || !is_string($csv)) {
    error_log('[Wallos daily-ledger] export failed HTTP '.$status);
    http_response_code(503);
    exit('Export unavailable');
}
header('Content-Type: text/csv; charset=utf-8');
header('X-Content-Type-Options: nosniff');
header('Cache-Control: private, no-store');
header('Content-Disposition: attachment; filename="finance-'.$month.'.csv"');
echo $csv;
