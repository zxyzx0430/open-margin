'use strict';

const $ = (id) => document.getElementById(id);
const RELEASES_URL = 'https://github.com/zxyzx0430/open-margin/releases/latest';
let currentState = null;
let currentConfig = null;
let jobTimer = null;
let progressTimer = null;
let pairTimer = null;

function toast(message, isError) {
  const el = $('toast');
  el.textContent = message;
  el.className = 'om-toast' + (isError ? ' is-error' : '');
  clearTimeout(el._timer);
  el._timer = setTimeout(() => { el.className = 'om-toast om-hidden'; }, 4200);
}

async function api(path, options) {
  const opts = Object.assign({ credentials: 'same-origin', headers: { 'Content-Type': 'application/json' } }, options || {});
  const response = await fetch(path, opts);
  if (response.status === 401) {
    toast('请从安装器打开的页面进入向导。', true);
    throw new Error('unauthorized');
  }
  let data = null;
  try { data = await response.json(); } catch (e) { data = { ok: false, code: 'BAD_RESPONSE' }; }
  return data;
}

function postAction(name, body) {
  return api('/api/actions/' + name, { method: 'POST', body: JSON.stringify(body || {}) });
}

function showScreen(name) {
  document.querySelectorAll('.om-screen').forEach((el) => el.classList.remove('is-active'));
  const el = $('screen-' + name);
  if (el) el.classList.add('is-active');
  updateSteps(name);
  window.scrollTo({ top: 0, behavior: 'smooth' });
}

function updateSteps(screen) {
  const order = ['env', 'tailscale', 'admin', 'deploy', 'complete'];
  let activeIndex = -1;
  if (screen === 'env') activeIndex = 0;
  else if (screen === 'tailscale' || screen === 'security') activeIndex = 1;
  else if (screen === 'admin') activeIndex = 2;
  else if (screen === 'deploy') activeIndex = 3;
  else if (screen === 'complete') activeIndex = 4;
  document.querySelectorAll('.om-step').forEach((el, index) => {
    el.classList.remove('is-active', 'is-done');
    if (activeIndex >= 0 && index < activeIndex) el.classList.add('is-done');
    if (activeIndex >= 0 && index === activeIndex) el.classList.add('is-active');
  });
}

function setHeader(state) {
  const el = $('header-state');
  if (!state) { el.textContent = '正在检查'; el.className = 'om-pill om-pill-neutral'; return; }
  if (state.step === 'COMPLETE' || state.publicSyncUrl) { el.textContent = '已部署'; el.className = 'om-pill om-pill-ok'; return; }
  if (state.step === 'WAITING_USER') { el.textContent = '等待操作'; el.className = 'om-pill om-pill-warn'; return; }
  if (state.step === 'FAILED') { el.textContent = '需要处理'; el.className = 'om-pill om-pill-error'; return; }
  el.textContent = '未完成'; el.className = 'om-pill om-pill-neutral';
}

function checkRow(check) {
  const row = document.createElement('div');
  row.className = 'om-check ' + (check.status || 'unknown');
  const dot = document.createElement('span'); dot.className = 'om-check-dot';
  const body = document.createElement('div'); body.className = 'om-check-body';
  const title = document.createElement('div'); title.className = 'om-check-title'; title.textContent = check.title || check.id;
  const detail = document.createElement('div'); detail.className = 'om-check-detail'; detail.textContent = check.detail || '';
  body.appendChild(title); body.appendChild(detail);
  if (check.remediation) {
    const fix = document.createElement('div'); fix.className = 'om-check-fix'; fix.textContent = check.remediation;
    body.appendChild(fix);
  }
  row.appendChild(dot); row.appendChild(body);
  return row;
}

function renderChecks(containerId, checks) {
  const container = $(containerId);
  if (!container) return;
  container.innerHTML = '';
  (checks || []).forEach((check) => container.appendChild(checkRow(check)));
}

function statusOk(checks) {
  return !(checks || []).some((check) => check.status === 'fail');
}

function renderProgress(active) {
  const stages = [
    { id: 'compose', label: '启动清衡服务' },
    { id: 'owner', label: '创建管理员' },
    { id: 'funnel', label: '开启公网入口' },
    { id: 'verify', label: '自测' }
  ];
  const container = $('deploy-progress');
  container.innerHTML = '';
  const activeIndex = stages.findIndex((stage) => stage.id === active);
  stages.forEach((stage, index) => {
    const row = document.createElement('div');
    row.className = 'om-progress-item';
    if (active === 'complete' || (activeIndex >= 0 && index < activeIndex)) row.classList.add('is-done');
    if (activeIndex === index) row.classList.add('is-active');
    if (active === 'failed') row.classList.add('is-fail');
    const dot = document.createElement('span'); dot.className = 'om-spinner';
    const text = document.createElement('span'); text.textContent = stage.label;
    row.appendChild(dot); row.appendChild(text);
    container.appendChild(row);
  });
}

function stopPolling() {
  if (jobTimer) { clearInterval(jobTimer); jobTimer = null; }
  if (progressTimer) { clearInterval(progressTimer); progressTimer = null; }
}

async function refreshState() {
  const data = await api('/api/status');
  if (data && data.ok !== false) {
    currentState = data.state;
    setHeader(currentState);
  }
  return data;
}

function pollJob(jobId, onDone, onProgress) {
  stopPolling();
  jobTimer = setInterval(async () => {
    try {
      const job = await api('/api/jobs/' + jobId);
      if (onProgress) onProgress(job);
      if (job.status === 'done' || job.status === 'failed') {
        stopPolling();
        onDone(job.result || { ok: false, code: 'JOB_FAILED', message: '后台任务失败，请查看日志。' });
      }
    } catch (error) {
      stopPolling();
      onDone({ ok: false, code: 'POLL_FAILED', message: error.message });
    }
  }, 900);
}

async function runCheck(targetScreen) {
  showScreen(targetScreen || 'env');
  const result = await postAction('check');
  if (!result.jobId) { toast(result.message || '检查失败', true); return; }
  pollJob(result.jobId, (data) => {
    const checks = data.checks || [];
    renderChecks('env-checks', checks.filter((check) => check.id !== 'tailscale'));
    renderChecks('ts-checks', checks.filter((check) => check.id === 'tailscale'));
    $('btn-env-next').disabled = !statusOk(checks.filter((check) => check.id !== 'tailscale'));
    $('btn-ts-next').disabled = !statusOk(checks.filter((check) => check.id === 'tailscale'));
    if (data.ok) toast('环境检查通过。');
    else toast('还有未通过的项目，请按提示处理。', true);
  });
}

async function installDeps() {
  $('btn-install-deps').disabled = true;
  toast('如果弹出 Windows 管理员确认（UAC），请点“是”。');
  const result = await postAction('install-deps');
  if (!result.jobId) { $('btn-install-deps').disabled = false; toast(result.message || '安装失败', true); return; }
  pollJob(result.jobId, (data) => {
    $('btn-install-deps').disabled = false;
    if (data.pendingReboot) {
      toast('依赖已安装，需要重启电脑后继续。', true);
      showScreen('welcome');
      return;
    }
    if (data.ok) { toast('依赖安装完成，正在重新检查。'); runCheck('env'); }
    else { toast(data.message || '依赖安装失败', true); }
  });
}

async function tailscaleLogin() {
  const result = await postAction('login-tailscale');
  if (result.ok) toast('已打开 Tailscale 登录，请在浏览器完成。');
  else toast(result.message || '无法打开 Tailscale 登录', true);
}

function nextFromEnv() { showScreen('tailscale'); runCheck('tailscale'); }

function nextFromSecurity() { showScreen('admin'); }

function nextFromAdmin() {
  const user = $('admin-user').value.trim();
  const password = $('admin-password').value;
  const password2 = $('admin-password2').value;
  if (!user) { toast('请填写用户名。', true); return; }
  if (password.length < 8) { toast('密码至少 8 位。', true); return; }
  if (password !== password2) { toast('两次密码不一致。', true); return; }
  showScreen('deploy');
  renderProgress(null);
}

async function startDeploy() {
  const user = $('admin-user').value.trim();
  const password = $('admin-password').value;
  if (!user || password.length < 8) { toast('请先完成管理员账号设置。', true); showScreen('admin'); return; }
  $('btn-deploy').disabled = true;
  $('deploy-state').textContent = '部署中';
  $('deploy-state').className = 'om-pill om-pill-warn';
  renderProgress('compose');
  progressTimer = setInterval(async () => {
    try {
      const data = await api('/api/status');
      if (data.state && data.state.progress) renderProgress(data.state.progress);
    } catch (e) { }
  }, 1600);
  try {
    const result = await postAction('deploy', {
      adminUser: user,
      adminPassword: password,
      adminEmail: $('admin-email').value.trim(),
      ackPublic: $('ack-public').checked,
      skipFunnel: false
    });
    if (!result.jobId) { throw new Error(result.message || '部署没有启动'); }
    pollJob(result.jobId, (data) => {
      $('btn-deploy').disabled = false;
      if (data.ok) {
        renderProgress('complete');
        $('deploy-state').textContent = '完成';
        $('deploy-state').className = 'om-pill om-pill-ok';
        showScreen('complete');
        loadQr();
        toast('部署完成。');
        return;
      }
      if (data.code === 'FUNNEL_APPROVAL_REQUIRED' || data.action === 'open_url') {
        $('deploy-state').textContent = '等待授权';
        $('deploy-state').className = 'om-pill om-pill-warn';
        $('deploy-action-box').classList.remove('om-hidden');
        $('deploy-action-text').textContent = data.message || '需要在浏览器里允许 Tailscale Funnel。';
        if (data.approvalUrl) $('deploy-action-link').href = data.approvalUrl;
        else $('deploy-action-link').href = 'https://login.tailscale.com/admin/machines';
        toast('请在浏览器里点“允许”，然后回来重试。', true);
        return;
      }
      renderProgress('failed');
      $('deploy-state').textContent = '需要处理';
      $('deploy-state').className = 'om-pill om-pill-error';
      toast(data.message || '部署失败，请查看日志。', true);
      if (data.manualUrl) {
        $('deploy-action-box').classList.remove('om-hidden');
        $('deploy-action-text').textContent = '请在浏览器完成 Wallos 管理员注册，然后回来重试。';
        $('deploy-action-link').href = data.manualUrl;
        $('deploy-action-link').textContent = '打开 Wallos 注册页';
      }
    }, () => {
      refreshState().catch(() => {});
    });
  } catch (error) {
    $('btn-deploy').disabled = false;
    $('deploy-state').textContent = '失败';
    $('deploy-state').className = 'om-pill om-pill-error';
    toast(error.message || '部署失败', true);
  }
}

function loadAppQr() {
  const container = $('app-qr');
  container.innerHTML = '';
  if (typeof QRCode === 'function') {
    new QRCode(container, { text: RELEASES_URL, width: 220, height: 220, correctLevel: QRCode.CorrectLevel.M });
  } else {
    const link = document.createElement('a'); link.href = RELEASES_URL; link.textContent = '打开 Releases 下载 APK';
    container.appendChild(link);
  }
}

async function loadQr(force) {
  if (force) { clearTimeout(pairTimer); pairTimer = null; }
  const data = await api('/api/qr');
  const container = $('pair-qr');
  container.innerHTML = '';
  container.classList.remove('is-hidden');
  $('pair-qr-hint').textContent = '二维码 10 分钟后自动隐藏，只在本机显示。';
  if (!data.ok || !data.payload) {
    container.textContent = data.message || '还没有配对信息。';
    return;
  }
  if (typeof QRCode === 'function') {
    new QRCode(container, { text: data.payload, width: 220, height: 220, correctLevel: QRCode.CorrectLevel.M });
  } else {
    container.textContent = '二维码库未加载，请重新打开向导。';
  }
  clearTimeout(pairTimer);
  pairTimer = setTimeout(() => {
    container.innerHTML = '';
    container.classList.add('is-hidden');
    $('pair-qr-hint').textContent = '二维码已隐藏。需要时点“重新显示二维码”。';
  }, 10 * 60 * 1000);
}

async function showStatus() {
  showScreen('status');
  const data = await refreshState();
  const summary = $('status-summary');
  summary.innerHTML = '';
  if (!data || !data.state) { summary.textContent = '无法读取状态。'; return; }
  const state = data.state;
  const lines = [
    ['部署状态', state.step || '未知'],
    ['公网地址', state.publicSyncUrl || '未开启'],
    ['手机', state.phoneVerified ? '已验证' : '未验证'],
    ['看门狗', state.watchdogPaused ? '已暂停' : '运行中'],
    ['端口', `API ${state.ports.api} / Wallos ${state.ports.wallos} / 网关 ${state.ports.gateway}`]
  ];
  lines.forEach(([label, value]) => {
    const row = document.createElement('div');
    row.className = 'om-check';
    const body = document.createElement('div'); body.className = 'om-check-body';
    const title = document.createElement('div'); title.className = 'om-check-title'; title.textContent = label;
    const detail = document.createElement('div'); detail.className = 'om-check-detail'; detail.textContent = value;
    body.appendChild(title); body.appendChild(detail); row.appendChild(body); summary.appendChild(row);
  });
  await refreshLogs();
}

async function refreshLogs() {
  const data = await api('/api/logs?tail=200');
  const lines = data.lines || [];
  $('status-logs').textContent = lines.join('\n') || '暂无日志。';
  $('log-lines').textContent = lines.join('\n') || '暂无日志。';
}

async function runSimpleAction(name, successMessage) {
  const result = await postAction(name);
  if (!result.jobId) { toast(result.message || '操作失败', true); return; }
  pollJob(result.jobId, (data) => {
    if (data.ok) toast(successMessage || '完成。');
    else toast(data.message || '操作失败', true);
    refreshState().catch(() => {});
    if (name === 'disable' || name === 'enable') showStatus();
  });
}

async function backup() {
  const result = await postAction('backup');
  if (!result.jobId) { toast(result.message || '备份没有启动', true); return; }
  toast('正在备份，完成后会提示。');
  pollJob(result.jobId, (data) => {
    if (data.ok) toast('备份完成。');
    else toast(data.message || '备份失败', true);
  });
}

function loadConfig() {
  api('/api/config').then((data) => {
    if (data && data.ok) {
      currentConfig = data;
      $('version').textContent = 'v' + data.version;
    }
  }).catch(() => {});
}

async function boot() {
  loadConfig();
  try {
    const data = await refreshState();
    if (data.state && data.state.publicSyncUrl && data.state.step === 'COMPLETE') {
      showScreen('complete');
      loadAppQr();
      loadQr();
      return;
    }
    if (data.state && data.state.step === 'WAITING_USER' && data.state.lastError && data.state.lastError.step === 'funnel') {
      showScreen('deploy');
      renderProgress('funnel');
      $('deploy-action-box').classList.remove('om-hidden');
      $('deploy-action-text').textContent = data.state.lastError.message || '需要在浏览器里允许 Tailscale Funnel。';
      $('deploy-action-link').href = 'https://login.tailscale.com/admin/machines';
      return;
    }
    showScreen('welcome');
  } catch (error) {
    showScreen('welcome');
  }
}

document.addEventListener('DOMContentLoaded', () => {
  $('btn-start').addEventListener('click', () => { showScreen('env'); runCheck('env'); });
  $('btn-status').addEventListener('click', showStatus);
  $('btn-recheck').addEventListener('click', () => runCheck('env'));
  $('btn-install-deps').addEventListener('click', installDeps);
  $('btn-env-next').addEventListener('click', nextFromEnv);
  $('btn-ts-recheck').addEventListener('click', () => runCheck('tailscale'));
  $('btn-ts-login').addEventListener('click', tailscaleLogin);
  $('btn-ts-admin').addEventListener('click', () => window.open('https://login.tailscale.com/admin/machines', '_blank', 'noopener'));
  $('btn-ts-next').addEventListener('click', () => showScreen('security'));
  $('ack-public').addEventListener('change', (event) => { $('btn-security-next').disabled = !event.target.checked; });
  $('btn-security-next').addEventListener('click', nextFromSecurity);
  $('btn-admin-next').addEventListener('click', nextFromAdmin);
  $('btn-deploy').addEventListener('click', startDeploy);
  $('btn-deploy-retry').addEventListener('click', startDeploy);
  $('btn-deploy-logs').addEventListener('click', async () => {
    $('log-drawer').classList.remove('om-hidden');
    await refreshLogs();
  });
  $('btn-close-logs').addEventListener('click', () => $('log-drawer').classList.add('om-hidden'));
  $('btn-pair-qr-refresh').addEventListener('click', () => loadQr(true));
  $('btn-backup').addEventListener('click', backup);
  $('btn-open-ledger').addEventListener('click', () => {
    const port = currentState && currentState.ports ? currentState.ports.wallos : 18082;
    window.open('http://127.0.0.1:' + port + '/', '_blank', 'noopener');
  });
  $('btn-disable').addEventListener('click', async () => {
    if (!window.confirm('关闭后公网入口会消失，手机将无法在外网同步。数据不会删除。继续吗？')) return;
    await runSimpleAction('disable', '远程同步已关闭。');
  });
  $('btn-status-refresh').addEventListener('click', showStatus);
  $('btn-status-repair').addEventListener('click', () => runSimpleAction('repair', '修复完成。'));
  $('btn-watchdog-pause').addEventListener('click', () => runSimpleAction('pause-watchdog', '看门狗已暂停 1 小时。'));
  $('btn-watchdog-resume').addEventListener('click', () => runSimpleAction('resume-watchdog', '看门狗已恢复。'));
  $('btn-rotate-token').addEventListener('click', async () => {
    if (!window.confirm('重新生成后，旧二维码立即失效，手机需要重新扫码。继续吗？')) return;
    await runSimpleAction('rotate-token', '设备密钥已更新，请重新扫码。');
    loadQr(true);
  });
  $('btn-status-enable').addEventListener('click', () => runSimpleAction('enable', '远程同步已重新开启。'));
  boot();
});
