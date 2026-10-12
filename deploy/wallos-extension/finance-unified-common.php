<?php
/** Shared financial read model for Wallos native pages. No second ledger. */
require_once __DIR__.'/ledger-common.php';
date_default_timezone_set('Asia/Shanghai');

function finance_month(string $value): string {
    if (!preg_match('/^\\d{4}-(0[1-9]|1[0-2])$/D',$value)) throw new InvalidArgumentException('Invalid month');
    return $value;
}
function finance_active_month(): string {
    try { return finance_month((string)($_GET['finance_month']??$_GET['month_key']??date('Y-m'))); }
    catch(Throwable $e){ return date('Y-m'); }
}
function finance_cny(array $rows,string $direction,string $category=''): int {
    $total=0;
    foreach($rows as $r) {
        if(($r['currency']??'')!=='CNY'||($r['direction']??'')!==$direction)continue;
        if($category!=='' && ($r['category']??'')!==$category)continue;
        $total+=(int)($r['total_cents']??0);
    }
    return $total;
}
function finance_categories():array {
    return ['FOOD'=>'餐饮','TRANSPORT'=>'交通','SHOPPING'=>'购物',
      'ENTERTAINMENT'=>'娱乐','HOUSING'=>'住房','UTILITIES'=>'水电通信',
      'HEALTH'=>'医疗','EDUCATION'=>'教育','SUBSCRIPTION'=>'订阅',
      'OTHER'=>'其他','SALARY'=>'工资','TRANSFER'=>'转账'];
}
/**
 * Wallos stores the amount of currency received for one base-CNY unit.
 * Main currency in this installation is CNY. Use the saved rates; do not
 * silently count an unconverted USD/HKD amount as CNY.
 */
function finance_to_cny_cents(float $price,float $storedRate):?int {
    if(!is_finite($price)||$price<0||!is_finite($storedRate)||$storedRate<=0) return null;
    $cents=round($price*100/$storedRate);
    if(!is_finite($cents)||$cents>100000000000)return null;
    return (int)$cents;
}
function finance_subscription_months(int $cycle,int $frequency):?float {
    if($frequency<1)return null;
    return match($cycle) {
        1=>$frequency/(365.2425/12.0),
        2=>($frequency*7)/(365.2425/12.0),
        3=>(float)$frequency,
        4=>12.0*$frequency,
        default=>null
    };
}
function finance_plan_month(SQLite3 $db,int $uid,string $month):array {
    finance_month($month);
    $zone=new DateTimeZone('Asia/Shanghai');
    $start=new DateTimeImmutable($month.'-01 00:00:00',$zone);
    $end=$start->modify('+1 month');
    $query=$db->prepare('SELECT s.id,s.name,s.price,s.next_payment,s.cycle,s.frequency,s.start_date,
                          c.code AS currency, c.rate AS stored_fx_rate
        FROM subscriptions s JOIN currencies c ON c.id=s.currency_id
        WHERE s.user_id=:uid AND s.inactive=0 AND s.next_payment IS NOT NULL
        ORDER BY s.id');
    if(!$query)throw new RuntimeException('Subscription query unavailable');
    $query->bindValue(':uid',$uid,SQLITE3_INTEGER);
    $rows=$query->execute();
    if(!$rows)throw new RuntimeException('Subscription read unavailable');
    $fxRefreshDate=$db->querySingle('SELECT date FROM last_exchange_update WHERE user_id='.(int)$uid.' LIMIT 1');
    $events=[];$dueCny=0;$unconverted=[];$monthlyExact=0.0;
    $monthlyItems=[];$rates=[];$missingMonthly=[];
    while($r=$rows->fetchArray(SQLITE3_ASSOC)) {
        try {$date=new DateTimeImmutable((string)$r['next_payment'],$zone);}
        catch(Throwable $e){continue;}
        $cycle=(int)$r['cycle'];
        $frequency=(int)$r['frequency'];
        if($frequency<1||$frequency>365)continue;
        $currency=(string)$r['currency'];
        $price=(float)$r['price'];
        $rate=filter_var($r['stored_fx_rate'],FILTER_VALIDATE_FLOAT);
        $rate=$rate===false||$rate<=0?0.0:(float)$rate;
        $cny=finance_to_cny_cents($price,$rate);
        if($rate>0)$rates[$currency]=$rate;
        $months=finance_subscription_months($cycle,$frequency);
        $originalCents=(int)round($price*100);
        $monthCny=$months===null||$cny===null?null:(int)round($price*100/($rate*$months));
        // Sum unrounded amounts once; this avoids systematic per-subscription rounding.
        if($months!==null) {
            if($cny===null)$missingMonthly[]=$currency;
            else $monthlyExact+=$price*100/($rate*$months);
        }
        $monthlyItems[]=[
            'id'=>(int)$r['id'],'name'=>(string)$r['name'],'currency'=>$currency,
            'price_cents'=>$originalCents,'converted_cny_cents'=>$cny,
            'monthly_cny_cents'=>$monthCny,'cycle'=>$cycle,'frequency'=>$frequency,
            'next_payment'=>$date->format('Y-m-d'),'fx_rate'=>$rate>0?$rate:null
        ];
        $interval=match($cycle) {
            1=>new DateInterval('P'.$frequency.'D'),
            2=>new DateInterval('P'.$frequency.'W'),
            3=>new DateInterval('P'.$frequency.'M'),
            4=>new DateInterval('P'.$frequency.'Y'),
            5=>null,
            default=>null
        };
        if($cycle!==5&&$interval===null)continue;
        $limit=0;
        if($interval!==null) {
            while($date >= $end && $limit++ < 600) {
                $prev=$date->sub($interval);
                if($prev >= $date)break;
                $date=$prev;
            }
            while($date < $start && $limit++ < 1200) {
                $next=$date->add($interval);
                if($next <= $date)break;
                $date=$next;
            }
        }
        while($date >= $start && $date < $end && $limit++ < 1250) {
            if(!empty($r['start_date'])) {
                try {
                    $begin=new DateTimeImmutable((string)$r['start_date'],$zone);
                    if($date<$begin) {if($interval===null)break;$date=$date->add($interval);continue;}
                }catch(Throwable $e){}
            }
            $event=['date'=>$date->format('Y-m-d'),'id'=>(int)$r['id'],
                'name'=>(string)$r['name'],'cents'=>$originalCents,'currency'=>$currency,
                'cny_cents'=>$cny,'rate'=>$rate>0?$rate:null];
            $events[]=$event;
            if($cny!==null)$dueCny+=$cny;
            else $unconverted[$currency]=($unconverted[$currency]??0)+$originalCents;
            if($interval===null)break;
            $next=$date->add($interval);
            if($next <= $date)break;
            $date=$next;
        }
    }
    $rows->finalize();
    return [
        'events'=>$events,
        'due_cny_cents'=>$unconverted?null:$dueCny,
        'convertible_due_cny_cents'=>$dueCny,
        'monthly_equivalent_cny_cents'=>$missingMonthly?null:(int)round($monthlyExact),
        'convertible_monthly_equivalent_cny_cents'=>(int)round($monthlyExact),
        'monthly_items'=>$monthlyItems,
        'fx_rates'=>$rates,
        'fx_last_updated'=>is_string($fxRefreshDate)?$fxRefreshDate:null,
        'other_currency_cents'=>$unconverted,
        'monthly_unconverted_currencies'=>array_values(array_unique($missingMonthly))
    ];
}
function finance_unified_model(SQLite3 $db,int $uid,string $month):array {
    $month=finance_month($month);
    $raw=ledger_request('GET','month?month='.$month);
    $plan=finance_plan_month($db,$uid,$month);
    $income=finance_cny($raw['totals']??[],'INCOME');
    $expense=finance_cny($raw['totals']??[],'EXPENSE');
    $actualSubs=finance_cny($raw['totals']??[],'EXPENSE','SUBSCRIPTION');
    $prevPeriodExpense=finance_cny($raw['previous_same_period_totals']??($raw['previous_month_totals']??[]),'EXPENSE');
    $monthIsCurrent=($month===date('Y-m'));
    $comparisonLabel=$monthIsCurrent?'上月同期':'上月完整月份';
    $prevIncome=finance_cny($raw['previous_month_totals']??[],'INCOME');
    $prevExpense=finance_cny($raw['previous_month_totals']??[],'EXPENSE');
    $budget=$raw['budget_cny_cents']??null;
    $budget=$budget===null?null:(int)$budget;
    $days=(int)date('t',strtotime($month.'-01'));
    $included=[];$excluded=[];$dayRevenue=[];
    foreach(($raw['daily_totals']??[]) as $r) {
        if(($r['currency']??'')!=='CNY')continue;
        $date=(string)($r['day']??'');
        if(($r['direction']??'')==='EXPENSE')$included[$date]=($included[$date]??0)+(int)$r['total_cents'];
        if(($r['direction']??'')==='INCOME')$dayRevenue[$date]=($dayRevenue[$date]??0)+(int)$r['total_cents'];
    }
    foreach(($raw['daily_excluded_totals']??[]) as $r) {
        if(($r['currency']??'')!=='CNY'||($r['direction']??'')!=='EXPENSE')continue;
        $date=(string)($r['day']??'');
        $excluded[$date]=($excluded[$date]??0)+(int)$r['total_cents'];
    }
    $scheduled=[];
    foreach($plan['events'] as $event) {
        $day=$event['date'];
        $scheduled[$day][]=$event;
    }
    $calendar=[];
    for($i=1;$i<=$days;$i++) {
        $date=sprintf('%s-%02d',$month,$i);
        $calendar[]=['date'=>$date,'expense_cents'=>$included[$date]??0,
            'income_cents'=>$dayRevenue[$date]??0,'excluded_cents'=>$excluded[$date]??0,
            'subscriptions'=>$scheduled[$date]??[]];
    }
    $categories=[];
    foreach(($raw['totals']??[]) as $r) {
        if(($r['currency']??'')==='CNY'&&($r['direction']??'')==='EXPENSE')
            $categories[(string)$r['category']]=($categories[(string)$r['category']]??0)+(int)$r['total_cents'];
    }
    arsort($categories);
    return [
        'month'=>$month,'timezone'=>'Asia/Shanghai','currency'=>'CNY',
        'income_cents'=>$income,'expense_cents'=>$expense,'net_cents'=>$income-$expense,
        'budget_cents'=>$budget,
        'remaining_cents'=>$budget===null?null:$budget-$expense,
        'previous_income_cents'=>$prevIncome,'previous_expense_cents'=>$prevExpense,
        'previous_same_period_expense_cents'=>$prevPeriodExpense,
        'comparison_label'=>$comparisonLabel,
        'actual_subscription_cents'=>$actualSubs,
        'actual_subscription_share_pct'=>$expense>0?round(100*$actualSubs/$expense,1):null,
        'planned_subscription_cents'=>$plan['due_cny_cents'],
        'monthly_subscription_equivalent_cents'=>$plan['monthly_equivalent_cny_cents'],
        'convertible_planned_subscription_cents'=>$plan['convertible_due_cny_cents'],
        'convertible_monthly_subscription_equivalent_cents'=>$plan['convertible_monthly_equivalent_cny_cents'],
        'subscription_monthly_items'=>$plan['monthly_items'],
        'subscription_fx_rates'=>$plan['fx_rates'],
        'subscription_fx_last_updated'=>$plan['fx_last_updated'],
        'subscription_monthly_unconverted'=>$plan['monthly_unconverted_currencies'],
        'planned_budget_share_pct'=>$budget!==null&&$budget>0&&$plan['due_cny_cents']!==null?round(100*$plan['due_cny_cents']/$budget,1):null,
        'other_currency_plans'=>$plan['other_currency_cents'],
        'category_totals'=>$categories,'category_labels'=>finance_categories(),
        'days'=>$calendar,'subscription_events'=>$plan['events'],
        'last_synced'=>$raw['last_synced_at_utc']??null
    ];
}
function finance_json(array $model):string {
    return json_encode($model,JSON_HEX_TAG|JSON_HEX_AMP|JSON_HEX_APOS|JSON_HEX_QUOT|JSON_UNESCAPED_UNICODE|JSON_THROW_ON_ERROR);
}
function finance_money(?int $cents):string {
    return $cents===null?'未设置':(($cents<0?'−':'').'¥'.number_format(abs($cents)/100,2,'.',','));
}
function finance_render_panel(string $view,array $model):void {
    if(empty($_SESSION['finance_csrf']))$_SESSION['finance_csrf']=bin2hex(random_bytes(32));
    $month=$model['month'];
    $income=finance_money($model['income_cents']);
    $expense=finance_money($model['expense_cents']);
    $net=finance_money($model['net_cents']);
    $budget=finance_money($model['budget_cents']);
    $remaining=finance_money($model['remaining_cents']);
    $actual=finance_money($model['actual_subscription_cents']);
    $planned=$model['planned_subscription_cents']===null?'汇率缺失':finance_money($model['planned_subscription_cents']);
    ?>
    <link rel="stylesheet" href="finance-unified.css?v=<?=filemtime(__DIR__.'/finance-unified.css')?>">
    <input type="hidden" data-finance-csrf value="<?=ledger_escape($_SESSION['finance_csrf'])?>">
    <section class="stats-section finance-native" data-finance-view="<?=ledger_escape($view)?>" data-finance-month="<?=ledger_escape($month)?>">
      <div class="finance-native-heading">
        <h2><?= $view==='dashboard'?'财务总览':($view==='calendar'?'每日真实消费':'综合财务分析') ?></h2>
        <div class="finance-header-actions">
          <a class="finance-ledger-shortcut" href="expenses.php?month=<?=ledger_escape($month)?>">完整流水 / 补记</a>
          <label>财务月份 <input type="month" class="finance-month" value="<?=ledger_escape($month)?>" aria-label="选择财务月份"></label>
        </div>
      </div>
      <p class="finance-hint">真实交易与订阅计划分别计算 · 人民币 · 北京时间</p>
      <?php if($view==='stats'): ?>
        <div class="finance-tabbar" role="tablist" aria-label="统计视图">
          <button type="button" class="finance-tab is-current" data-finance-tab="overview">综合财务</button>
          <button type="button" class="finance-tab" data-finance-tab="categories">消费分析</button>
          <button type="button" class="finance-tab" data-finance-tab="subscriptions">订阅分析</button>
        </div>
      <?php endif; ?>
      <?php if($view!=='calendar'): ?>
      <div class="finance-panel" data-finance-panel="overview">
        <div class="finance-metric-grid">
          <button type="button" class="finance-metric" data-finance-detail="expense"><small>本月实际支出</small><strong data-finance-value="expense_cents"><?= $expense ?></strong></button>
          <button type="button" class="finance-metric" data-finance-detail="income"><small>本月已记录收入（含转账入账）</small><strong data-finance-value="income_cents"><?= $income ?></strong></button>
          <div class="finance-metric"><small>净现金流</small><strong data-finance-value="net_cents"><?= $net ?></strong></div>
          <div class="finance-metric"><small>预算剩余</small><strong data-finance-value="remaining_cents"><?= $remaining ?></strong></div>
        </div>
        <div class="finance-budget">
          <div class="finance-line"><strong>Finance 日常消费预算</strong><span data-finance-budget-caption><?= $budget ?> · 已用 <?= $model['budget_cents']>0?round($model['expense_cents']/$model['budget_cents']*100,1).'%' :'未设置' ?></span></div>
          <div class="finance-progress"><div data-finance-budget-bar style="width:<?= $model['budget_cents']>0?min(100,round($model['expense_cents']/$model['budget_cents']*100,1)):0 ?>%"></div></div>
          <form class="finance-budget-form" data-finance-budget-form>
            <label>调整预算（元）<input type="number" min="0" step=".01" name="budget" value="<?= $model['budget_cents']===null?'':number_format($model['budget_cents']/100,2,'.','') ?>" placeholder="例如 2000"></label>
            <button type="submit">保存</button>
          </form>
        </div>
        <?php if($view==='stats'): ?>
        <div class="finance-comparison"><strong>与上月对比</strong>
          <span>本月实际支出 <b data-finance-value="expense_cents"><?= $expense ?></b> · <span data-finance-comparison-label><?=ledger_escape($model['comparison_label'])?></span> <b data-finance-value="previous_same_period_expense_cents"><?=finance_money($model['previous_same_period_expense_cents'])?></b></span>
          <small>进行中月份按同一日数对比，已结束月份对比完整月份；金额均不含已排除交易。</small>
        </div>
        <?php endif; ?>
        <div class="finance-chart-section"><div class="finance-line">
          <strong>消费趋势</strong><div class="finance-switch"><button type="button" data-finance-range="7">7 天</button><button type="button" class="is-current" data-finance-range="30">整月</button></div>
        </div><div class="finance-chart" role="group" aria-label="人民币实际消费每日金额柱状图与日期选择"></div></div>
      </div>
      <?php endif; ?>
      <?php if($view==='stats'): ?>
      <div class="finance-panel" data-finance-panel="categories" hidden>
        <h3>本月消费类别排行</h3>
        <div class="finance-categories"></div>
        <p class="finance-hint">点击分类查看对应交易；转账和已排除项目不计入。</p>
      </div>
      <?php endif; ?>
      <?php if($view!=='calendar'): ?>
      <div class="finance-panel" data-finance-panel="subscriptions" <?= $view==='stats'?'hidden':'' ?>>
        <div class="finance-metric-grid">
          <div class="finance-metric"><small>实际订阅支出</small><strong data-finance-value="actual_subscription_cents"><?= $actual ?></strong><span>占实际支出 <b data-finance-share="actual"><?= $model['actual_subscription_share_pct']===null?'不可计算':$model['actual_subscription_share_pct'].'%' ?></b></span></div>
          <div class="finance-metric"><small>本月计划到期</small><strong data-finance-value="planned_subscription_cents"><?= $planned ?></strong><span>占预算 <b data-finance-share="planned"><?= $model['planned_budget_share_pct']===null?'未设预算':$model['planned_budget_share_pct'].'%' ?></b></span></div>
        </div>
        <div class="finance-comparison"><strong>当前订阅折合月均成本</strong>
          <span data-finance-value="monthly_subscription_equivalent_cents"><?= $model['monthly_subscription_equivalent_cents']===null?'汇率缺失':finance_money($model['monthly_subscription_equivalent_cents']) ?></span>
          <small>按当前周期折算，非实际扣费；一次性订阅不折算。历史月份也仅表示当前配置的月均负担。</small>
        </div>
        <p class="finance-hint" data-finance-fx-status>Wallos 汇率数据读取中</p>
        <p class="finance-hint">实际订阅支出只统计 Finance 中归类为“订阅”的有效账单。未分类的已扣款不会自动算作订阅；如需计入，可在流水中调整分类。</p>
        <h3 class="finance-list-heading">全部有效订阅 · 月均成本明细</h3>
        <div class="finance-subscription-monthly"></div>
        <h3 class="finance-list-heading">本月计划续费</h3>
        <div class="finance-subscriptions"></div>
        <p class="finance-hint">外币按 Wallos 已保存汇率换算为人民币，仅供预算参考，不是银行卡实际结算价。计划续费不等于已扣款；实际订阅支出仅来自 Finance 中有效的订阅分类交易。若汇率缺失会显示“无法完整计算”，不把缺失项目当作零。</p>
      </div>
      <?php endif; ?>
      <?php if($view==='calendar'): ?>
      <div class="finance-metric-grid">
        <div class="finance-metric"><small>本月实际支出</small><strong data-finance-value="expense_cents"><?= $expense ?></strong></div>
        <div class="finance-metric"><small>本月计划订阅</small><strong data-finance-value="planned_subscription_cents"><?= $planned ?></strong></div>
      </div>
      <p class="finance-hint">原生订阅日历继续保留。金额显示 Finance 中已计入的真实支出，点击日期可直接编辑当日流水。</p>
      <?php endif; ?>
    </section>
    <script type="application/json" id="finance-data-<?=ledger_escape($view)?>"><?=finance_json($model)?></script>
    <?php
}
