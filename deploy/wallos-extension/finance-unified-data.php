<?php
/** Same-origin authenticated JSON bridge for native Finance panels. */
ob_start();
require_once 'includes/header.php';
ob_end_clean();
require_once 'finance-unified-common.php';
ledger_require_owner();
header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: private, no-store, max-age=0');
try {
    $mode=(string)($_GET['mode']??'summary');
    $month=finance_month((string)($_GET['month']??date('Y-m')));
    if($mode==='summary') {
        $payload=finance_unified_model($db,(int)$userId,$month);
    } elseif($mode==='day') {
        $day=(string)($_GET['day']??'');
        $dt=DateTimeImmutable::createFromFormat('!Y-m-d',$day,new DateTimeZone('Asia/Shanghai'));
        if(!$dt || $dt->format('Y-m-d')!==$day || substr($day,0,7)!==$month)
            throw new InvalidArgumentException('Invalid date');
        $offset=filter_var($_GET['offset']??0,FILTER_VALIDATE_INT,
            ['options'=>['min_range'=>0,'max_range'=>1000000]]);
        if($offset===false)throw new InvalidArgumentException('Invalid page');
        $result=ledger_request('GET','month?month='.$month.'&day='.$day.'&offset='.$offset);
        $plan=finance_plan_month($db,(int)$userId,$month);
        $scheduled=array_values(array_filter($plan['events'],static fn($r)=>$r['date']===$day));
        $payload=['day'=>$day,'items'=>$result['items']??[],
            'next_offset'=>$result['next_offset']??null,
            'subscriptions'=>$scheduled];
    } elseif($mode==='transactions') {
        $day=(string)($_GET['day']??'');
        $category=(string)($_GET['category']??'');
        $direction=(string)($_GET['direction']??'');
        $offset=filter_var($_GET['offset']??0,FILTER_VALIDATE_INT,
            ['options'=>['min_range'=>0,'max_range'=>1000000]]);
        if($offset===false)throw new InvalidArgumentException('Invalid page');
        $params='month?month='.$month;
        if($day!=='') {
            $dt=DateTimeImmutable::createFromFormat('!Y-m-d',$day,new DateTimeZone('Asia/Shanghai'));
            if(!$dt || $dt->format('Y-m-d')!==$day || substr($day,0,7)!==$month)
                throw new InvalidArgumentException('Invalid day');
            $params.='&day='.$day;
        }
        if($category!=='') {
            if(!array_key_exists($category,finance_categories()))throw new InvalidArgumentException('Invalid category');
            $params.='&category='.$category;
        }
        if($direction!=='') {
            if(!in_array($direction,['INCOME','EXPENSE'],true))throw new InvalidArgumentException('Invalid direction');
            $params.='&direction='.$direction;
        }
        if($offset>0)$params.='&offset='.$offset;
        $result=ledger_request('GET',$params);
        $plan=$day!==''?finance_plan_month($db,(int)$userId,$month):['events'=>[]];
        $scheduled=$day!==''?array_values(array_filter($plan['events'],
            static fn($r)=>$r['date']===$day)):[];
        $payload=['items'=>$result['items']??[], 'next_offset'=>$result['next_offset']??null,
            'subscriptions'=>$scheduled];
    } else {
        throw new InvalidArgumentException('Invalid request');
    }
    echo finance_json(['ok'=>true,'data'=>$payload]);
} catch(InvalidArgumentException $e) {
    http_response_code(422);
    echo '{"ok":false,"message":"日期或筛选条件无效"}';
} catch(Throwable $e) {
    error_log('[Finance unified] read unavailable '.get_class($e));
    http_response_code(503);
    echo '{"ok":false,"message":"数据暂时不可用，请重试"}';
}
