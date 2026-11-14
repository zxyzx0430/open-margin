<?php

require_once 'includes/header.php';


require_once 'includes/getdbkeys.php';
require_once 'includes/logo_theme_variant.php';
require_once 'includes/upcoming_cancellations.php';

function formatPrice($price, $currencyCode, $currencies)
{
    $formattedPrice = CurrencyFormatter::format($price, $currencyCode);
    if (strstr($formattedPrice, $currencyCode)) {
        $symbol = $currencyCode;

        foreach ($currencies as $currency) {

            if ($currency['code'] === $currencyCode) {
                if ($currency['symbol'] != "") {
                    $symbol = $currency['symbol'];
                }
                break;
            }
        }
        $formattedPrice = str_replace($currencyCode, $symbol, $formattedPrice);
    }

    return $formattedPrice;
}

function formatDate($date, $lang = 'en')
{
    $currentYear = date('Y');
    $dateYear = date('Y', strtotime($date));

    // Determine the date format based on whether the year matches the current year
    $dateFormat = ($currentYear == $dateYear) ? 'MMM d' : 'MMM yyyy';

    // Try to create an IntlDateFormatter; if it fails, fallback to 'en'
    try {
        $formatter = new IntlDateFormatter(
            $lang,
            IntlDateFormatter::SHORT,
            IntlDateFormatter::NONE,
            null,
            null,
            $dateFormat
        );

        if (!$formatter) {
            throw new Exception('Failed to create IntlDateFormatter with language: ' . $lang);
        }
    } catch (Throwable $e) {
        $lang = 'en'; // Fallback to English on error
        $formatter = new IntlDateFormatter(
            $lang,
            IntlDateFormatter::SHORT,
            IntlDateFormatter::NONE,
            null,
            null,
            $dateFormat
        );
    }

    // Format the date
    $formattedDate = $formatter->format(new DateTime($date));

    return $formattedDate;
}

// All active scheduled payments, earliest due first. Do not cap the homepage at three.
$upcomingSubscriptions = [];
$paymentQuery = $db->prepare("SELECT s.id, s.name, s.price, s.currency_id, s.next_payment,
        s.cycle, s.frequency, s.auto_renew, pm.name AS payment_method_name
    FROM subscriptions s
    LEFT JOIN payment_methods pm ON pm.id = s.payment_method_id AND pm.user_id = s.user_id
    WHERE s.user_id = :owner AND s.inactive = 0
        AND s.next_payment IS NOT NULL AND s.next_payment <> ''
    ORDER BY date(s.next_payment) ASC, s.id ASC");
$paymentQuery->bindValue(':owner', $userId, SQLITE3_INTEGER);
$paymentRows = $paymentQuery->execute();
while ($paymentRows && ($payment = $paymentRows->fetchArray(SQLITE3_ASSOC))) {
    $upcomingSubscriptions[] = $payment;
}

// Fetch the subscriptions whose cancellation reminder is still ahead (shared with
// the statistics page, so both stay in step).
$upcomingCancellations = get_upcoming_cancellations($db, $userId);
$hasUpcomingCancellations = !empty($upcomingCancellations);

require_once 'includes/stats_calculations.php';
require_once 'finance-unified-common.php';
$financeMonthKey=finance_active_month();


?>

<section class="contain dashboard om-home">
<link rel="stylesheet" href="dashboard-refresh.css?v=<?=filemtime(__DIR__."/dashboard-refresh.css")?>">
    <?php
        if ($isAdmin && $settings['update_notification']) {
            if (!is_null($settings['latest_version'])) {
                $latestVersion = $settings['latest_version'];
                if (version_compare($version, $latestVersion) == -1) {
                    ?>
                    <div class="update-banner">
                        <div class="update-banner-icon">
                            <i class="fa-solid fa-arrow-up"></i>
                        </div>
                        <div class="update-banner-text">
                            <strong><?= translate('new_version_available', $i18n) ?></strong>
                            <span>
                                <?= translate('current_version', $i18n) ?>: <?= htmlspecialchars($version) ?>
                                <i class="fa-solid fa-arrow-right"></i>
                                <b><?= htmlspecialchars($latestVersion) ?></b>
                            </span>
                        </div>
                        <a class="update-banner-link"
                            href="https://github.com/ellite/Wallos/releases/tag/<?= htmlspecialchars($latestVersion) ?>"
                            target="_blank" title="<?= translate('external_url', $i18n) ?>" rel="noreferrer">
                            <?= translate('release_notes', $i18n) ?>
                            <i class="fa-solid fa-arrow-up-right-from-square"></i>
                        </a>
                    </div>
                    <?php
                }
            }
        }
        if ($demoMode) {
            ?>
            <div class="demo-banner">
            Running in <b>Demo Mode</b>, certain actions and settings are disabled.<br>
            The database will be reset every 120 minutes.
            </div>
            <?php
        }
    ?>


<?php ob_start(); ?>
    <section class="om-home-section om-payments" aria-labelledby="om-upcoming-title">
        <div class="om-section-heading">
            <div>
                <h2 id="om-upcoming-title">即将到期的付款</h2>
                <span><?= count($upcomingSubscriptions) ?> 项</span>
            </div>
            <a href="subscriptions.php">管理订阅</a>
        </div>
        <?php
        $paymentMonth = date('Y-m');
        $groupedPayments = ['current' => [], 'future' => []];
        foreach ($upcomingSubscriptions as $payment) {
            $dueMonth = substr((string)$payment['next_payment'], 0, 7);
            $groupKey = $dueMonth <= $paymentMonth ? 'current' : 'future';
            $groupedPayments[$groupKey][] = $payment;
        }
        ?>
        <?php if (!$upcomingSubscriptions): ?>
            <p class="om-empty">暂无付款计划</p>
        <?php else: ?>
            <?php foreach ($groupedPayments as $groupKey => $groupItems):
                if (!$groupItems) continue;
                $groupTitle = $groupKey === 'current' ? '本月' : '未来';
            ?>
            <div class="om-payment-group">
                <div class="om-payment-group-heading">
                    <h3><?= $groupTitle ?></h3>
                    <div class="om-carousel-controls" data-om-carousel-controls hidden>
                        <span class="om-carousel-position" data-om-carousel-position aria-live="polite"></span>
                        <button type="button" class="om-carousel-arrow" data-om-prev aria-label="<?= $groupTitle ?>付款：上一项">‹</button>
                        <button type="button" class="om-carousel-arrow" data-om-next aria-label="<?= $groupTitle ?>付款：下一项">›</button>
                    </div>
                </div>
                <div class="om-payment-carousel" data-om-carousel aria-label="<?= $groupTitle ?>付款" data-total="<?= count($groupItems) ?>">
                  <div class="om-carousel-viewport" data-om-carousel-viewport role="region" aria-roledescription="轮播" aria-label="<?= $groupTitle ?>付款项目">
                    <div class="om-payment-grid om-carousel-track" data-om-carousel-track>
                    <?php foreach ($groupItems as $subscription):
                        $due = (string)$subscription['next_payment'];
                        $dueStamp = strtotime($due);
                        $overdue = $due < date('Y-m-d');
                        $dueText = $dueStamp === false ? $due : date('Y年n月j日', $dueStamp);
                        $currencyId = (int)$subscription['currency_id'];
                        $currencyCode = $currencies[$currencyId]['code'] ?? 'CNY';
                        $priceText = formatPrice((float)$subscription['price'], $currencyCode, $currencies);
                        $cycle = (int)$subscription['cycle'];
                        $frequency = max(1, (int)$subscription['frequency']);
                        $cycleLabel = $cycle === 1 ? '天' : ($cycle === 2 ? '周' :
                            ($cycle === 3 ? '月' : ($cycle === 4 ? '年' : '次')));
                        $cycleText = $cycle === 5 ? '一次性' :
                            (($frequency === 1 ? '每' : '每'.$frequency).$cycleLabel);
                        if ($cycle !== 5 && !(int)$subscription['auto_renew']) {
                            $cycleText .= ' · 手动续费';
                        }
                        $method = trim((string)($subscription['payment_method_name'] ?? ''));
                        if ($method === 'Credit Card') $method = '银行卡';
                        if ($method === 'WeChat') $method = '微信支付';
                        if ($method === 'AliPay') $method = '支付宝';
                        if ($method === '未指定（待确认）') $method = '未指定';
                    ?>
                    <button type="button" class="om-payment-card"
                        onclick="showSubscriptionDetails(event, <?= (int)$subscription['id'] ?>)"
                        data-id="<?= (int)$subscription['id'] ?>">
                        <span class="om-payment-card-top">
                            <span class="om-payment-date"><?= htmlspecialchars($dueText, ENT_QUOTES, 'UTF-8') ?></span>
                            <?php if ($overdue): ?><em class="om-due-late">已逾期</em><?php endif; ?>
                        </span>
                        <strong class="om-payment-name"><?= htmlspecialchars((string)$subscription['name'], ENT_QUOTES, 'UTF-8') ?></strong>
                        <strong class="om-payment-amount"><?= htmlspecialchars($priceText, ENT_QUOTES, 'UTF-8') ?></strong>
                        <span class="om-payment-meta"><?= htmlspecialchars($cycleText, ENT_QUOTES, 'UTF-8') ?><?= $method !== '' ? ' · '.htmlspecialchars($method, ENT_QUOTES, 'UTF-8') : '' ?></span>
                    </button>
                    <?php endforeach; ?>
                    </div>
                  </div>
                </div>
            </div>
            <?php endforeach; ?>
        <?php endif; ?>
    </section>
<?php $omPaymentsHtml = ob_get_clean(); ?>

<?php
try {
    $financeModel=finance_unified_model($db,(int)$userId,$financeMonthKey);
    finance_render_panel('dashboard',$financeModel,$omPaymentsHtml);
} catch(Throwable $e) {
    error_log('[Finance unified] page render unavailable '.get_class($e));
    echo '<section class="finance-unavailable">Finance 实际消费暂时不可用，请刷新后重试；此处不把错误显示为零元。</section>';
    echo $omPaymentsHtml;
}
?>






    <section class="om-home-section om-subscription-overview" aria-labelledby="om-subscription-title">
        <div class="om-section-heading">
            <h2 id="om-subscription-title">我的订阅与预算</h2>
            <a href="subscriptions.php">查看全部订阅</a>
        </div>
        <div class="om-summary-grid">
            <div class="om-summary-card">
                <small>本月实际订阅支出</small>
                <strong><?= isset($financeModel) ? finance_money($financeModel['actual_subscription_cents']) : '—' ?></strong>
            </div>
            <div class="om-summary-card">
                <small>本月计划到期</small>
                <strong><?= isset($financeModel) && $financeModel['planned_subscription_cents'] !== null ? finance_money($financeModel['planned_subscription_cents']) : '—' ?></strong>
            </div>
            <div class="om-summary-card">
                <small>在用订阅</small>
                <strong><?= (int)($activeSubscriptions ?? 0) ?></strong>
            </div>
            <div class="om-summary-card">
                <small>折合月均成本</small>
                <strong><?= isset($totalCostPerMonth) ? CurrencyFormatter::format($totalCostPerMonth, $currencies[$userData['main_currency']]['code']) : '—' ?></strong>
            </div>
        </div>
        <div class="om-subscription-budget">
            <div class="om-budget-head">
                <div>
                    <h3>订阅预算</h3>
                    <span><?= isset($monthlyBudget) && $monthlyBudget > 0 ? '已设置' : '未设置' ?></span>
                </div>
                <strong><?= isset($monthlyBudget) && $monthlyBudget > 0 ? formatPrice($monthlyBudget, $currencies[$userData['main_currency']]['code'], $currencies) : '—' ?></strong>
            </div>
            <?php if (isset($monthlyBudget) && $monthlyBudget > 0): ?>
                <?php
                    $usedPct = isset($totalCostPerMonth) ? (float)$totalCostPerMonth / $monthlyBudget * 100 : 0;
                    $overBudget = isset($totalCostPerMonth) ? max(0, $totalCostPerMonth - $monthlyBudget) : 0;
                ?>
                <div class="om-budget-track<?= $overBudget > 0 ? ' is-over-budget' : '' ?>" role="progressbar" aria-label="订阅预算使用率" aria-valuenow="<?= min(100, max(0, round($usedPct, 1))) ?>" aria-valuetext="已用 <?= number_format($usedPct, 1) ?>%" aria-valuemin="0" aria-valuemax="100">
                    <span style="width:<?= min(100, max(0, $usedPct)) ?>%"></span>
                </div>
                <div class="om-budget-foot">
                    <span>已用 <?= number_format($usedPct, 1) ?>%</span>
                    <span class="<?= $overBudget > 0 ? 'om-budget-over' : '' ?>">
                        <?= $overBudget > 0
                            ? '超出 '.formatPrice($overBudget, $currencies[$userData['main_currency']]['code'], $currencies)
                            : '剩余 '.(isset($monthlyBudgetLeft) ? formatPrice($monthlyBudgetLeft, $currencies[$userData['main_currency']]['code'], $currencies) : '—') ?>
                    </span>
                </div>
            <?php endif; ?>
            <div class="om-budget-detail">
                <?php if (isset($totalCostPerYear)): ?>
                    <span>全年折合 <?= CurrencyFormatter::format($totalCostPerYear, $currencies[$userData['main_currency']]['code']) ?></span>
                <?php endif; ?>
                <?php if (isset($inactiveSubscriptions) && $inactiveSubscriptions > 0): ?>
                    <span>已停用 <?= (int)$inactiveSubscriptions ?> 项<?php if (isset($totalSavingsPerMonth) && $totalSavingsPerMonth > 0): ?> · 每月节省 <?= CurrencyFormatter::format($totalSavingsPerMonth, $currencies[$userData['main_currency']]['code']) ?><?php endif; ?></span>
                <?php endif; ?>
                <?php if (isset($periodBudget) && $periodBudget > 0): ?>
                    <span>本周期预算 <?= formatPrice($periodBudget, $currencies[$userData['main_currency']]['code'], $currencies) ?></span>
                    <?php if (isset($amountNeededThisPeriod)): ?>
                    <span>本周期待付 <?= CurrencyFormatter::format($amountNeededThisPeriod, $currencies[$userData['main_currency']]['code']) ?></span>
                    <?php endif; ?>
                <?php endif; ?>
            </div>
        </div>
        <?php if ($hasUpcomingCancellations): ?>
            <div class="om-cancellations">
                <h3>即将取消</h3>
                <?php foreach ($upcomingCancellations as $item): ?>
                    <button type="button" onclick="showSubscriptionDetails(event, <?= (int)$item['id'] ?>)">
                        <?= htmlspecialchars((string)$item['name'], ENT_QUOTES, 'UTF-8') ?>
                        <span><?= htmlspecialchars((string)$item['cancellation_date'], ENT_QUOTES, 'UTF-8') ?></span>
                    </button>
                <?php endforeach; ?>
            </div>
        <?php endif; ?>
    </section>

</section>

<?php
// Get all subscriptions for user details lookup
$query = 'SELECT * FROM subscriptions WHERE user_id = :userId';
$stmt = $db->prepare($query);
$stmt->bindParam(':userId', $userId, SQLITE3_INTEGER);
$result = $stmt->execute();
$subscriptions = [];
while ($row = $result->fetchArray(SQLITE3_ASSOC)) {
    $subscriptions[] = $row;
}
require_once 'includes/subscription_details_popup.php';
?>

<script src="scripts/dashboard.js?<?= $version ?>"></script>

<script src="scripts/libs/apexcharts.min.js"></script>
<script src="finance-unified.js?v=<?=filemtime(__DIR__."/finance-unified.js")?>"></script>
<script src="dashboard-carousel.js?v=<?=filemtime(__DIR__."/dashboard-carousel.js")?>" defer></script>
<?php
require_once 'includes/footer.php';
?>
