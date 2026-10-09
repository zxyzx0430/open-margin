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
$lastSynced=null;
$monthCount=0;
$budgetCny=null;
$nextOffset=null;
$labels=[
    'FOOD'=>'餐饮','TRANSPORT'=>'交通','SHOPPING'=>'购物','ENTERTAINMENT'=>'娱乐',
    'HOUSING'=>'住房','UTILITIES'=>'水电通信','HEALTH'=>'医疗','EDUCATION'=>'学习',
    'SUBSCRIPTION'=>'订阅','OTHER'=>'其他','SALARY'=>'工资','TRANSFER'=>'转账'
];
$error='';
try {
    $parameters=array_filter($filters,static fn($v)=>$v!=='');
    if ($offset>0) $parameters['offset']=$offset;
    $query='month?month='.$month;
    foreach($parameters as $key=>$val) {
        $query.='&'.$key.'='.rawurlencode((string)$val);
    }
    $result=ledger_request('GET',$query);
    $items=$result['items'] ?? [];
    $totals=$result['totals'] ?? [];
    $todayTotals=$result['today_totals'] ?? [];
    $weekTotals=$result['last7_totals'] ?? [];
    $prevMonthTotals=$result['previous_month_totals'] ?? [];
    $dailyTotals=$result['daily_totals'] ?? [];
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
$lastDay=(int)date('t',strtotime($month.'-01'));
$maxDaily=max([1,...array_values($plot)]);
$revision=hash('sha256',json_encode([$monthCount,$lastSynced,$totals,$dailyTotals,$budgetCny,$filters,$offset],JSON_UNESCAPED_UNICODE));
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
.wallos-ledger .ledger-chart{display:flex;gap:3px;align-items:end;width:100%;height:152px;padding:12px 0 4px;box-sizing:border-box;overflow-x:auto}
.wallos-ledger .ledger-chart-day{flex:1;min-width:8px;max-width:36px;height:100%;display:flex;flex-direction:column;justify-content:flex-end;align-items:center;text-decoration:none;color:var(--text-muted)}
.wallos-ledger .ledger-chart-bar{width:75%;border-radius:4px 4px 0 0;min-height:2px;background:var(--main-color);opacity:.85;display:block}
.wallos-ledger .ledger-chart-day:hover .ledger-chart-bar{opacity:1}
.wallos-ledger .ledger-chart-label{display:block;height:20px;font-size:10px;line-height:20px}
.wallos-ledger .ledger-search-form{display:flex;flex-wrap:wrap;gap:10px;align-items:end;margin:8px 0 16px}
.wallos-ledger .ledger-search-form input,.wallos-ledger .ledger-search-form select{max-width:210px;min-width:115px;padding:11px;border-radius:9px;background:var(--input-background-color);color:var(--text-color);border:1px solid var(--input-border-color)}
.wallos-ledger .ledger-sync{font-size:12px;color:var(--text-muted);margin:5px 0 16px}
.wallos-ledger .ledger-budget-track{width:100%;height:10px;border-radius:7px;overflow:hidden;background:var(--box-border-color)}
.wallos-ledger .ledger-budget-used{height:100%;border-radius:7px;background:var(--main-color)}
.wallos-ledger .ledger-budget-form{display:flex;flex-wrap:wrap;align-items:end;gap:9px;margin-top:13px}
.wallos-ledger .ledger-budget-form label{flex:1 1 100%}
.wallos-ledger .ledger-budget-form input{flex:1 1 160px;height:48px;background:var(--input-background-color);color:var(--text-color);border:1px solid var(--input-border-color);border-radius:9px;padding:10px;font:inherit}

@media(max-width:768px){.wallos-ledger .ledger-search-form input,.wallos-ledger .ledger-search-form select{max-width:none;flex:1 1 125px;min-width:0}.wallos-ledger .ledger-chart{gap:2px}.wallos-ledger .ledger-chart-label{font-size:9px}}
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
  <p class="ledger-intro">此页记录已经发生的消费；Wallos 的订阅金额只是预计支出，不会自动计入消费流水。通知识别可能漏记或重复，请定期核对。</p>
  <?php if($error): ?><div class="error-box" role="alert"><i class="fa-solid fa-circle-exclamation"></i> <?= ledger_escape($error) ?></div><?php endif; ?>
  <?php if ($notice==='added'): ?><div class="ledger-section-message" role="status"><i class="fa-solid fa-circle-check"></i> 已成功记录交易。</div>
  <?php elseif ($notice==='reviewed'): ?><div class="ledger-section-message" role="status"><i class="fa-solid fa-circle-check"></i> 已更新核对状态。</div>
  <?php elseif ($notice==='budget'): ?><div class="ledger-section-message" role="status"><i class="fa-solid fa-circle-check"></i> 本月消费预算已保存。</div>
  <?php elseif ($notice==='failed'): ?><div class="error-box" role="alert">未能保存，请检查金额与日期。</div><?php endif; ?>

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
      <p class="ledger-section-message">均为已录入交易，未包含尚未扣款的订阅；不同币种分别列示，不使用未经核实的汇率。今天、近 7 天按北京时间计算。</p>
      <p class="ledger-section-message">上月人民币支出：<?= ledger_escape(ledger_money($previousCny,'CNY')) ?>；本月已记：<?= ledger_escape(ledger_money($monthCny,'CNY')) ?>。<?= $month===date('Y-m')?'本月尚未结束，不直接按月度总额评价变化。':'' ?></p>
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
        <form method="post" action="expenses-action.php" class="ledger-budget-form">
          <input type="hidden" name="csrf" value="<?= ledger_escape($csrf) ?>">
          <input type="hidden" name="action" value="budget">
          <input type="hidden" name="month" value="<?= ledger_escape($month) ?>">
          <label for="ledger-budget-amount" class="ledger-section-message">本月预算金额（CNY，留空可清除）</label>
          <input id="ledger-budget-amount" name="budget_amount" type="text" inputmode="decimal"
               pattern="[0-9]+(\.[0-9]{1,2})?" placeholder="例如 1500.00"
               maxlength="12" value="<?= $budgetCny!==null?ledger_escape(number_format($budgetCny/100,2,'.','')):'' ?>">
          <button type="submit" class="button secondary-button thin">保存预算</button>
        </form>
      </div>
    </section>
    <section class="stats-section">
      <h2>每日消费趋势 · <?= ledger_escape($month) ?></h2>
      <div class="ledger-chart" role="img" aria-label="本月人民币每天的支出柱状图">
       <?php for($n=1;$n<=$lastDay;$n++):
          $dateKey=sprintf('%s-%02d',$month,$n);
          $cents=intval($plot[$dateKey]??0);
          $height=$cents>0?max(5,round(100*$cents/$maxDaily)):2;
       ?>
         <a class="ledger-chart-day" href="<?= ledger_escape(ledger_page_link($month,['day'=>$dateKey,'category'=>'','direction'=>'','q'=>''])) ?>"
           title="<?= ledger_escape($dateKey.' · '.ledger_money($cents,'CNY')) ?>" aria-label="<?= ledger_escape($dateKey.'：'.ledger_money($cents,'CNY')) ?>">
           <span class="ledger-chart-bar" style="height:<?= $height ?>%"></span>
           <span class="ledger-chart-label"><?= $n ?></span>
         </a>
       <?php endfor; ?>
      </div>
      <p class="ledger-section-message">仅显示人民币支出；点击日期查看当天交易。零消费日也会显示。</p>
    </section>
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
      <a href="integration.php?month=<?= ledger_escape($month) ?>" class="button secondary-button tiny"><i class="fa-solid fa-link"></i> 订阅对账与分析</a>
    </div>
    <form method="get" action="expenses.php" class="ledger-search-form" aria-label="筛选消费流水">
      <input type="hidden" name="month" value="<?= ledger_escape($month) ?>">
      <input type="date" name="day" aria-label="按日期" value="<?= ledger_escape($filters['day']) ?>">
      <select name="category" aria-label="按类别"><option value="">全部分类</option>
        <?php foreach($labels as $key=>$label): ?><option value="<?= ledger_escape($key) ?>" <?= $filters['category']===$key?'selected':'' ?>><?= ledger_escape($label) ?></option><?php endforeach; ?>
      </select>
      <select name="direction" aria-label="按收支"><option value="">收入和支出</option>
        <option value="EXPENSE" <?= $filters['direction']==='EXPENSE'?'selected':'' ?>>仅支出</option>
        <option value="INCOME" <?= $filters['direction']==='INCOME'?'selected':'' ?>>仅收入</option>
      </select>
      <input name="q" aria-label="搜索商户" placeholder="搜索商户" maxlength="80" value="<?= ledger_escape($filters['q']) ?>">
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
        <div class="subscription-container ledger-entry">
          <div class="subscription">
            <div class="subscription-main">
              <div class="ledger-mark"><i class="fa-solid <?= ledger_escape($icon) ?>"></i></div>
              <div class="ledger-text">
                <div class="ledger-name"><?= ledger_escape($entry['merchant']?:($labels[$category]??'未填写')) ?></div>
                <div class="ledger-details"><?= ledger_escape($date) ?> · <?= ledger_escape($labels[$category]??$category) ?> · <?= ledger_escape($entry['source']) ?></div>
              </div>
              <div class="ledger-amount ledger-money">
                <?= $entry['direction']==='INCOME'?'+':'−' ?><?= ledger_escape(ledger_money(intval($entry['amount_cents']),$entry['currency'])) ?>
              </div>
            </div>
            <details class="ledger-section-message" style="margin:0 12px 0 60px">
              <summary>修改交易 / 排除重复</summary>
              <form method="post" action="expenses-action.php" class="ledger-budget-form" style="margin-bottom:10px">
                <input type="hidden" name="csrf" value="<?=ledger_escape($csrf)?>">
                <input type="hidden" name="month" value="<?=ledger_escape($month)?>">
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
                <input type="hidden" name="action" value="duplicate">
                <input type="hidden" name="entry_id" value="<?=$id?>">
                <button class="button secondary-button tiny" type="submit">确认这笔是重复交易（不计入统计）</button>
              </form>
            </details>
            <div class="ledger-review">
              <small><?= $status==='possible_duplicate'?'⚠ 疑似重复':'交易状态' ?></small>
              <form method="post" action="expenses-action.php">
                <input type="hidden" name="csrf" value="<?= ledger_escape($csrf) ?>">
                <input type="hidden" name="month" value="<?= ledger_escape($month) ?>">
                <input type="hidden" name="action" value="review">
                <input type="hidden" name="entry_id" value="<?= $id ?>">
                <select name="status" aria-label="核对 <?= ledger_escape($entry['merchant']??'交易') ?>" onchange="this.form.submit()">
                  <?php foreach(['unverified'=>'待核对','confirmed'=>'已确认','excluded'=>'不计入','transfer'=>'转账','refund'=>'退款'] as $state=>$title): ?>
                    <option value="<?= ledger_escape($state) ?>" <?= $status===$state?'selected':'' ?>><?= ledger_escape($title) ?></option>
                  <?php endforeach; ?>
                  <?php if($status==='possible_duplicate'): ?><option value="possible_duplicate" selected>疑似重复</option><?php endif; ?>
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
        <div class="form-group"><label for="ledger-date">交易时间</label><input id="ledger-date" type="datetime-local" name="spent_at" value="<?= ledger_escape(date('Y-m-d\TH:i')) ?>" required></div>
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
setInterval(async ()=>{
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
    if(oldView.dataset.revision!==newView.dataset.revision) oldView.replaceWith(newView);
    const stamp=document.getElementById('ledger-check-time');
    if(stamp) stamp.textContent='最近自动检查 '+new Date().toLocaleTimeString('zh-CN',{hour12:false});
  }catch(_e) { /* preserve the currently displayed ledger when offline */ }
},60000);
</script>
<?php require_once 'includes/footer.php'; ?>
