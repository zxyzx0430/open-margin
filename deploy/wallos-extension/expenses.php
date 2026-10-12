<?php
/** Native Wallos daily-expense extension, backed by the existing Finance ledger.
 * Wallos's own subscription database remains untouched.
 */
// The vendor header emits HTML. Buffer it until the owner check passes so
// unauthorized accounts receive a real HTTP 403, not a 200 with partial UI.
ob_start();
require_once 'includes/header.php';
require_once 'ledger-common.php';
ledger_require_owner();
ob_end_flush();
$options = @parse_ini_file('/run/secrets/wallos-ledger.env', false, INI_SCANNER_RAW);
date_default_timezone_set($options['FINANCE_TIMEZONE'] ?? 'Asia/Shanghai');
$month=$_GET['month'] ?? date('Y-m');
if (!is_string($month) || !preg_match('/^\d{4}-(0[1-9]|1[0-2])$/D',$month)) {
    $month=date('Y-m');
}
if (empty($_SESSION['finance_csrf'])) {
    $_SESSION['finance_csrf']=bin2hex(random_bytes(32));
}
$csrf=$_SESSION['finance_csrf'];
$uuid=bin2hex(random_bytes(16));
$uuid=substr($uuid,0,8).'-'.substr($uuid,8,4).'-4'.substr($uuid,13,3).'-a'.substr($uuid,17,3).'-'.substr($uuid,20,12);
$filters=[
  'day'=>is_string($_GET['day']??null)?$_GET['day']:'',
  'category'=>is_string($_GET['category']??null)?$_GET['category']:'',
  'direction'=>is_string($_GET['direction']??null)?$_GET['direction']:'',
  'q'=>is_string($_GET['q']??null)?mb_substr($_GET['q'],0,80):'',
];
foreach(['day','category','direction'] as $key) { $filters[$key]=mb_substr($filters[$key],0,32); }
if ($filters['day']!=='' && (!preg_match('/^\d{4}-\d{2}-\d{2}$/D',$filters['day']) || substr($filters['day'],0,7)!==$month)) { $filters['day']=''; }
$offset=filter_var($_GET['offset']??0,FILTER_VALIDATE_INT,['options'=>['min_range'=>0,'max_range'=>1000000]]);
if ($offset===false) $offset=0;
$items=[];
$totals=[];
$todayTotals=[];
$weekTotals=[];
$prevMonthTotals=[];
$dailyTotals=[];
$dailyExcludedTotals=[];
$lastSynced=null;
$monthCount=0;
$budgetCny=null;
$nextOffset=null;
$labels=[
    'FOOD'=>'餐饮','TRANSPORT'=>'交通','SHOPPING'=>'购物','ENTERTAINMENT'=>'娱乐',
    'HOUSING'=>'住房','UTILITIES'=>'水电通信','HEALTH'=>'医疗','EDUCATION'=>'学习',
    'SUBSCRIPTION'=>'订阅','OTHER'=>'其他','SALARY'=>'工资','TRANSFER'=>'转账'
];
$error='';$ledgerLoaded=false;
$sourceNames=['WECHAT'=>'微信','ALIPAY'=>'支付宝','BANK_SMS'=>'银行短信',
    'MANUAL'=>'手动记录','OTHER'=>'其他来源'];
try {
    $parameters=array_filter($filters,static fn($v)=>$v!=='');
    if ($offset>0) $parameters['offset']=$offset;
    $query='month?month='.$month;
    foreach($parameters as $key=>$val) {
        $query.='&'.$key.'='.rawurlencode((string)$val);
    }
    $result=ledger_request('GET',$query);
    $ledgerLoaded=true;
    $items=$result['items'] ?? [];
    $totals=$result['totals'] ?? [];
    $todayTotals=$result['today_totals'] ?? [];
    $weekTotals=$result['last7_totals'] ?? [];
    $prevMonthTotals=$result['previous_month_totals'] ?? [];
    $dailyTotals=$result['daily_totals'] ?? [];
    $dailyExcludedTotals=$result['daily_excluded_totals'] ?? [];
    $monthCount=intval($result['month_count'] ?? count($items));
    $budgetCny=$result['budget_cny_cents']??null;
    $nextOffset=$result['next_offset']??null;
    $lastSynced=$result['last_synced_at_utc']??null;
} catch (Throwable $ex) {
    $error='交易服务暂时不可用，请稍后再试。';
    error_log('[Wallos daily-ledger] Page load '.get_class($ex));
}
$sum=[];
$categoryExpense=[];
foreach ($totals as $row) {
    $currency=(string)($row['currency'] ?? 'CNY');
    $direction=(string)($row['direction'] ?? 'EXPENSE');
    $category=(string)($row['category'] ?? 'OTHER');
    $cents=intval($row['total_cents'] ?? 0);
    $sum[$currency][$direction]=($sum[$currency][$direction]??0)+$cents;
    if ($direction==='EXPENSE' && $currency==='CNY') {
        $categoryExpense[$category]=($categoryExpense[$category]??0)+$cents;
    }
}
arsort($categoryExpense);
function ledger_sums(array $rows, string $direction): array {
    $totals=[];
    foreach($rows as $item) {
        if(($item['direction']??'')!==$direction) continue;
        $currency=(string)($item['currency']??'CNY');
        $totals[$currency]=($totals[$currency]??0)+intval($item['total_cents']??0);
    }
    ksort($totals);
    return $totals;
}
function ledger_figure(array $total,string $direction):string {
    $values=ledger_sums($total,$direction);
    return $values?implode(' / ',array_map(
        static fn($c,$v)=>ledger_money($v,$c),
        array_keys($values),array_values($values))):'¥0.00';
}
function ledger_page_link(string $month,array $filters,int $offset=0):string {
    $query=['month'=>$month];
    foreach ($filters as $key=>$value) if($value!=='') $query[$key]=$value;
    if($offset) $query['offset']=$offset;
    return 'expenses.php?'.http_build_query($query,'','&',PHP_QUERY_RFC3986);
}
$monthlySpend=ledger_figure($totals,'EXPENSE');
$todaySpend=ledger_figure($todayTotals,'EXPENSE');
$weekSpend=ledger_figure($weekTotals,'EXPENSE');
$monthlyIncome=ledger_figure($totals,'INCOME');
$monthCny=(ledger_sums($totals,'EXPENSE'))['CNY']??0;
$previousCny=(ledger_sums($prevMonthTotals,'EXPENSE'))['CNY']??0;
$elapsedDays=($month===date('Y-m'))?(int)date('j'):(int)date('t',strtotime($month.'-01'));
$avgCny=$elapsedDays>0?round($monthCny/$elapsedDays):0;
$plot=[];
foreach($dailyTotals as $row) {
    if($row['currency']!=='CNY'||$row['direction']!=='EXPENSE')continue;
    $plot[$row['day']]=($plot[$row['day']]??0)+intval($row['total_cents']);
}
// Excluded totals never contribute to the chart's bar height.
$excludedPlot=[];
foreach($dailyExcludedTotals as $row) {
    if($row['currency']!=='CNY'||$row['direction']!=='EXPENSE')continue;
    $excludedPlot[$row['day']]=($excludedPlot[$row['day']]??0)+(int)$row['total_cents'];
}
$lastDay=(int)date('t',strtotime($month.'-01'));
$dailyChartPoints=[];
for($n=1;$n<=$lastDay;$n++) {
    $dateKey=sprintf('%s-%02d',$month,$n);
    $dailyChartPoints[]=['day'=>$dateKey,
        'cents'=>max(0,(int)($plot[$dateKey]??0)),
        'excluded_cents'=>max(0,(int)($excludedPlot[$dateKey]??0))];
}
$hasDailySpend=array_sum(array_column($dailyChartPoints,'cents'))>0;
$revision=hash('sha256',json_encode([$monthCount,$lastSynced,$totals,$dailyTotals,$dailyExcludedTotals,$budgetCny,$filters,$offset],JSON_UNESCAPED_UNICODE));
$notice=$_GET['notice'] ?? '';
?>
<style>
/* Layout-only adapters; all typography, buttons, cards, radii, colors and
   dark-mode treatments inherit from the installed Wallos stylesheets. */
.wallos-ledger {padding-bottom:84px}
.wallos-ledger .ledger-heading {display:flex;justify-content:space-between;align-items:center;gap:18px;flex-wrap:wrap}
.wallos-ledger .ledger-heading h1{margin:0}
.wallos-ledger .ledger-heading p{margin:8px 0 0;color:var(--text-muted);font-size:14px}
.wallos-ledger .ledger-tools{display:flex;gap:10px;align-items:center}
.wallos-ledger .ledger-month{display:flex;gap:8px;align-items:center}
.wallos-ledger .ledger-month input{max-width:180px;height:50px;padding:0 15px;background:var(--input-background-color);color:var(--text-color);border:1px solid var(--input-border-color);border-radius:10px;font:inherit;box-sizing:border-box}
.wallos-ledger .ledger-month input:focus{border-color:var(--main-color);outline:0;box-shadow:var(--focus-ring)}
.wallos-ledger .ledger-intro{font-size:14px;line-height:1.55;color:var(--text-muted);margin:14px 0 0}
.wallos-ledger .ledger-inline{display:flex;gap:12px;align-items:center;justify-content:space-between;flex-wrap:wrap}
.wallos-ledger .ledger-inline>h2{font-size:14px;font-weight:600;letter-spacing:.08em;text-transform:uppercase;color:var(--text-muted);margin:0 0 14px;padding-bottom:10px;border-bottom:1px solid var(--box-border-color)}
.wallos-ledger .ledger-money{font-variant-numeric:tabular-nums;font-weight:600;white-space:nowrap}
.wallos-ledger .ledger-empty{color:var(--text-muted);margin:10px 0}
.wallos-ledger .ledger-entry .subscription{cursor:default}
.wallos-ledger .ledger-entry .subscription-main{gap:14px}
.wallos-ledger .ledger-entry .ledger-mark{display:grid;place-items:center;width:46px;height:46px;flex:0 0 46px;background:rgba(var(--main-color-rgb),.10);border-radius:12px;color:var(--main-color);font-size:19px}
.wallos-ledger .ledger-entry .ledger-text{flex:1;min-width:0}
.wallos-ledger .ledger-entry .ledger-name{font-weight:600;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.wallos-ledger .ledger-entry .ledger-details{font-size:13px;color:var(--text-muted);margin-top:4px}
.wallos-ledger .ledger-entry .ledger-amount{font-size:19px;flex:0 0 auto}
.wallos-ledger .ledger-entry .ledger-review{display:flex;align-items:center;justify-content:space-between;gap:9px;margin-left:60px}
.wallos-ledger .ledger-entry .ledger-review small{color:var(--text-muted);font-size:12px}
.wallos-ledger .ledger-review-state.is-excluded{color:var(--text-muted);background:var(--box-background-color);border:1px solid var(--box-border-color);border-radius:7px;padding:5px 7px}
.wallos-ledger .ledger-review-state:not(.is-excluded){font-weight:600}

.wallos-ledger .ledger-entry .ledger-review select{height:35px;font-size:13px;padding:0 30px 0 10px;min-width:100px}
.wallos-ledger .ledger-form-grid{display:grid;grid-template-columns:1fr 1fr;gap:4px 15px}
.wallos-ledger .ledger-form-grid .form-group{min-width:0;margin-bottom:14px}
.wallos-ledger input[type="datetime-local"]{width:100%;height:50px;padding:0 15px;background:var(--input-background-color);color:var(--text-color);border:1px solid var(--input-border-color);border-radius:10px;font:inherit;box-sizing:border-box}
.wallos-ledger input[type="datetime-local"]:focus{border-color:var(--main-color);outline:0;box-shadow:var(--focus-ring)}
.wallos-ledger .ledger-form-grid .form-group.wide{grid-column:1/-1}
.wallos-ledger .ledger-section-message{color:var(--text-muted);font-size:13px;margin-top:9px}
.wallos-ledger .ledger-category-list{margin-top:15px}
.wallos-ledger .ledger-category-list .subscription-item{cursor:default}
.wallos-ledger .ledger-meta-pill{font-size:12px;color:var(--text-muted)}
/* Calendar-accurate chart has dedicated ApexCharts layout below. */
.wallos-ledger .ledger-search-form{display:flex;flex-wrap:wrap;gap:10px;align-items:end;margin:8px 0 16px}
.wallos-ledger .ledger-search-form input,.wallos-ledger .ledger-search-form select{max-width:210px;min-width:115px;padding:11px;border-radius:9px;background:var(--input-background-color);color:var(--text-color);border:1px solid var(--input-border-color)}
.wallos-ledger .ledger-sync{font-size:12px;color:var(--text-muted);margin:5px 0 16px}
.wallos-ledger .ledger-budget-track{width:100%;height:10px;border-radius:7px;overflow:hidden;background:var(--box-border-color)}
.wallos-ledger .ledger-budget-used{height:100%;border-radius:7px;background:var(--main-color)}
.wallos-ledger .ledger-budget-form{display:flex;flex-wrap:wrap;align-items:end;gap:9px;margin-top:13px}
.wallos-ledger .ledger-budget-form label{flex:1 1 100%}
.wallos-ledger .ledger-budget-form input{flex:1 1 160px;height:48px;background:var(--input-background-color);color:var(--text-color);border:1px solid var(--input-border-color);border-radius:9px;padding:10px;font:inherit}

@media(max-width:768px){.wallos-ledger .ledger-search-form input,.wallos-ledger .ledger-search-form select{max-width:none;flex:1 1 125px;min-width:0}}
.wallos-ledger .ledger-dialog-backdrop{display:none;position:fixed;inset:0;background:#0008;z-index:19}
.wallos-ledger .ledger-dialog-backdrop.open{display:block}
.wallos-ledger .ledger-dialog{z-index:20}
.wallos-ledger .ledger-dialog header{display:flex;justify-content:space-between;align-items:center}
.wallos-ledger .ledger-close{border:0;background:transparent;color:var(--text-color);font-size:23px;cursor:pointer}
@media(max-width:768px){
.wallos-ledger{padding:18px 16px 90px}
.wallos-ledger .ledger-heading{align-items:stretch}
.wallos-ledger .ledger-heading .button{width:100%}
.wallos-ledger .ledger-tools{width:100%;flex-direction:column;align-items:stretch}
.wallos-ledger .ledger-month{width:100%}
.wallos-ledger .ledger-month input{flex:1;max-width:none}
.wallos-ledger .ledger-entry .ledger-mark{width:42px;height:42px;flex-basis:42px}
.wallos-ledger .ledger-entry .ledger-review{margin-left:0}
.wallos-ledger .ledger-entry .ledger-amount{font-size:17px}
.wallos-ledger .ledger-form-grid{grid-template-columns:1fr}
.wallos-ledger .ledger-form-grid .form-group{grid-column:1/-1}
}

/* Compact readable finance statistics and transaction editor on small screens. */
.wallos-ledger .statistics{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:12px}
.wallos-ledger .statistics .statistic{flex:initial;min-width:0;min-height:105px;padding:18px 14px;align-items:flex-start;justify-content:center;gap:6px}
.wallos-ledger .statistics .statistic>span{font-size:clamp(20px,2.2vw,29px);font-weight:600;line-height:1.3;letter-spacing:-.03em;font-variant-numeric:tabular-nums;overflow-wrap:anywhere}
.wallos-ledger .statistics .statistic>.title{font-size:13px;text-align:left;margin:0;line-height:1.4}
.wallos-ledger .ledger-entry .ledger-edit-details{margin:12px 0 12px 0;padding:10px 14px;border:1px solid var(--box-border-color);border-radius:10px;background:var(--box-background-color)}
.wallos-ledger .ledger-entry .ledger-edit-details>summary{cursor:pointer;font-size:13px}
.wallos-ledger .ledger-entry .ledger-edit-details label{display:flex;flex-direction:column;gap:6px;min-width:0;flex:1 1 150px}
.wallos-ledger .ledger-entry .ledger-edit-details form{max-width:100%}
.wallos-ledger .ledger-entry .ledger-edit-details input,.wallos-ledger .ledger-entry .ledger-edit-details select{max-width:100%;min-width:0}
.wallos-ledger .ledger-entry .ledger-edit-details .ledger-budget-form{align-items:flex-end}
.wallos-ledger :is(button,input,select,summary,a):focus-visible{outline:2px solid var(--main-color);outline-offset:3px}
@media(max-width:768px){
 .wallos-ledger .statistics{grid-template-columns:repeat(2,minmax(0,1fr));gap:10px}
 .wallos-ledger .statistics .statistic{min-height:93px;padding:14px 12px}
 .wallos-ledger .statistics .statistic>span{font-size:clamp(19px,5vw,24px)}
 .wallos-ledger .statistics .statistic>.title{font-size:12px}
 .wallos-ledger .ledger-heading .ledger-tools{gap:10px}
 .wallos-ledger .ledger-entry .ledger-edit-details{margin:10px 0}
 .wallos-ledger .ledger-entry .ledger-edit-details .ledger-budget-form{display:grid;grid-template-columns:1fr}
 .wallos-ledger .ledger-entry .ledger-edit-details form input,.wallos-ledger .ledger-entry .ledger-edit-details form select,.wallos-ledger .ledger-entry .ledger-edit-details form button{width:100%;max-width:none}
}
/* v1.5.1 financial hierarchy and responsive mobile controls */
.wallos-ledger{--finance-gap:14px}
.wallos-ledger .ledger-live-state{display:flex;align-items:center;gap:8px;flex-wrap:wrap;padding:10px 13px;margin:18px 0;border:1px solid var(--box-border-color);border-radius:12px;background:var(--box-background-color);font-size:13px;color:var(--text-muted)}
.wallos-ledger .ledger-live-state b{color:var(--text-color)}
.wallos-ledger .ledger-live-state a{margin-left:auto;color:var(--main-color);font-weight:600}
.wallos-ledger .ledger-live-dot{width:8px;height:8px;flex:0 0 8px;border-radius:100%;background:#38a169}
.wallos-ledger .stats-section{margin-bottom:24px}
.wallos-ledger .stats-section h2{font-size:16px;margin-bottom:12px;font-weight:650}
.wallos-ledger .ledger-explainer{font-size:12px;line-height:1.55;opacity:.85;max-width:690px}
.wallos-ledger .ledger-secondary-panel{margin-top:16px;border-top:1px solid var(--box-border-color);padding-top:12px}
.wallos-ledger .ledger-secondary-panel summary{cursor:pointer;color:var(--main-color);font-size:13px;font-weight:600;padding:8px 0}
.wallos-ledger .ledger-secondary-panel .ledger-budget-form{margin-top:12px}
.wallos-ledger .ledger-budget-form input{box-sizing:border-box}
/* Accessible, scrollable monthly column chart. Keep the Y axis auto-scaled. */
.wallos-ledger .ledger-chart-shell{max-width:100%;overflow-x:auto;overflow-y:hidden;overscroll-behavior-x:contain;border:1px solid var(--box-border-color);border-radius:12px;padding:10px 2px 1px;background:var(--box-background-color)}
.wallos-ledger .ledger-apex-chart{min-width:620px;width:100%;height:248px}
.wallos-ledger .ledger-chart-controls{display:flex;flex-wrap:wrap;align-items:center;gap:10px;margin:12px 0;font-size:13px;color:var(--text-muted)}
.wallos-ledger .ledger-chart-controls label{font-weight:600}
.wallos-ledger .ledger-chart-controls select{min-width:155px;max-width:100%;height:42px;border:1px solid var(--input-border-color);border-radius:9px;background:var(--input-background-color);color:var(--text-color);padding:0 12px;font:inherit}
.wallos-ledger .ledger-chart-selection{display:flex;align-items:center;gap:10px;flex-wrap:wrap;background:var(--box-background-color);border:1px solid var(--box-border-color);border-radius:12px;padding:12px 14px;margin:10px 0;font-size:14px;color:var(--text-color)}
.wallos-ledger .ledger-chart-selection[hidden]{display:none!important}
/* Day selection: distinct bar, date band and confirmed selection card. */
.wallos-ledger .ledger-apex-chart{position:relative}
.wallos-ledger .finance-daily-focus-band{position:absolute;z-index:2;pointer-events:none;border:2px solid var(--main-color);border-radius:7px;background:var(--main-color);opacity:1;box-sizing:border-box;box-shadow:inset 0 0 0 1px var(--main-color),0 0 10px color-mix(in srgb,var(--main-color) 28%,transparent);background:color-mix(in srgb,var(--main-color) 17%,transparent);transition:opacity .2s ease}
.wallos-ledger .finance-daily-focus-band[hidden]{display:none}
.wallos-ledger .finance-daily-has-selection .apexcharts-bar-area:not(.finance-daily-bar-selected){opacity:.48;transition:opacity .18s ease}
.wallos-ledger .apexcharts-bar-area.finance-daily-bar-selected{opacity:1!important;filter:brightness(1.15) drop-shadow(0px 0px 5px var(--main-color));transform-box:fill-box;transform-origin:center bottom;transform:scaleX(1.27) scaleY(1.08);transition:filter .18s ease,transform .18s ease}
.wallos-ledger .finance-daily-bar-pop{animation:financeDailyBarPop .48s ease-out}
.wallos-ledger .ledger-chart-selection.is-active{border:2px solid var(--main-color);background:color-mix(in srgb,var(--main-color) 10%,var(--box-background-color));box-shadow:0 2px 13px color-mix(in srgb,var(--main-color) 20%,transparent);padding:11px 13px}
.wallos-ledger .ledger-chart-selection.is-active strong{font-size:18px;color:var(--main-color)}
.wallos-ledger .ledger-chart-selection .finance-daily-selected-label{font-weight:700;color:var(--main-color)}
.wallos-ledger .ledger-daily-excluded{font-size:12px;color:var(--text-muted);font-weight:600}
.wallos-ledger .ledger-chart-table table{min-width:480px}

.wallos-ledger .ledger-chart-selection.finance-daily-selection-pop{animation:financeDailySelectionPop .46s ease-out}
@keyframes financeDailyBarPop{0%{filter:brightness(1.4) drop-shadow(0px 0px 10px var(--main-color));transform:scaleX(1.45) scaleY(1.14)}100%{transform:scaleX(1.27) scaleY(1.08)}}
@keyframes financeDailySelectionPop{0%{transform:scale(.985)}65%{transform:scale(1.008)}100%{transform:scale(1)}}
@media(prefers-reduced-motion:reduce){.wallos-ledger .finance-daily-focus-band,.wallos-ledger .apexcharts-bar-area,.wallos-ledger .finance-daily-selection-pop,.wallos-ledger .finance-daily-bar-pop{animation:none!important;transition:none!important}}
.wallos-ledger .ledger-chart-selection strong{font-variant-numeric:tabular-nums}
.wallos-ledger .ledger-chart-selection a{margin-left:auto;color:var(--main-color);font-weight:600;text-decoration:underline}
.wallos-ledger .ledger-chart-table{margin-top:12px;max-width:100%;overflow-x:auto}
.wallos-ledger .ledger-chart-table summary{cursor:pointer;font-size:13px;color:var(--main-color);padding:8px 0}
.wallos-ledger .ledger-chart-table table{width:100%;border-collapse:collapse;font-size:13px;color:var(--text-color)}
.wallos-ledger .ledger-chart-table td,.wallos-ledger .ledger-chart-table th{text-align:left;border-bottom:1px solid var(--box-border-color);padding:8px 6px}
.wallos-ledger .ledger-chart-table button{border:0;background:none;padding:4px 8px;color:var(--main-color);text-decoration:underline;cursor:pointer;font:inherit}
@media(max-width:768px){.wallos-ledger .ledger-chart-shell{margin-right:0}.wallos-ledger .ledger-chart-selection a{margin-left:0}}
.wallos-ledger .ledger-empty-chart{padding:20px;border:1px dashed var(--box-border-color);border-radius:12px;font-size:13px;color:var(--text-muted)}
.wallos-ledger .ledger-empty-chart i{margin-right:7px}
.wallos-ledger .ledger-search-form{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));align-items:end;gap:12px;margin:14px 0 22px}
.wallos-ledger .ledger-filter-field{display:flex;flex-direction:column;gap:6px;min-width:0;font-size:12px;font-weight:600;color:var(--text-muted)}
.wallos-ledger .ledger-filter-field input,.wallos-ledger .ledger-filter-field select{box-sizing:border-box;flex:0 0 auto!important;height:46px;min-height:46px;min-width:0;max-width:none;width:100%;font-size:14px}
.wallos-ledger .ledger-search-form>.button{min-height:43px;display:inline-flex;justify-content:center;align-items:center}
.wallos-ledger .ledger-entry{margin-bottom:9px}
.wallos-ledger .ledger-entry .subscription{border-radius:12px}
.wallos-ledger .ledger-entry .ledger-name{max-width:100%;white-space:normal;overflow-wrap:anywhere;line-height:1.45}
.wallos-ledger .ledger-entry .ledger-details{line-height:1.45}
.wallos-ledger .ledger-entry .ledger-review{border-top:1px solid var(--box-border-color);padding-top:10px;margin-top:10px}
.wallos-ledger .ledger-inline{gap:8px}
.wallos-ledger .ledger-inline>.button{display:inline-flex;align-items:center;gap:6px}
@media(max-width:768px){
 .wallos-ledger .ledger-search-form{grid-template-columns:repeat(2,minmax(0,1fr));gap:11px}
 .wallos-ledger .ledger-search-form .ledger-filter-field input,.wallos-ledger .ledger-search-form .ledger-filter-field select{max-width:none;min-width:0;flex:0 0 46px!important}
 .wallos-ledger .ledger-search-form>.button{min-width:0;max-width:none}
 .wallos-ledger .ledger-inline{align-items:center;justify-content:flex-start}
 .wallos-ledger .ledger-inline>h2{flex:1 1 100%!important}
 .wallos-ledger .ledger-inline>.button{font-size:12px;max-width:100%}
 .wallos-ledger .ledger-entry .subscription-main{flex-wrap:wrap}
 .wallos-ledger .ledger-entry .ledger-amount{margin-left:auto}
 .wallos-ledger .ledger-live-state a{margin-left:0}
}
@media(max-width:345px){
 .wallos-ledger .ledger-search-form{grid-template-columns:1fr}
 .wallos-ledger .ledger-inline>.button{flex:1 1 100%}
}
</style>
<section class="contain dashboard wallos-ledger">
  <div class="ledger-heading">
    <div>
      <h1>日常消费</h1>
      <p>交易流水 · 与你的 Wallos 订阅管理使用同一账户</p>
    </div>
    <div class="ledger-tools">
      <form method="get" action="expenses.php" class="ledger-month">
        <input aria-label="查看月份" type="month" name="month" value="<?= ledger_escape($month) ?>">
        <button class="button secondary-button thin" type="submit"><i class="fa-solid fa-calendar-days"></i> 查看</button>
      </form>
      <button class="button" type="button" onclick="ledgerToggle(true)">
        <i class="fa-solid fa-circle-plus"></i> 新增记录
      </button>
    </div>
  </div>
  <p class="ledger-intro">按月实时汇总已同步交易。Wallos 订阅计划不会重复计入实际支出。</p>
  <?php if($error): ?><div class="error-box" role="alert"><i class="fa-solid fa-circle-exclamation"></i> <?= ledger_escape($error) ?></div><?php endif; ?>
  <?php if ($notice==='added'): ?><div class="ledger-section-message" role="status"><i class="fa-solid fa-circle-check"></i> 已成功记录交易。</div>
  <?php elseif ($notice==='reviewed'): ?><div class="ledger-section-message" role="status"><i class="fa-solid fa-circle-check"></i> 已更新核对状态。</div>
  <?php elseif ($notice==='budget'): ?><div class="ledger-section-message" role="status"><i class="fa-solid fa-circle-check"></i> 本月消费预算已保存。</div>
  <?php elseif ($notice==='failed'): ?><div class="error-box" role="alert">未能保存，请检查金额与日期。</div><?php endif; ?>

  <?php if (!$ledgerLoaded): ?>
  <section class="stats-section" role="alert" style="padding:16px 0">
    <h2>账本连接暂时不可用</h2>
    <p class="ledger-intro">未能取得实时交易，不显示任何可能误导的零元统计。你的已同步交易仍保存在服务器上。</p>
    <a class="button" href="<?= ledger_escape(ledger_page_link($month,$filters,$offset)) ?>">重新读取本月账本</a>
  </section>
  <?php else: ?>
  <div class="ledger-live-state" role="status"><span class="ledger-live-dot" aria-hidden="true"></span>本月已记录 <b><?=intval($monthCount)?></b> 笔，实时更新 <a href="<?=ledger_escape(ledger_page_link($month,$filters,$offset))?>">刷新数据</a></div>
  <div id="ledger-live" data-revision="<?= ledger_escape($revision) ?>">
    <section class="stats-section">
      <h2>消费概览 · <?= ledger_escape($month) ?></h2>
      <div class="statistics">
        <div class="statistic"><span><?= ledger_escape($todaySpend) ?></span><div class="title">今日支出</div></div>
        <div class="statistic"><span><?= ledger_escape($weekSpend) ?></span><div class="title">近 7 天支出</div></div>
        <div class="statistic"><span><?= ledger_escape($monthlySpend) ?></span><div class="title">本月支出</div></div>
        <div class="statistic"><span><?= ledger_escape($monthlyIncome) ?></span><div class="title">本月收入</div></div>
        <div class="statistic"><span><?= ledger_escape(ledger_money((int)$avgCny,'CNY')) ?></span><div class="title">本月人民币日均支出</div></div>
        <div class="statistic"><span><?= $monthCount ?></span><div class="title">本月记录笔数</div></div>
      </div>
      <p class="ledger-section-message ledger-explainer">仅统计已录入交易，未经官方账单确认的记录可能存在遗漏或误识别；跨币种不合并。</p>
    </section>
    <section class="stats-section">
      <h2>消费预算 · 人民币</h2>
      <div class="dashboard-subscriptions-container">
        <?php if($budgetCny!==null):
          $usedPercent=$budgetCny>0?round(100*$monthCny/$budgetCny,1):0;
          $remaining=max(0,$budgetCny-$monthCny);
        ?>
          <div class="statistics" style="margin-bottom:14px">
            <div class="statistic"><span><?= ledger_escape(ledger_money($budgetCny,'CNY')) ?></span><div class="title">本月预算</div></div>
            <div class="statistic"><span><?= ledger_escape(ledger_money($remaining,'CNY')) ?></span><div class="title">预算剩余</div></div>
          </div>
          <div class="ledger-budget-track" role="progressbar" aria-label="人民币月度消费预算使用比例"
               aria-valuemin="0" aria-valuemax="100" aria-valuenow="<?= min(100,(int)round($usedPercent)) ?>">
            <div class="ledger-budget-used" style="width:<?= max(0,min(100,$usedPercent)) ?>%"></div>
          </div>
          <p class="ledger-section-message">已使用 <?= ledger_escape($usedPercent) ?>%<?php if($usedPercent>100): ?> · 超出预算 <?= ledger_escape(ledger_money($monthCny-$budgetCny,'CNY')) ?><?php endif; ?></p>
        <?php else: ?><p class="ledger-section-message">尚未设置日常消费预算。此预算独立于 Wallos 的会员订阅预算。</p><?php endif; ?>
        <details class="ledger-secondary-panel">
          <summary>修改本月预算</summary>
        <form method="post" action="expenses-action.php" class="ledger-budget-form">
          <input type="hidden" name="csrf" value="<?= ledger_escape($csrf) ?>">
          <input type="hidden" name="action" value="budget">
          <input type="hidden" name="month" value="<?= ledger_escape($month) ?>">
<?php foreach($filters as $returnKey=>$returnValue): ?>
          <input type="hidden" name="return_<?= ledger_escape($returnKey) ?>" value="<?= ledger_escape($returnValue) ?>">
          <?php endforeach; ?>
          <input type="hidden" name="return_offset" value="<?= intval($offset) ?>">
          <label for="ledger-budget-amount" class="ledger-section-message">本月预算金额（CNY，留空可清除）</label>
          <input id="ledger-budget-amount" name="budget_amount" type="text" inputmode="decimal"
               pattern="[0-9]+(\.[0-9]{1,2})?" placeholder="例如 1500.00"
               maxlength="12" value="<?= $budgetCny!==null?ledger_escape(number_format($budgetCny/100,2,'.','')):'' ?>">
          <button type="submit" class="button secondary-button thin">保存预算</button>
        </form>
        </details>
      </div>
    </section>
    <section class="stats-section" id="finance-daily-chart-section">
      <h2>每日消费趋势 · <?= ledger_escape($month) ?></h2>
      <?php if(!$hasDailySpend): ?>
        <p class="ledger-section-message">该月尚无已记录的人民币支出。所有日期按 ¥0.00 展示。</p>
      <?php endif; ?>
      <div class="ledger-chart-shell" tabindex="0" role="region" aria-label="每日消费柱状图，可左右滑动查看整月">
        <div id="finance-daily-chart" class="ledger-apex-chart" role="img" aria-label="本月按公历日期排列的每日人民币实际支出图；可点击柱子选择日期"></div>
      </div>
      <div class="ledger-chart-controls">
        <label for="finance-daily-date">查看指定日期</label>
        <select id="finance-daily-date" aria-label="选择每日消费日期">
          <option value="">选择日期</option>
          <?php foreach($dailyChartPoints as $point): ?>
            <option value="<?= ledger_escape($point['day']) ?>"><?= ledger_escape(substr($point['day'],8,2).' 日 · '.ledger_money($point['cents'],'CNY')) ?></option>
          <?php endforeach; ?>
        </select>
        <span>点击柱子或图表日期区域，蓝色高亮表示已选中；左右滑动查看整月</span>
      </div>
      <div class="ledger-chart-selection" id="finance-daily-selection" role="status" aria-live="polite" hidden>
        <span class="finance-daily-selected-label">已选日期</span><span id="finance-daily-selected-date"></span>
        <strong id="finance-daily-selected-amount"></strong>
        <span id="finance-daily-selected-excluded" class="ledger-daily-excluded" hidden></span>
        <a id="finance-daily-detail-link" href="expenses.php">查看当日全部交易（含不计入）</a>
      </div>
      <details class="ledger-chart-table">
        <summary>查看整月每日金额与明细（含零消费日期）</summary>
        <table>
          <thead><tr><th scope="col">日期（北京时间）</th><th scope="col">实际支出</th><th scope="col">不计入</th><th scope="col">操作</th></tr></thead>
          <tbody>
            <?php foreach($dailyChartPoints as $point): ?>
              <tr>
                <td><?= ledger_escape($point['day']) ?></td>
                <td><?= ledger_escape(ledger_money($point['cents'],'CNY')) ?></td>
                <td><?= ledger_escape(ledger_money($point['excluded_cents'],'CNY')) ?></td>
                <td><a href="<?= ledger_escape(ledger_page_link($month,['day'=>$point['day'],'category'=>'','direction'=>'','q'=>''])) ?>">查看流水</a></td>
              </tr>
            <?php endforeach; ?>
          </tbody>
        </table>
      </details>
      <p class="ledger-section-message ledger-explainer">柱子显示 VPS 账本中已计入的人民币支出；下方可查看当日“不计入”的金额和全部交易。手机 App 删除本地记录目前不会自动在 VPS 上标记“不计入”；如发现两端不一致，请在此页面对应交易中选择“不计入”。退款、转账和排除交易不计入实际支出。</p>
    </section>
    <script type="application/json" id="finance-daily-data"><?= json_encode($dailyChartPoints,JSON_HEX_TAG|JSON_HEX_AMP|JSON_HEX_APOS|JSON_HEX_QUOT|JSON_UNESCAPED_UNICODE) ?></script>
    <script src="scripts/libs/apexcharts.min.js"></script>
    <script src="daily-spend-chart-v2.js?v=<?= ledger_escape(filemtime(__DIR__.'/daily-spend-chart-v2.js')) ?>"></script>
  <?php if($categoryExpense): ?>
  <section class="stats-section">
    <h2>支出分类 · 人民币</h2>
    <div class="dashboard-subscriptions-container ledger-category-list">
      <div class="dashboard-subscriptions-list">
        <?php foreach(array_slice($categoryExpense,0,10,true) as $cat=>$cents): ?>
          <div class="subscription-item thin">
            <p class="subscription-item-title"><?= ledger_escape($labels[$cat]??$cat) ?></p>
            <p class="subscription-item-value"><?= ledger_escape(ledger_money($cents,'CNY')) ?></p>
          </div>
        <?php endforeach; ?>
      </div>
    </div>
  </section>
  <?php endif; ?>

  <section class="stats-section">
    <div class="ledger-inline">
      <h2 style="flex:1;margin-bottom:14px">交易流水 · <?= ledger_escape($month) ?></h2>
      <a href="expenses-export.php?month=<?= ledger_escape($month) ?>" class="button secondary-button tiny"><i class="fa-solid fa-file-csv"></i> 导出 CSV</a>
      <a href="subscriptions.php" class="button secondary-button tiny"><i class="fa-solid fa-rotate"></i> 订阅管理</a>
    </div>
    <form method="get" action="expenses.php" class="ledger-search-form" aria-label="筛选消费流水">
      <input type="hidden" name="month" value="<?= ledger_escape($month) ?>">

      <label class="ledger-filter-field"><span>交易日期</span><input type="date" name="day" aria-label="按日期" value="<?= ledger_escape($filters['day']) ?>"></label>
      <label class="ledger-filter-field"><span>消费分类</span><select name="category" aria-label="按类别"><option value="">全部分类</option>
        <?php foreach($labels as $key=>$label): ?><option value="<?= ledger_escape($key) ?>" <?= $filters['category']===$key?'selected':'' ?>><?= ledger_escape($label) ?></option><?php endforeach; ?>
      </select></label>
      <label class="ledger-filter-field"><span>收支方向</span><select name="direction" aria-label="按收支"><option value="">收入和支出</option>
        <option value="EXPENSE" <?= $filters['direction']==='EXPENSE'?'selected':'' ?>>仅支出</option>
        <option value="INCOME" <?= $filters['direction']==='INCOME'?'selected':'' ?>>仅收入</option>
      </select></label>
      <label class="ledger-filter-field"><span>商户名称</span><input name="q" aria-label="搜索商户" placeholder="搜索商户" maxlength="80" value="<?= ledger_escape($filters['q']) ?>"></label>
      <button class="button secondary-button thin" type="submit"><i class="fa-solid fa-magnifying-glass"></i> 筛选</button>
      <a class="button secondary-button thin" href="<?= ledger_escape(ledger_page_link($month,['day'=>'','category'=>'','direction'=>'','q'=>''])) ?>">重置</a>
    </form>
    <?php if($nextOffset!==null): ?><p class="ledger-section-message">单页展示最多 100 条，其他记录可翻页查看。</p><?php endif; ?>
    <?php if(!$items): ?>
      <div class="subscription-container"><div class="subscription"><p class="ledger-empty">暂无交易。你在无感记账 App 中的记录会同步到这里，也可以点击「新增记录」手动添加。</p></div></div>
    <?php else: ?>
    <div class="subscriptions">
      <?php foreach($items as $entry):
         $status=(string)($entry['review_status']??'unverified');
         $id=intval($entry['id']);
         $date=date('m-d H:i',intdiv(intval($entry['timestamp_ms']),1000));
         $category=(string)($entry['category']??'OTHER');
         $icon=match($category) {
           'FOOD'=>'fa-utensils','TRANSPORT'=>'fa-train-subway',
           'SHOPPING'=>'fa-bag-shopping','ENTERTAINMENT'=>'fa-gamepad',
           'HEALTH'=>'fa-heart-pulse','EDUCATION'=>'fa-book',
           'UTILITIES'=>'fa-bolt','SUBSCRIPTION'=>'fa-arrows-rotate',
           default=>'fa-wallet'
         };
      ?>
        <div class="subscription-container ledger-entry" id="transaction-<?= $id ?>">
          <div class="subscription">
            <div class="subscription-main">
              <div class="ledger-mark"><i class="fa-solid <?= ledger_escape($icon) ?>"></i></div>
              <div class="ledger-text">
                <div class="ledger-name"><?= ledger_escape($entry['merchant']?:($sourceNames[$entry['source']]??'交易记录')) ?></div>
                <div class="ledger-details"><?= ledger_escape($date) ?> · <?= ledger_escape($labels[$category]??$category) ?> · <?= ledger_escape($sourceNames[$entry['source']]??$entry['source']) ?></div>
              </div>
              <div class="ledger-amount ledger-money">
                <?= $entry['direction']==='INCOME'?'+':'−' ?><?= ledger_escape(ledger_money(intval($entry['amount_cents']),$entry['currency'])) ?>
              </div>
            </div>
            <details class="ledger-section-message ledger-edit-details">
              <summary>调整分类、金额或排除重复</summary>
              <form method="post" action="expenses-action.php" class="ledger-budget-form" style="margin-bottom:10px">
                <input type="hidden" name="csrf" value="<?=ledger_escape($csrf)?>">
                <input type="hidden" name="month" value="<?=ledger_escape($month)?>">
<?php foreach($filters as $returnKey=>$returnValue): ?>
                <input type="hidden" name="return_<?= ledger_escape($returnKey) ?>" value="<?= ledger_escape($returnValue) ?>">
                <?php endforeach; ?>
                <input type="hidden" name="return_offset" value="<?= intval($offset) ?>">
                <input type="hidden" name="action" value="edit">
                <input type="hidden" name="entry_id" value="<?=$id?>">
                <label>金额（元）<input name="amount" value="<?=ledger_escape(number_format(intval($entry['amount_cents'])/100,2,'.',''))?>" pattern="[0-9]+(\.[0-9]{1,2})?" required></label>
                <label>商户<input name="merchant" maxlength="140" value="<?=ledger_escape($entry['merchant'])?>"></label>
                <select name="category"><?php foreach($labels as $k=>$label): ?><option value="<?=ledger_escape($k)?>" <?=$category===$k?'selected':''?>><?=ledger_escape($label)?></option><?php endforeach; ?></select>
                <button type="submit" class="button secondary-button tiny">保存修正</button>
              </form>
              <form method="post" action="expenses-action.php">
                <input type="hidden" name="csrf" value="<?=ledger_escape($csrf)?>">
                <input type="hidden" name="month" value="<?=ledger_escape($month)?>">
<?php foreach($filters as $returnKey=>$returnValue): ?>
                <input type="hidden" name="return_<?= ledger_escape($returnKey) ?>" value="<?= ledger_escape($returnValue) ?>">
                <?php endforeach; ?>
                <input type="hidden" name="return_offset" value="<?= intval($offset) ?>">
                <input type="hidden" name="action" value="duplicate">
                <input type="hidden" name="entry_id" value="<?=$id?>">
                <button class="button secondary-button tiny" type="submit">确认这笔是重复交易（不计入统计）</button>
              </form>
            </details>
            <div class="ledger-review">
              <small class="ledger-review-state <?= (in_array($status,['excluded','transfer','refund'],true)||($category==='TRANSFER'&&$direction==='EXPENSE'))?'is-excluded':'' ?>"><?= ledger_escape(in_array($status,['excluded','transfer','refund'],true)?'不计入统计':($category==='TRANSFER'?($direction==='INCOME'?'转账入账 · 已计入':'转账 · 不计入'):match($status) {
                'confirmed'=>'已确认', 'excluded'=>'不计入统计', 'transfer'=>'转账 · 不计入',
                'refund'=>'退款 · 不计入', 'possible_duplicate'=>'疑似重复 · 待核对',
                default=>'待核对'
              })) ?></small>
              <form method="post" action="expenses-action.php">
                <input type="hidden" name="csrf" value="<?= ledger_escape($csrf) ?>">
                <input type="hidden" name="month" value="<?= ledger_escape($month) ?>">
<?php foreach($filters as $returnKey=>$returnValue): ?>
                <input type="hidden" name="return_<?= ledger_escape($returnKey) ?>" value="<?= ledger_escape($returnValue) ?>">
                <?php endforeach; ?>
                <input type="hidden" name="return_offset" value="<?= intval($offset) ?>">
                <input type="hidden" name="action" value="review">
                <input type="hidden" name="entry_id" value="<?= $id ?>">
                <select name="status" aria-label="核对 <?= ledger_escape($entry['merchant']??'交易') ?>" onchange="this.form.submit()">
                  <?php foreach(['unverified'=>'待核对','confirmed'=>'已确认','excluded'=>'不计入','transfer'=>'转账'] as $state=>$title): ?>
                    <option value="<?= ledger_escape($state) ?>" <?= $status===$state?'selected':'' ?>><?= ledger_escape($title) ?></option>
                  <?php endforeach; ?>
                  <?php if($status==='possible_duplicate'): ?><option value="possible_duplicate" selected>疑似重复</option><?php endif; ?>
                  <?php if($status==='refund'): ?><option value="refund" selected>已关联退款</option><?php endif; ?>
                </select>
              </form>
            </div>
          </div>
        </div>
      <?php endforeach; ?>
    </div>
    <?php endif; ?>
    <div class="ledger-inline" style="justify-content:center;margin:15px 0">
      <?php if($offset>0): ?><a class="button secondary-button thin" href="<?= ledger_escape(ledger_page_link($month,$filters,max(0,$offset-100))) ?>">上一页</a><?php endif; ?>
      <?php if($nextOffset!==null): ?><a class="button secondary-button thin" href="<?= ledger_escape(ledger_page_link($month,$filters,$nextOffset)) ?>">下一页</a><?php endif; ?>
    </div>
    <p class="ledger-sync">最近一笔记录入库时间（UTC）：<?= ledger_escape($lastSynced?:'暂无记录') ?> · <span id="ledger-check-time">页面读取时间 <?= ledger_escape(date('H:i:s')) ?></span> · <a href="<?= ledger_escape(ledger_page_link($month,$filters,$offset)) ?>">立即刷新</a></p>
  </section>
  </div>
  <?php endif; ?>

  <div id="ledger-backdrop" class="ledger-dialog-backdrop" onclick="ledgerToggle(false)"></div>
  <section id="ledger-dialog" class="subscription-form ledger-dialog" role="dialog" aria-modal="true" aria-label="新增消费记录">
    <header>
      <h3><i class="fa-solid fa-circle-plus"></i> 新增记录</h3>
      <button type="button" class="ledger-close" aria-label="关闭" onclick="ledgerToggle(false)"><i class="fa-solid fa-xmark"></i></button>
    </header>
    <form method="post" action="expenses-action.php">
      <input type="hidden" name="csrf" value="<?= ledger_escape($csrf) ?>">
      <input type="hidden" name="request_id" value="<?= ledger_escape($uuid) ?>">
      <input type="hidden" name="month" value="<?= ledger_escape($month) ?>">
      <input type="hidden" name="action" value="add">
      <div class="ledger-form-grid">
        <div class="form-group"><label for="ledger-amount">金额</label><input id="ledger-amount" name="amount" type="text" inputmode="decimal" pattern="[0-9]+(\.[0-9]{1,2})?" required placeholder="12.50" maxlength="12"></div>
        <div class="form-group"><label for="ledger-currency">币种</label><select id="ledger-currency" name="currency"><option value="CNY">人民币 CNY</option><option value="USD">美元 USD</option><option value="HKD">港币 HKD</option><option value="EUR">欧元 EUR</option><option value="JPY">日元 JPY</option><option value="GBP">英镑 GBP</option></select></div>
        <div class="form-group"><label for="ledger-direction">类型</label><select id="ledger-direction" name="direction"><option value="EXPENSE">支出</option><option value="INCOME">收入</option></select></div>
        <div class="form-group"><label for="ledger-category">分类</label><select id="ledger-category" name="category"><?php foreach($labels as $key=>$label): ?><option value="<?= ledger_escape($key) ?>"><?= ledger_escape($label) ?></option><?php endforeach; ?></select></div>
        <div class="form-group"><label for="ledger-date">交易时间</label><input id="ledger-date" type="datetime-local" name="spent_at" value="<?= ledger_escape(date('Y-m-d\TH:i')) ?>" required><small class="ledger-section-message">保存后会显示在交易时间所属月份。</small></div>
        <div class="form-group"><label for="ledger-merchant">商户或备注</label><input id="ledger-merchant" type="text" name="merchant" maxlength="140" placeholder="食堂、地铁、超市"></div>
      </div>
      <div class="buttons">
        <button type="button" class="button secondary-button" onclick="ledgerToggle(false)">取消</button>
        <button type="submit" class="button"><i class="fa-solid fa-check"></i> 保存记录</button>
      </div>
    </form>
  </section>
</section>
<script>
function ledgerToggle(show) {
  const dialog=document.getElementById('ledger-dialog');
  const backdrop=document.getElementById('ledger-backdrop');
  dialog.classList.toggle('is-open',show);
  backdrop.classList.toggle('open',show);
  document.body.style.overflow=show?'hidden':'';
  if(show) document.getElementById('ledger-amount').focus();
}
document.addEventListener('keydown',e=>{if(e.key==='Escape') ledgerToggle(false)});
// Refresh current view every 60 seconds. The finance DB is read directly on
// each page fetch (no second synchronized transaction database).
async function ledgerRefreshCurrentView(){
  if(document.hidden || document.getElementById('ledger-dialog').classList.contains('is-open')) return;
  const focused=document.activeElement;
  if(focused && ['INPUT','SELECT','TEXTAREA'].includes(focused.tagName)) return;
  try{
    const response=await fetch(location.href,{credentials:'same-origin',cache:'no-store'});
    if(!response.ok || !response.url.includes('/expenses.php')) return;
    const newDoc=new DOMParser().parseFromString(await response.text(),'text/html');
    const oldView=document.getElementById('ledger-live');
    const newView=newDoc.getElementById('ledger-live');
    if(!oldView || !newView) return;
    // A replaceWith destroys ApexCharts SVG state and all JS listeners.
    // Rebuild the entire document only when the ledger really changed,
    // so the versioned script initializes the new month/chart exactly once.
    if(oldView.dataset.revision!==newView.dataset.revision) {
      location.reload();
      return;
    }
    const stamp=document.getElementById('ledger-check-time');
    if(stamp) stamp.textContent='最近自动检查 '+new Date().toLocaleTimeString('zh-CN',{hour12:false});
  }catch(_e) { /* preserve the currently displayed ledger when offline */ }
}
setInterval(ledgerRefreshCurrentView,60000);
</script>
<?php require_once 'includes/footer.php'; ?>
