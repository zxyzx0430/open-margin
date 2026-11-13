<?php
require_once 'includes/header.php';

$wallosIsUpToDate = true;
if (!is_null($settings['latest_version'])) {
    $latestVersion = $settings['latest_version'];
    if (version_compare($version, $latestVersion) == -1) {
        $wallosIsUpToDate = false;
    }
}
?>

<section class="contain">

    <section class="account-section om-integration-intro">
        <header><h2>开源整合与扩展</h2></header>
        <p class="om-origin-lead">清衡（Open Margin）基于 Wallos 与 Seamless Bookkeeping 两个独立开源项目整合开发，作为个人财务管理的统一入口。我们是整合与修改方，不是这两个上游项目的原始作者。</p>
        <div class="om-origin-grid">
            <article class="om-origin-card">
                <div class="om-origin-card-title"><h3>Wallos</h3><span>订阅管理 · 网页端</span></div>
                <p>提供订阅计划、续费日程、周期预算及相关统计。清衡在其基础上扩展了财务展示和网页交互。</p>
                <div class="om-origin-card-meta">
                    <span>原项目：ellite/Wallos</span><span>开源许可：GPL-3.0</span>
                </div>
                <a href="https://github.com/ellite/Wallos" target="_blank" rel="noopener noreferrer">查看 Wallos 原项目</a>
            </article>
            <article class="om-origin-card">
                <div class="om-origin-card-title"><h3>Seamless Bookkeeping</h3><span>Android 记账端</span></div>
                <p>提供 Android 端记账基础。清衡在其基础上扩展了通知采集、记录管理及与服务端的同步能力。</p>
                <div class="om-origin-card-meta">
                    <span>原项目：DykiSensei/seamless-bookkeeping</span><span>开源许可：MIT</span>
                </div>
                <a href="https://github.com/DykiSensei/seamless-bookkeeping" target="_blank" rel="noopener noreferrer">查看 Seamless Bookkeeping 原项目</a>
            </article>
        </div>
        <div class="om-origin-added">
            <h3>清衡的整合与修改</h3>
            <p>将 Android 端的交易采集与独立 Finance 账本、Wallos 订阅管理连接起来，新增服务器同步、财务查询与分析、统一网页和供外部 AI 使用的 MCP 接口。上游实现及其版权归原项目作者，清衡的整合与扩展代码由本项目维护。</p>
            <a href="https://github.com/zxyzx0430/open-margin" target="_blank" rel="noopener noreferrer">查看清衡项目代码</a>
        </div>
    </section>


    <section class="account-section">
        <header>
            <h2>Wallos 组件信息</h2>
        </header>
        <div class="credits-list">
            <div>
                <h3>
                    Wallos <?= $version ?> <?= $demoMode ? "Demo" : "" ?>
                </h3>
                <span>
                    <?= translate('release_notes', $i18n) ?>
                    <a href="https://github.com/ellite/Wallos/releases/tag/<?= $version ?>" target="_blank"
                        title="<?= translate('external_url', $i18n) ?>" rel="noreferrer">
                        <i class="fa-solid fa-arrow-up-right-from-square"></i>
                    </a>
                </span>
            </div>
            <?php if (!$wallosIsUpToDate): ?>
                <div class="update-available">
                    <h3>
                        <i class="fa-solid fa-info-circle"></i>
                        <?= translate('update_available', $i18n) ?> <?= $latestVersion ?>
                    </h3>
                    <span>
                        <?= translate('release_notes', $i18n) ?>
                        <a href="https://github.com/ellite/Wallos/releases/tag/<?= $latestVersion ?>" target="_blank"
                            title="<?= translate('external_url', $i18n) ?>" rel="noreferrer">
                            <i class="fa-solid fa-arrow-up-right-from-square"></i>
                        </a>
                    </span>
                </div>
            <?php endif; ?>
            <div>
                <h3><?= translate('license', $i18n) ?></h3>
                <span>
                    GPLv3
                    <a href="https://www.gnu.org/licenses/gpl-3.0.en.html" target="_blank"
                        title="<?= translate('external_url', $i18n) ?>" rel="noreferrer">
                        <i class="fa-solid fa-arrow-up-right-from-square"></i>
                    </a>
                </span>
            </div>
            <div>
                <h3><?= translate('issues_and_requests', $i18n) ?></h3>
                <span>
                    GitHub
                    <a href="https://github.com/ellite/Wallos/issues" target="_blank"
                        title="<?= translate('external_url', $i18n) ?>" rel="noreferrer">
                        <i class="fa-solid fa-arrow-up-right-from-square"></i>
                    </a>
                </span>
            </div>
            <div>
                <h3><?= translate('the_author', $i18n) ?></h3>
                <span>
                    https://henrique.pt
                    <a href="https://henrique.pt/" target="_blank" title="<?= translate('external_url', $i18n) ?>"
                        rel="noreferrer">
                        <i class="fa-solid fa-arrow-up-right-from-square"></i>
                    </a>
                </span>
            </div>

        </div>
    </section>

    <section class="account-section">
        <header>
            <h2><?= translate("credits", $i18n) ?></h2>
        </header>
        <div class="credits-list">
            <div>
                <h3><?= translate('payment_icons', $i18n) ?></h3>
                <span>
                    https://www.figma.com/file/5IMW8JfoXfB5GRlPNdTyeg/Credit-Cards-and-Payment-Methods-Icons-(Community)
                    <a href="https://www.figma.com/file/5IMW8JfoXfB5GRlPNdTyeg/Credit-Cards-and-Payment-Methods-Icons-(Community)"
                        target="_blank" title="<?= translate('external_url', $i18n) ?>" rel="noreferrer">
                        <i class="fa-solid fa-arrow-up-right-from-square"></i>
                    </a>
                </span>
            </div>
            <div>
                <h3>ApexCharts</h3>
                <span>
                    https://apexcharts.com/
                    <a href="https://apexcharts.com/" target="_blank" title="<?= translate('external_url', $i18n) ?>"
                        rel="noreferrer">
                        <i class="fa-solid fa-arrow-up-right-from-square"></i>
                    </a>
                </span>
            </div>
            <div>
                <h3>QRCode.js</h3>
                <span>
                    https://github.com/davidshimjs/qrcodejs
                    <a href="https://github.com/davidshimjs/qrcodejs" target="_blank"
                        title="<?= translate('external_url', $i18n) ?>" rel="noreferrer">
                        <i class="fa-solid fa-arrow-up-right-from-square"></i>
                    </a>
                </span>
            </div>
            <div>
                <h3>Icons by icons8</h3>
                <span>
                    https://icons8.com/
                    <a href="https://icons8.com/" target="_blank" title="<?= translate('external_url', $i18n) ?>"
                        rel="noreferrer">
                        <i class="fa-solid fa-arrow-up-right-from-square"></i>
                    </a>
                </span>
            </div>
        </div>
    </section>

</section>

<?php
require_once 'includes/footer.php';
?>