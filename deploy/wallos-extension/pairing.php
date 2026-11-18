<?php
/** Pairing entry in the same signed-in Wallos account as the ledger UI. */
header('Cache-Control: private, no-store, max-age=0');
header('Pragma: no-cache');
header('Referrer-Policy: no-referrer');
ob_start();
require_once 'includes/header.php';
require_once 'ledger-common.php';
ledger_require_owner();
ob_end_flush();
if (empty($_SESSION['finance_csrf'])) {
    $_SESSION['finance_csrf'] = bin2hex(random_bytes(32));
}
$csrf = $_SESSION['finance_csrf'];
?>
<style>
.om-pairing{max-width:680px;margin:35px auto 96px;padding:0 20px;color:var(--text-color)}
.om-pairing h1{font-size:28px;margin:0 0 12px}
.om-pairing p{line-height:1.6}
.om-pair-card{padding:28px;border:1px solid var(--box-border-color);
  border-radius:18px;background:var(--box-background-color);margin-top:20px}
.om-pair-card button{padding:12px 28px;border:0;border-radius:10px;
  background:var(--main-color);color:white;font:inherit;cursor:pointer}
.om-pair-card button:disabled{opacity:.55;cursor:default}
.om-pair-code{display:none;max-width:max-content;background:white;padding:16px;
  border-radius:12px;margin:24px auto 12px}
.om-pair-code img{display:block}
.om-pair-warning{font-size:13px;opacity:.8;margin-top:18px}
#om-pair-status{min-height:1.5em;margin:12px 0}
</style>
<main class="om-pairing">
  <h1>手机配对</h1>
  <p>打开清衡 App → 同步 → 重新配对，扫描这里生成的二维码。VPS 普通 HTTPS 域名和 Windows Tailscale 域名均使用同一套配对流程。</p>
  <div class="om-pair-card">
    <p>只有当前账本的所有者可以生成二维码。扫码后手机会验证服务器密钥和账本身份，你确认后才会启用同步。</p>
    <button id="om-pair-reveal" type="button">生成配对二维码</button>
    <p id="om-pair-status" role="status" aria-live="polite"></p>
    <div id="om-pair-code" class="om-pair-code" aria-label="清衡配对二维码"></div>
    <p class="om-pair-warning">二维码包含具有同步权限的设备密钥，请勿截图分享。二维码将在本页面显示 5 分钟后自动隐藏，但截图中的密钥不会自动失效；如已泄露，请在服务器端轮换设备密钥。</p>
    <p><a href="settings.php">返回设置</a></p>
  </div>
</main>
<script src="pairing-qrcode.min.js"></script>
<script>
(() => {
  'use strict';
  const csrf = <?= json_encode($csrf, JSON_HEX_TAG | JSON_HEX_AMP | JSON_HEX_APOS | JSON_HEX_QUOT) ?>;
  const reveal = document.getElementById('om-pair-reveal');
  const status = document.getElementById('om-pair-status');
  const box = document.getElementById('om-pair-code');
  let hideTimer = null;
  function hideQr() {
    if (hideTimer !== null) { clearTimeout(hideTimer); hideTimer = null; }
    box.replaceChildren();
    box.style.display = 'none';
  }
  reveal.addEventListener('click', async () => {
    hideQr();
    reveal.disabled = true;
    status.textContent = '正在生成配对二维码…';
    try {
      if (typeof QRCode !== 'function') throw new Error('二维码组件未加载');
      const res = await fetch('pairing-action.php', {
        method: 'POST', credentials: 'same-origin', cache: 'no-store',
        headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({csrf}),
      });
      const data = await res.json();
      if (!res.ok || data.ok !== true || !data.payload) throw new Error(data.message || '请求失败');
      // Encode the exact Windows v1 payload. Never persist it to web storage.
      new QRCode(box, {
        text: JSON.stringify(data.payload), width: 256, height: 256,
        correctLevel: QRCode.CorrectLevel.M,
      });
      box.style.display = 'block';
      status.textContent = '二维码已生成。请在 5 分钟内使用手机扫码。';
      hideTimer = setTimeout(() => {
        hideQr();
        status.textContent = '二维码展示已结束。再次点击可重新显示。';
      }, 5 * 60 * 1000);
    } catch (error) {
      hideQr();
      status.textContent = '生成失败：' + (error.message || '请重试');
    } finally {
      reveal.disabled = false;
    }
  });
  window.addEventListener('pagehide', hideQr);
})();
</script>

<?php require_once 'includes/footer.php'; ?>
