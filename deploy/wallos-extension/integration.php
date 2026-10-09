<?php
/** Unified subscription reconciliation dashboard; native Wallos UI classes. */
ob_start();
require_once 'includes/header.php';
require_once 'ledger-common.php';
ledger_require_owner();
ob_end_flush();
$options=@parse_ini_file('/run/secrets/wallos-ledger.env',false,INI_SCANNER_RAW);
date_default_timezone_set($options['FINANCE_TIMEZONE']??'Asia/Shanghai');
$month=$_GET['month']??date('Y-m');
if(!is_string($month)||!preg_match('/^\d{4}-(0[1-9]|1[0-2])$/D',$month))$month=date('Y-m');
if(empty($_SESSION['finance_csrf']))$_SESSION['finance_csrf']=bin2hex(random_bytes(32));
$csrf=$_SESSION['finance_csrf'];
$overview=[];$trends=[];$discovery=[];$rules=[];$error='';
try {
    $overview=ledger_request('GET','integration?month='.$month);
    $trends=ledger_request('GET','integration/trends?months=12');
    $discovery=ledger_request('GET','integration/discover?months=12');
    $rules=ledger_request('GET','integration/rules');
} catch(Throwable $e) {
    $error='订阅与交易的综合分析暂时不可用。';
    error_log('[Wallos integration] '.get_class($e));
}
function integ_cny($cents):string{return ledger_money((int)$cents,'CNY');}
?>
<style>
.wallos-integration{padding-bottom:85px}
.wallos-integration .integ-summary{display:flex;flex-wrap:wrap;gap:15px;margin:10px 0 20px}
.wallos-integration .integ-summary .statistic{flex:1 1 145px}
.wallos-integration .integ-table{width:100%;border-collapse:collapse}
.wallos-integration .integ-table td,.wallos-integration .integ-table th{padding:11px 9px;text-align:left;border-bottom:1px solid var(--box-border-color);font-size:13px}
.wallos-integration .integ-table th{font-weight:600;color:var(--text-muted)}
.wallos-integration .integ-scroll{overflow-x:auto}
.wallos-integration .integ-form{display:flex;align-items:center;flex-wrap:wrap;gap:8px}
.wallos-integration .integ-form input,.wallos-integration .integ-form select{max-width:220px;min-width:94px;min-height:36px;border:1px solid var(--input-border-color);background:var(--input-background-color);color:var(--text-color);border-radius:8px;padding:6px}
.wallos-integration .integ-list{display:grid;gap:10px;margin:12px 0 20px}
.wallos-integration .integ-item{border:1px solid var(--box-border-color);border-radius:10px;padding:13px;display:flex;justify-content:space-between;align-items:center;gap:12px;flex-wrap:wrap}
.wallos-integration .integ-note{font-size:13px;color:var(--text-muted)}
.wallos-integration .integ-heading{display:flex;gap:12px;align-items:center;justify-content:space-between;flex-wrap:wrap}
.wallos-integration h2{margin:25px 0 12px}
.wallos-integration .integ-tag{font-size:12px;border-radius:6px;padding:4px 7px;background:var(--input-background-color)}
</style>
<section class="contain dashboard wallos-integration">
  <div class="integ-heading">
    <div><h1>订阅 × 消费对账</h1><p class="integ-note">Wallos 提供预计订阅，Android 账本提供真实扣款；不重复计数</p></div>
    <div class="integ-form">
      <form method="get" action="integration.php" class="integ-form"><input type="month" name="month" value="<?=ledger_escape($month)?>"><button class="button secondary-button thin">查看</button></form>
      <a class="button secondary-button thin" href="expenses.php?month=<?=ledger_escape($month)?>">日常消费</a>
      <a class="button secondary-button thin" href="subscriptions.php">Wallos 订阅</a>
    </div>
  </div>
  <?php if($error): ?><div class="error-box" role="alert"><?=ledger_escape($error)?></div><?php endif; ?>
  <?php if(($_GET['notice']??'')==='saved'): ?><p class="ledger-section-message" role="status">更改已保存，请核对结果。</p><?php elseif(($_GET['notice']??'')==='failed'): ?><div class="error-box" role="alert">未能保存，可能是金额、日期、重复关联或订阅服务不可用。</div><?php endif; ?>
  <?php if($overview): ?>
  <section class="stats-section">
    <h2>综合月度概览 · 人民币</h2>
    <div class="statistics integ-summary">
      <div class="statistic"><span><?=ledger_escape(integ_cny($overview['net_actual_spend_cny_cents']??0))?></span><div class="title">实际净支出</div></div>
      <div class="statistic"><span><?=ledger_escape(integ_cny($overview['expected_unpaid_cny_cents']??0))?></span><div class="title">尚待扣费预测</div></div>
      <div class="statistic"><span><?=ledger_escape(integ_cny($overview['forecast_total_cny_cents']??0))?></span><div class="title">本月预计总开销</div></div>
      <div class="statistic"><span><?=isset($overview['budget_remaining_after_forecast_cny_cents'])?ledger_escape(integ_cny($overview['budget_remaining_after_forecast_cny_cents'])):'未设置'?></span><div class="title">预算预计结余</div></div>
      <div class="statistic"><span><?=intval($overview['linked_count']??0)?></span><div class="title">已匹配期数</div></div>
      <div class="statistic"><span><?=intval($overview['pending_count']??0)?></span><div class="title">待匹配期数</div></div>
    </div>
    <p class="integ-note">净支出＝真实支出－已关联退款。预计总开销＝净支出＋未付款的 CNY 订阅预测；同一笔已匹配交易只计一次。币种分开展示，不进行推测汇率换算。</p>
  </section>
  <section class="stats-section">
    <h2>订阅周期付款状态</h2>
    <?php if(empty($overview['subscriptions'])): ?><p class="integ-note">选中月份没有可识别的 Wallos 周期。可以先去订阅管理中添加服务。</p><?php else: ?>
    <div class="integ-scroll"><table class="integ-table">
      <thead><tr><th>会员 / 本期日期</th><th>预计扣款</th><th>实际扣款</th><th>状态 / 操作</th></tr></thead>
      <tbody><?php foreach($overview['subscriptions'] as $sub): ?>
        <tr>
          <td><b><?=ledger_escape($sub['name'])?></b><div class="integ-note"><?=ledger_escape($sub['cycle_key'])?></div></td>
          <td><?=ledger_escape(ledger_money((int)$sub['amount_cents'],$sub['currency']))?></td>
          <td><?=isset($sub['paid_cents'])?ledger_escape(ledger_money((int)$sub['paid_cents'],$sub['currency'])):'—'?></td>
          <td>
            <?php if($sub['status']==='matched'): ?>
              <span class="integ-tag">已关联真实交易 #<?=intval($sub['transaction_id'])?></span>
              <form method="post" action="expenses-action.php" class="integ-form">
                <input type="hidden" name="csrf" value="<?=ledger_escape($csrf)?>">
                <input type="hidden" name="month" value="<?=ledger_escape($month)?>">
                <input type="hidden" name="action" value="unlink">
                <input type="hidden" name="tx_id" value="<?=intval($sub['transaction_id'])?>">
                <button class="button secondary-button tiny" type="submit">解除关联</button>
              </form>
            <?php else: ?><span class="integ-tag">尚未匹配</span><?php endif; ?>
          </td>
        </tr>
      <?php endforeach; ?></tbody>
    </table></div><?php endif; ?>
  </section>
  <section class="stats-section">
    <h2>可能属于订阅的真实扣款</h2>
    <p class="integ-note">根据商户、金额、时间生成建议。不会自动把普通消费当作会员扣费；只有你确认后才会建立关联。</p>
    <div class="integ-list">
    <?php foreach(($overview['suggestions']??[]) as $s): ?>
      <div class="integ-item">
        <div><b><?=ledger_escape($s['merchant']?:'未识别商户')?> · <?=ledger_escape(ledger_money((int)$s['paid_cents'],$s['currency']))?></b>
          <div class="integ-note">交易 #<?=intval($s['tx_id'])?> → <?=ledger_escape($s['subscription_name'])?> · <?=ledger_escape($s['cycle_key'])?> · <?=ledger_escape($s['confidence'])?> 可信度</div></div>
        <form method="post" action="expenses-action.php" class="integ-form">
          <input type="hidden" name="csrf" value="<?=ledger_escape($csrf)?>"><input type="hidden" name="month" value="<?=ledger_escape($month)?>">
          <input type="hidden" name="action" value="link"><input type="hidden" name="tx_id" value="<?=intval($s['tx_id'])?>">
          <input type="hidden" name="subscription_id" value="<?=intval($s['subscription_id'])?>">
          <input type="hidden" name="cycle_key" value="<?=ledger_escape($s['cycle_key'])?>">
          <button class="button secondary-button thin" type="submit">确认关联</button>
        </form>
      </div>
    <?php endforeach; ?>
    <?php if(empty($overview['suggestions'])): ?><p class="integ-note">暂时没有匹配候选。可在下方使用交易编号手动关联。</p><?php endif; ?>
    </div>
    <details><summary>手动关联已有交易与会员周期</summary>
      <form action="expenses-action.php" method="post" class="integ-form">
        <input type="hidden" name="csrf" value="<?=ledger_escape($csrf)?>"><input type="hidden" name="month" value="<?=ledger_escape($month)?>"><input type="hidden" name="action" value="link">
        <input type="number" min="1" name="tx_id" placeholder="交易编号" required>
        <select name="subscription_cycle" aria-label="订阅及周期" required>
          <option value="">选择订阅周期</option>
          <?php foreach(($overview['subscriptions']??[]) as $s): if($s['status']==='matched')continue; ?>
            <option value="<?=intval($s['subscription_id']).'|'.ledger_escape($s['cycle_key'])?>"><?=ledger_escape($s['name'].' · '.$s['cycle_key'])?></option>
          <?php endforeach; ?>
        </select>
        <button class="button secondary-button thin">关联</button>
      </form>
    </details>
  </section>
  <section class="stats-section">
    <h2>扣款提醒与异常</h2>
    <?php foreach(($overview['alerts']??[]) as $a): ?>
      <div class="integ-item">
        <span><?=ledger_escape(match($a['kind']){'price_change'=>'金额变化','payment_method_mismatch'=>'支付渠道不一致，请核对','duplicate_subscription_charge'=>'疑似同一会员周期重复扣款','missing_payment'=>'预计扣款日已过，未匹配到账单','possible_duplicates'=>'疑似重复交易',default=>'需要核对'})?></span>
        <span class="integ-note"><?=ledger_escape($a['subscription']??('交易 '.($a['count']??'')))?> · <?=ledger_escape($a['cycle_key']??'')?></span>
      </div>
    <?php endforeach; ?>
    <?php if(empty($overview['alerts'])): ?><p class="integ-note">目前没有发现上述异常。这不代表通知一定完整，仍需定期核对官方账单。</p><?php endif; ?>
  </section>
  <?php endif; ?>
  <section class="stats-section">
    <h2>商户自动分类规则</h2>
    <p class="integ-note">经过确认的商户关键词，未来新交易若原分类为「其他」会自动采用此分类；不会自动创建或扣除 Wallos 订阅。</p>
    <form method="post" action="expenses-action.php" class="integ-form">
      <input type="hidden" name="csrf" value="<?=ledger_escape($csrf)?>"><input type="hidden" name="month" value="<?=ledger_escape($month)?>"><input type="hidden" name="action" value="rule">
      <input type="text" name="needle" required minlength="3" maxlength="90" placeholder="商户关键词">
      <select name="category"><?php foreach(['FOOD'=>'餐饮','TRANSPORT'=>'交通','SHOPPING'=>'购物','ENTERTAINMENT'=>'娱乐','SUBSCRIPTION'=>'订阅','OTHER'=>'其他','EDUCATION'=>'学习','UTILITIES'=>'水电通信','HEALTH'=>'医疗','HOUSING'=>'住房'] as $k=>$v): ?><option value="<?=ledger_escape($k)?>"><?=ledger_escape($v)?></option><?php endforeach; ?></select>
      <button class="button secondary-button thin">保存规则</button>
    </form>
    <?php foreach(($rules['items']??[]) as $r): ?>
      <div class="integ-item"><span><?=ledger_escape($r['needle'])?> → <?=ledger_escape($r['category'])?></span>
        <form action="expenses-action.php" method="post"><input type="hidden" name="csrf" value="<?=ledger_escape($csrf)?>"><input type="hidden" name="month" value="<?=ledger_escape($month)?>"><input type="hidden" name="action" value="delete_rule"><input type="hidden" name="rule_id" value="<?=intval($r['id'])?>"><button class="button secondary-button tiny">删除</button></form>
      </div>
    <?php endforeach; ?>
  </section>
  <section class="stats-section">
    <h2>退款与重复消费修正</h2>
    <p class="integ-note">退款必须关联一笔已存在的收入和原始消费，仅减少净支出，不会凭空添加交易。</p>
    <form method="post" action="expenses-action.php" class="integ-form">
      <input type="hidden" name="csrf" value="<?=ledger_escape($csrf)?>"><input type="hidden" name="month" value="<?=ledger_escape($month)?>"><input type="hidden" name="action" value="refund">
      <input type="number" name="original_tx_id" min="1" placeholder="原消费交易编号" required>
      <input type="number" name="refund_tx_id" min="1" placeholder="退款入账编号" required>
      <input type="text" name="refund_amount" placeholder="退款金额（元）" pattern="[0-9]+(\.[0-9]{1,2})?" required>
      <button class="button secondary-button thin">确认退款关联</button>
    </form>
  <?php if(!empty($overview['refund_links'])): ?>
  <section class="stats-section">
    <h2>已关联的退款</h2>
    <?php foreach($overview['refund_links'] as $refund): ?>
    <div class="integ-item">
      <span>交易 #<?=intval($refund['original_tx_id'])?> ← 退款 #<?=intval($refund['refund_tx_id'])?> · <?=ledger_escape(ledger_money((int)$refund['amount_cents'],$refund['currency']))?></span>
      <form action="expenses-action.php" method="post">
        <input type="hidden" name="csrf" value="<?=ledger_escape($csrf)?>"><input type="hidden" name="month" value="<?=ledger_escape($month)?>"><input type="hidden" name="action" value="unlink_refund">
        <input type="hidden" name="refund_tx_id" value="<?=intval($refund['refund_tx_id'])?>">
        <button class="button secondary-button tiny">解除退款关联</button>
      </form>
    </div>
    <?php endforeach; ?>
  </section>
  <?php endif; ?>
  </section>
  <section class="stats-section">
    <h2>可能遗漏的会员订阅</h2>
    <p class="integ-note">检测重复扣款周期后提出建议；只在你确认后才会加入 Wallos。</p>
    <?php foreach(($discovery['items']??[]) as $candidate): ?>
      <div class="integ-item"><div><b><?=ledger_escape($candidate['merchant'])?></b>
        <div class="integ-note"><?=ledger_escape($candidate['cadence'])?> · <?=ledger_escape(ledger_money((int)$candidate['amount_cents'],$candidate['currency']))?> · <?=intval($candidate['observed_payments'])?> 次付款</div></div>
        <form action="expenses-action.php" method="post" class="integ-form">
          <input type="hidden" name="csrf" value="<?=ledger_escape($csrf)?>"><input type="hidden" name="month" value="<?=ledger_escape($month)?>"><input type="hidden" name="action" value="subscribe">
          <input type="hidden" name="name" value="<?=ledger_escape($candidate['merchant'])?>">
          <input type="hidden" name="currency" value="<?=ledger_escape($candidate['currency'])?>">
          <input type="hidden" name="amount_cents" value="<?=intval($candidate['amount_cents'])?>">
          <input type="hidden" name="cadence" value="<?=ledger_escape($candidate['cadence'])?>">
          <label class="integ-note">下次扣费（请确认）<input type="date" name="next_due_date" value="<?=ledger_escape($candidate['next_due_date'])?>" required></label>
          <button class="button secondary-button thin">确认加入 Wallos</button>
        </form></div>
    <?php endforeach; ?>
    <?php if(empty($discovery['items'])): ?><p class="integ-note">数据不足或暂未发现稳定的重复订阅模式。</p><?php endif; ?>
  </section>
  <section class="stats-section">
    <h2>近 12 个月实际支出趋势</h2>
    <div class="integ-scroll"><table class="integ-table"><thead><tr><th>月份</th><th>币种</th><th>方向</th><th>总额</th></tr></thead><tbody>
      <?php foreach(($trends['series']??[]) as $r): ?>
        <tr><td><?=ledger_escape($r['month'])?></td><td><?=ledger_escape($r['currency'])?></td><td><?=ledger_escape($r['direction'])?></td><td><?=ledger_escape(ledger_money((int)$r['amount_cents'],$r['currency']))?></td></tr>
      <?php endforeach; ?>
    </tbody></table></div>
    <?php if(empty($trends['series'])): ?><p class="integ-note">尚无足够交易形成趋势。</p><?php endif; ?>
    <p class="integ-note">多币种不合并；已关联退款单独作为扣减项展示。</p>
  </section>
</section>
