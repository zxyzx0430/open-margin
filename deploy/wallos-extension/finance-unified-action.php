<?php
/** Authenticated same-origin JSON mutation facade, no new transaction storage. */
ob_start();
require_once 'includes/header.php';
ob_end_clean();
require_once 'finance-unified-common.php';
header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: private, no-store');
if($_SERVER['REQUEST_METHOD']!=='POST') {
    http_response_code(405);echo '{"ok":false,"message":"方法不支持"}';exit;
}
try {
    $content=file_get_contents('php://input');
    if(strlen($content)>8192)throw new InvalidArgumentException('Request too large');
    $body=json_decode($content,true,32,JSON_THROW_ON_ERROR);
    if(!is_array($body))throw new InvalidArgumentException('Invalid request');
    $csrf=(string)($body['csrf']??'');
    if(empty($_SESSION['finance_csrf'])||$csrf===''||
       !hash_equals($_SESSION['finance_csrf'],$csrf)) {
        http_response_code(403);echo '{"ok":false,"message":"会话已失效，请刷新页面"}';exit;
    }
    $operation=(string)($body['action']??'');
    if($operation==='review') {
        $id=filter_var($body['id']??null,FILTER_VALIDATE_INT,['options'=>['min_range'=>1]]);
        $status=(string)($body['status']??'');
        if(!$id||!in_array($status,['confirmed','unverified','excluded','transfer','possible_duplicate'],true))
            throw new InvalidArgumentException('Invalid review');
        ledger_request('PATCH','review/'.$id,['status'=>$status]);
    } elseif($operation==='edit') {
        $id=filter_var($body['id']??null,FILTER_VALIDATE_INT,['options'=>['min_range'=>1]]);
        $cents=filter_var($body['amount_cents']??null,FILTER_VALIDATE_INT,
            ['options'=>['min_range'=>1,'max_range'=>100000000000]]);
        $category=(string)($body['category']??'');
        $merchant=trim((string)($body['merchant']??''));
        if(!$id||$cents===false||!array_key_exists($category,finance_categories())||mb_strlen($merchant)>140)
            throw new InvalidArgumentException('Invalid transaction');
        ledger_request('PATCH','integration/transaction/'.$id,
            ['amount_cents'=>$cents,'merchant'=>$merchant,'category'=>$category]);
    } elseif($operation==='budget') {
        $month=finance_month((string)($body['month']??''));
        $cents=filter_var($body['amount_cents']??null,FILTER_VALIDATE_INT,
            ['options'=>['min_range'=>0,'max_range'=>100000000000]]);
        if($cents===false)throw new InvalidArgumentException('Invalid budget');
        ledger_request('PUT','budget/'.$month,['amount_cents'=>$cents]);
    } else {
        throw new InvalidArgumentException('Invalid action');
    }
    echo '{"ok":true}';
} catch(InvalidArgumentException|JsonException $e) {
    http_response_code(422);echo '{"ok":false,"message":"提交数据格式不正确"}';
} catch(Throwable $e) {
    error_log('[Finance unified] update failed '.get_class($e));
    http_response_code(503);echo '{"ok":false,"message":"保存未成功，请重试"}';
}
