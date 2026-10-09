<?php
/** Protected POST handler: verifies Wallos PHP login and a per-session CSRF token. */
ob_start();
require_once 'includes/header.php';
ob_end_clean();
require_once 'ledger-common.php';
ledger_require_owner();

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    http_response_code(405);
    exit('Method not allowed');
}
$csrf=$_POST['csrf'] ?? '';
if (!is_string($csrf) || !isset($_SESSION['finance_csrf']) ||
    !hash_equals($_SESSION['finance_csrf'],$csrf)) {
    http_response_code(403);
    exit('Security check failed');
}
$month=$_POST['month'] ?? date('Y-m');
if (!is_string($month) || !preg_match('/^\d{4}-(0[1-9]|1[0-2])$/',$month)) {
    $month=date('Y-m');
}
$action=$_POST['action'] ?? '';
try {
    if ($action==='add') {
        $raw=(string)($_POST['amount'] ?? '');
        if (!preg_match('/^[0-9]{1,9}(?:\.[0-9]{1,2})?$/D',$raw)) {
            throw new InvalidArgumentException('Invalid amount');
        }
        [$major,$fraction]=array_pad(explode('.',$raw,2),2,'');
        $amount=intval($major)*100+intval(str_pad($fraction,2,'0'));
        if ($amount<1 || $amount>100000000000) {
            throw new InvalidArgumentException('Amount out of bounds');
        }
        $date=(string)($_POST['spent_at'] ?? '');
        $parsed=DateTimeImmutable::createFromFormat('!Y-m-d\TH:i',$date,new DateTimeZone('Asia/Shanghai'));
        if (!$parsed || $parsed->format('Y-m-d\TH:i')!==$date) {
            throw new InvalidArgumentException('Invalid transaction time');
        }
        $uuid=(string)($_POST['request_id'] ?? '');
        $merchant=trim((string)($_POST['merchant'] ?? ''));
        $payload=[
            'idempotency_key'=>$uuid,
            'timestamp_ms'=>intval($parsed->format('U'))*1000,
            'amount_cents'=>$amount,
            'currency'=>(string)($_POST['currency'] ?? 'CNY'),
            'direction'=>(string)($_POST['direction'] ?? 'EXPENSE'),
            'category'=>(string)($_POST['category'] ?? 'OTHER'),
            'merchant'=>mb_substr($merchant,0,140,'UTF-8'),
        ];
        ledger_request('POST','manual',$payload);
        $notice='added';
    } elseif ($action==='budget') {
        $amountText=trim((string)($_POST['budget_amount']??''));
        if ($amountText==='' || $amountText==='0') {
            $cents=0;
        } else {
            if (!preg_match('/^[0-9]{1,9}(?:\.[0-9]{1,2})?$/D',$amountText)) {
                throw new InvalidArgumentException('Invalid monthly budget');
            }
            [$whole,$frac]=array_pad(explode('.',$amountText,2),2,'');
            $cents=intval($whole)*100+intval(str_pad($frac,2,'0'));
        }
        ledger_request('PUT','budget/'.$month,['amount_cents'=>$cents]);
        $notice='budget';
    } elseif ($action==='link') {
        $tx=filter_var($_POST['tx_id']??'',FILTER_VALIDATE_INT,['options'=>['min_range'=>1]]);
        $sub=filter_var($_POST['subscription_id']??'',FILTER_VALIDATE_INT,['options'=>['min_range'=>1]]);
        $cycle=(string)($_POST['cycle_key']??'');
        if (isset($_POST['subscription_cycle'])) {
            $pair=explode('|',(string)$_POST['subscription_cycle'],2);
            if(count($pair)===2){$sub=filter_var($pair[0],FILTER_VALIDATE_INT,['options'=>['min_range'=>1]]);$cycle=$pair[1];}
        }
        if(!$tx||!$sub||!preg_match('/^\d{4}-\d{2}-\d{2}$/D',$cycle))throw new InvalidArgumentException('Invalid link');
        ledger_request('POST','integration/link',['tx_id'=>$tx,'subscription_id'=>$sub,'cycle_key'=>$cycle]);
        $notice='saved';
    } elseif ($action==='unlink') {
        $tx=filter_var($_POST['tx_id']??'',FILTER_VALIDATE_INT,['options'=>['min_range'=>1]]);
        if(!$tx)throw new InvalidArgumentException('Invalid unlink');
        ledger_request('DELETE','integration/link/'.$tx);
        $notice='saved';
    } elseif ($action==='rule') {
        $needle=trim((string)($_POST['needle']??''));
        $category=(string)($_POST['category']??'OTHER');
        if(mb_strlen($needle)<3||mb_strlen($needle)>90)throw new InvalidArgumentException('Invalid merchant');
        ledger_request('POST','integration/rules',['needle'=>$needle,'category'=>$category]);
        $notice='saved';
    } elseif ($action==='delete_rule') {
        $id=filter_var($_POST['rule_id']??'',FILTER_VALIDATE_INT,['options'=>['min_range'=>1]]);
        if(!$id)throw new InvalidArgumentException('Invalid rule');
        ledger_request('DELETE','integration/rules/'.$id);
        $notice='saved';
    } elseif ($action==='edit') {
        $tx=filter_var($_POST['entry_id']??'',FILTER_VALIDATE_INT,['options'=>['min_range'=>1]]);
        $raw=trim((string)($_POST['amount']??''));
        if(!$tx||!preg_match('/^[0-9]{1,9}(?:\.[0-9]{1,2})?$/D',$raw))throw new InvalidArgumentException('Invalid amount');
        [$whole,$fraction]=array_pad(explode('.',$raw,2),2,'');
        $amount=intval($whole)*100+intval(str_pad($fraction,2,'0'));
        if($amount<1)throw new InvalidArgumentException('Invalid amount');
        ledger_request('PATCH','integration/transaction/'.$tx,[
            'amount_cents'=>$amount,'merchant'=>mb_substr((string)($_POST['merchant']??''),0,140),
            'category'=>(string)($_POST['category']??'OTHER')]);
        $notice='saved';
    } elseif ($action==='duplicate') {
        $tx=filter_var($_POST['entry_id']??'',FILTER_VALIDATE_INT,['options'=>['min_range'=>1]]);
        if(!$tx)throw new InvalidArgumentException('Invalid entry');
        ledger_request('POST','integration/duplicate/'.$tx);
        $notice='saved';
    } elseif ($action==='refund') {
        $original=filter_var($_POST['original_tx_id']??'',FILTER_VALIDATE_INT,['options'=>['min_range'=>1]]);
        $refund=filter_var($_POST['refund_tx_id']??'',FILTER_VALIDATE_INT,['options'=>['min_range'=>1]]);
        $raw=trim((string)($_POST['refund_amount']??''));
        if(!$original||!$refund||!preg_match('/^[0-9]{1,9}(?:\.[0-9]{1,2})?$/D',$raw))throw new InvalidArgumentException('Invalid refund');
        [$whole,$fraction]=array_pad(explode('.',$raw,2),2,'');
        $amount=intval($whole)*100+intval(str_pad($fraction,2,'0'));
        ledger_request('POST','integration/refund',['original_tx_id'=>$original,'refund_tx_id'=>$refund,'amount_cents'=>$amount]);
        $notice='saved';
    } elseif ($action==='unlink_refund') {
        $tx=filter_var($_POST['refund_tx_id']??'',FILTER_VALIDATE_INT,['options'=>['min_range'=>1]]);
        if(!$tx)throw new InvalidArgumentException('Invalid refund');
        ledger_request('DELETE','integration/refund/'.$tx);
        $notice='saved';
    } elseif ($action==='subscribe') {
        $date=(string)($_POST['next_due_date']??'');
        $parsed=DateTimeImmutable::createFromFormat('!Y-m-d',$date,new DateTimeZone('Asia/Shanghai'));
        $amount=filter_var($_POST['amount_cents']??'',FILTER_VALIDATE_INT,['options'=>['min_range'=>1]]);
        if(!$amount||!$parsed||$parsed->format('Y-m-d')!==$date)throw new InvalidArgumentException('Invalid next payment date');
        ledger_request('POST','integration/subscribe',[
          'name'=>mb_substr(trim((string)($_POST['name']??'')),0,140),
          'currency'=>(string)($_POST['currency']??'CNY'),
          'amount_cents'=>$amount,'cadence'=>(string)($_POST['cadence']??'monthly'),
          'next_due_ms'=>intval($parsed->format('U'))*1000]);
        $notice='saved';
    } elseif ($action==='review') {
        $entry=(string)($_POST['entry_id'] ?? '');
        $status=(string)($_POST['status'] ?? '');
        if (!ctype_digit($entry) || !in_array($status,
          ['confirmed','unverified','excluded','transfer','refund'],true)) {
            throw new InvalidArgumentException('Invalid review details');
        }
        ledger_request('PATCH','review/'.intval($entry),['status'=>$status]);
        $notice='reviewed';
    } else {
        throw new InvalidArgumentException('Unknown operation');
    }
} catch (Throwable $e) {
    error_log('[Wallos daily-ledger] Failed expense action '.get_class($e));
    $notice='failed';
}
$target=in_array($action,['link','unlink','rule','delete_rule','refund','unlink_refund','subscribe'],true)?'integration.php':'expenses.php';
header('Location: '.$target.'?month='.rawurlencode($month).'&notice='.$notice,true,303);
exit;
