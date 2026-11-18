'use strict';

const $ = (id) => document.getElementById(id);
const RELEASES_URL = 'https://github.com/zxyzx0430/open-margin/releases/latest';
let currentState = null;
let currentConfig = null;
let jobTimer = null;
let progressTimer = null;
let pairTimer = null;
let pollFailures = 0;
let homeRequested = false;
let dockerLicenseRecorded = false;

const STEP_LABELS = {
  UNINITIALIZED: '未开始',
  CHECKING: '检查中',
  NEEDS_USER: '等待操作',
  INSTALLING_DEPS: '安装依赖',
  WAITING_REBOOT: '等待重启',
  READY: '准备就绪',
  DEPLOYING: '部署中',
  WAITING_USER: '等待操作',
  VERIFYING: '自测中',
  PHONE_VERIFIED: '手机已验证',
  COMPLETE: '已完成',
  DISABLING: '关闭中',
  DISABLED: '已关闭',
  SWITCHING: '切换中',
  FAILED: '需要处理'
};

function toast(message, isError) {
  const el = $('toast');
  el.textContent = message;
  el.className = 'om-toast' + (isError ? ' is-error' : '');
  clearTimeout(el._timer);
  el._timer = setTimeout(() => { el.className = 'om-toast om-hidden'; }, 4600);
}

async function api(path, options) {
  const opts = Object.assign({ credentials: 'same-origin', headers: { 'Content-Type': 'application/json' } }, options || {});
  const response = await fetch(path, opts);
  if (response.status === 401) {
    toast('请从安装器打开的页面进入向导。', true);
    throw new Error('unauthorized');
  }
  let data = null;
  try { data = await response.json(); } catch (e) { data = { ok: false, code: 'BAD_RESPONSE', message: '服务器返回了无法解析的内容。' }; }
  if (!response.ok) {
    data = Object.assign({}, data, { ok: false, httpStatus: response.status });
  }
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

function goHome() {
  homeRequested = true;
  showScreen('welcome');
}

function applyProbeMode(mode) {
  const value = (mode === 'direct' || mode === 'system') ? mode : 'auto';
  document.querySelectorAll('[data-probe-mode]').forEach((btn) => {
    const active = btn.getAttribute('data-probe-mode') === value;
    btn.classList.toggle('is-active', active);
    btn.setAttribute('aria-pressed', active ? 'true' : 'false');
  });
}

async function setProbeMode(mode) {
  try {
    const data = await postAction('set-public-probe-mode', { mode });
    if (data.ok) {
      applyProbeMode(mode);
      if (currentState) currentState.publicProbeMode = mode;
      toast('已切换，下次自测生效。');
    } else {
      toast(data.message || '切换失败。', true);
    }
  } catch (error) {
    toast('切换失败：' + error.message, true);
  }
}

function updateSteps(screen) {
  const order = ['env', 'tailscale', 'admin', 'deploy', 'verify', 'complete'];
  let activeIndex = -1;
  if (screen === 'env') activeIndex = 0;
  else if (screen === 'tailscale' || screen === 'security') activeIndex = 1;
  else if (screen === 'admin') activeIndex = 2;
  else if (screen === 'deploy') activeIndex = 3;
  else if (screen === 'verify') activeIndex = 4;
  else if (screen === 'complete') activeIndex = 5;
  document.querySelectorAll('.om-step').forEach((el, index) => {
    el.classList.remove('is-active', 'is-done');
    if (activeIndex >= 0 && index < activeIndex) el.classList.add('is-done');
    if (activeIndex >= 0 && index === activeIndex) el.classList.add('is-active');
  });
}

function setHeader(state) {
  const el = $('header-state');
  if (!state) { el.textContent = '正在检查'; el.className = 'om-pill om-pill-neutral'; return; }
  const step = state.step || '';
  if (step === 'PHONE_VERIFIED') { el.textContent = '手机已验证'; el.className = 'om-pill om-pill-ok'; return; }
  if (step === 'COMPLETE' || (state.publicSyncUrl && step !== 'DISABLED')) {
    el.textContent = '已部署'; el.className = 'om-pill om-pill-ok'; return;
  }
  if (step === 'DISABLED') { el.textContent = '已关闭'; el.className = 'om-pill om-pill-warn'; return; }
  if (step === 'FAILED') { el.textContent = '需要处理'; el.className = 'om-pill om-pill-error'; return; }
  if (step === 'WAITING_USER' || step === 'NEEDS_USER' || step === 'WAITING_REBOOT') {
    el.textContent = '等待操作'; el.className = 'om-pill om-pill-warn'; return;
  }
  if (step === 'DEPLOYING' || step === 'VERIFYING' || step === 'SWITCHING' || step === 'INSTALLING_DEPS' || step === 'CHECKING' || step === 'DISABLING') {
    el.textContent = '进行中'; el.className = 'om-pill om-pill-warn'; return;
  }
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

function placeholderRow(message) {
  const row = document.createElement('div');
  row.className = 'om-check unknown';
  const dot = document.createElement('span'); dot.className = 'om-check-dot';
  const body = document.createElement('div'); body.className = 'om-check-body';
  const title = document.createElement('div'); title.className = 'om-check-title'; title.textContent = message;
  body.appendChild(title); row.appendChild(dot); row.appendChild(body);
  return row;
}

function renderChecks(containerId, checks) {
  const container = $(containerId);
  if (!container) return;
  container.innerHTML = '';
  const list = checks || [];
  if (list.length === 0) { container.appendChild(placeholderRow('没有结果。')); return; }
  list.forEach((check) => container.appendChild(checkRow(check)));
}

function statusOk(checks) {
  return !(checks || []).some((check) => check.status === 'fail');
}

function renderProgress(active) {
  const stages = [
    { id: 'compose', label: '启动清衡服务' },
    { id: 'owner', label: '创建管理员' },
    { id: 'funnel', label: '开启公网入口' },
    { id: 'dns', label: '等待公网地址' },
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

async function refreshState(full) {
  const data = await api('/api/status' + (full ? '?full=1' : ''));
  if (data && data.state) {
    currentState = data.state;
    setHeader(currentState);
    applyProbeMode(currentState.publicProbeMode);
  }
  return data;
}

function pollJob(jobId, onDone, onProgress) {
  stopPolling();
  pollFailures = 0;
  jobTimer = setInterval(async () => {
    try {
      const job = await api('/api/jobs/' + jobId);
      pollFailures = 0;
      if (onProgress) onProgress(job);
      if (job.status === 'done' || job.status === 'failed') {
        stopPolling();
        onDone(job.result || { ok: false, code: 'JOB_FAILED', message: '后台任务失败，请查看日志。' });
      }
    } catch (error) {
      pollFailures += 1;
      if (pollFailures >= 5) {
        stopPolling();
        onDone({ ok: false, code: 'POLL_FAILED', message: '无法读取任务进度：' + error.message });
      }
    }
  }, 900);
}

function startProgressWatch() {
  if (progressTimer) clearInterval(progressTimer);
  progressTimer = setInterval(async () => {
    try {
      const data = await refreshState();
      if (data.state && data.state.progress && data.state.progress.stage) {
        renderProgress(data.state.progress.stage);
      }
    } catch (e) { }
  }, 1600);
}

async function runCheck(targetScreen) {
  homeRequested = false;
  const screen = targetScreen || 'env';
  showScreen(screen);
  const containerId = screen === 'tailscale' ? 'ts-checks' : 'env-checks';
  const container = $(containerId);
  if (container) { container.innerHTML = ''; container.appendChild(placeholderRow('正在检查…')); }
  try {
    const result = await postAction('check');
    if (!result.jobId) { toast(result.message || '检查没有启动。', true); return; }
    pollJob(result.jobId, (data) => {
      const checks = data.checks || [];
      const tsChecks = checks.filter((check) => check.id === 'tailscale');
      const envChecks = checks.filter((check) => check.id !== 'tailscale');
      renderChecks('env-checks', envChecks);
      renderChecks('ts-checks', tsChecks);
      $('btn-env-next').disabled = !statusOk(envChecks);
      $('btn-ts-next').disabled = !statusOk(tsChecks);
      if (data.ok) toast('环境检查通过。');
      else toast('还有未通过的项目，请按提示处理。', true);
    });
  } catch (error) {
    renderChecks(containerId, []);
  }
}

function applyDockerLicenseInfo(recorded) {
  dockerLicenseRecorded = !!(recorded === true);
  const row = $('docker-license-row');
  const note = $('docker-license-recorded');
  const ack = $('ack-docker-license');
  if (row) row.classList.toggle('om-hidden', dockerLicenseRecorded);
  if (note) note.classList.toggle('om-hidden', !dockerLicenseRecorded);
  if (ack && dockerLicenseRecorded) ack.checked = true;
}

async function installDeps() {
  homeRequested = false;
  const ack = $('ack-docker-license');
  if (!dockerLicenseRecorded && !(ack && ack.checked)) {
    toast('请先勾选 Docker 许可确认，再安装依赖。', true);
    if (ack) ack.focus();
    return;
  }
  const button = $('btn-install-deps');
  button.disabled = true;
  toast('如果弹出 Windows 管理员确认（UAC），请点“是”。');
  try {
    const result = await postAction('install-deps', { licenseAcknowledged: dockerLicenseRecorded || !!(ack && ack.checked) });
    if (!result.jobId) { toast(result.message || '安装没有启动。', true); return; }
    pollJob(result.jobId, (data) => {
      button.disabled = false;
      if (data.pendingReboot) {
        toast('依赖已安装，需要重启电脑后继续。', true);
        showScreen('welcome');
        return;
      }
      if (data.ok) { toast('依赖安装完成，正在重新检查。'); runCheck('env'); }
      else {
        if (data.code === 'DOCKER_LICENSE_REQUIRED') {
          applyDockerLicenseInfo(false);
          toast(data.message || '需要先确认 Docker 许可。', true);
          const box = $('ack-docker-license');
          if (box) box.focus();
          return;
        }
        toast(data.message || '依赖安装失败。', true);
      }
    }, () => {});
  } catch (error) {
    button.disabled = false;
    toast(error.message || '安装失败。', true);
  }
}

async function tailscaleLogin() {
  try {
    const result = await postAction('login-tailscale');
    if (!result.jobId) return;
    pollJob(result.jobId, (data) => {
      if (data.ok) toast('已打开 Tailscale 登录，请在浏览器完成。');
      else toast(data.message || '无法打开 Tailscale 登录。', true);
    });
  } catch (error) {
    toast(error.message || '无法打开 Tailscale 登录。', true);
  }
}

function nextFromEnv() { homeRequested = false; showScreen('tailscale'); runCheck('tailscale'); }
function nextFromSecurity() { homeRequested = false; showScreen('admin'); }

function nextFromAdmin() {
  homeRequested = false;
  const user = $('admin-user').value.trim();
  const password = $('admin-password').value;
  const password2 = $('admin-password2').value;
  if (!user) { toast('请填写用户名。', true); return; }
  if (password.length < 8) { toast('密码至少 8 位。', true); return; }
  if (password !== password2) { toast('两次密码不一致。', true); return; }
  showScreen('deploy');
  renderProgress(null);
}

function showVerify(checks, serverId) {
  renderChecks('verify-checks', checks);
  $('verify-server-id').textContent = serverId || '尚未读取';
  const ok = statusOk(checks);
  const httpZero = !ok && (checks || []).some((check) => /HTTP 0/.test(check.detail || ''));
  if (httpZero) {
    toast('公网访问不通：可在“公网自测网络”里切换到“系统代理”后重新自测。', true);
  }
  $('verify-state').textContent = ok ? '通过' : '需要处理';
  $('verify-state').className = 'om-pill ' + (ok ? 'om-pill-ok' : 'om-pill-error');
  $('btn-verify-continue').disabled = !ok;
  if (homeRequested) {
    // The user returned to the main screen; keep the job running but do not
    // yank them away from where they are.
    toast('后台任务已完成。点“查看运行状态”查看结果。');
    return;
  }
  showScreen('verify');
}

function handleDeployResult(data) {
  $('btn-deploy').disabled = false;
  $('btn-deploy-retry').disabled = false;
  if (data.ok && data.dryRun) {
    renderProgress('complete');
    $('deploy-state').textContent = '演练完成';
    $('deploy-state').className = 'om-pill om-pill-ok';
    showVerify(data.checks || [], data.serverId);
    toast('演练完成：没有安装依赖、启动容器或开启 Funnel。');
    return;
  }
  if (data.ok) {
    renderProgress('complete');
    $('deploy-state').textContent = '完成';
    $('deploy-state').className = 'om-pill om-pill-ok';
    showVerify(data.checks || [], data.serverId);
    toast('部署完成，正在显示自测结果。');
    return;
  }
  if (data.code === 'FUNNEL_APPROVAL_REQUIRED' || data.code === 'FUNNEL_PENDING' || (data.action === 'open_url' && data.approvalUrl)) {
    $('deploy-state').textContent = '等待授权';
    $('deploy-state').className = 'om-pill om-pill-warn';
    $('deploy-action-box').classList.remove('om-hidden');
    $('deploy-action-text').textContent = data.message || '需要在浏览器里允许 Tailscale Funnel。';
    $('deploy-action-link').href = data.approvalUrl || 'https://login.tailscale.com/admin/machines';
    $('deploy-action-link').textContent = '打开授权页';
    $('btn-approve-funnel').classList.remove('om-hidden');
    toast('请在浏览器里点“允许”，然后回来点“我已完成授权，继续”。', true);
    return;
  }
  renderProgress('failed');
  $('deploy-state').textContent = '需要处理';
  $('deploy-state').className = 'om-pill om-pill-error';
  toast(data.message || '部署失败，请查看日志。', true);
  if (data.manualUrl) {
    $('deploy-action-box').classList.remove('om-hidden');
    $('deploy-action-text').textContent = (data.message || '') + ' 请在浏览器完成 Wallos 管理员注册，然后回来重试。';
    $('deploy-action-link').href = data.manualUrl;
    $('deploy-action-link').textContent = '打开 Wallos 注册页';
    $('btn-approve-funnel').classList.add('om-hidden');
  }
  if (data.checks) renderChecks('verify-checks', data.checks);
}

async function startDeploy() {
  homeRequested = false;
  const user = $('admin-user').value.trim();
  const password = $('admin-password').value;
  if (!user || password.length < 8) { toast('请先完成管理员账号设置。', true); showScreen('admin'); return; }
  $('btn-deploy').disabled = true;
  $('btn-deploy-retry').disabled = true;
  $('btn-approve-funnel').classList.add('om-hidden');
  $('deploy-action-box').classList.add('om-hidden');
  $('deploy-state').textContent = '部署中';
  $('deploy-state').className = 'om-pill om-pill-warn';
  renderProgress('compose');
  startProgressWatch();
  try {
    const result = await postAction('deploy', {
      adminUser: user,
      adminPassword: password,
      adminEmail: $('admin-email').value.trim(),
      ackPublic: $('ack-public').checked,
      skipFunnel: false
    });
    if (!result.jobId) { throw new Error(result.message || '部署没有启动。'); }
    pollJob(result.jobId, (data) => {
      stopPolling();
      handleDeployResult(data);
    }, (job) => {
      if (job.progress && job.progress.stage) renderProgress(job.progress.stage);
    });
  } catch (error) {
    stopPolling();
    $('btn-deploy').disabled = false;
    $('btn-deploy-retry').disabled = false;
    $('deploy-state').textContent = '失败';
    $('deploy-state').className = 'om-pill om-pill-error';
    toast(error.message || '部署失败。', true);
  }
}

async function approveFunnel() {
  homeRequested = false;
  $('btn-approve-funnel').disabled = true;
  toast('正在确认 Funnel 授权…');
  try {
    const result = await postAction('approve-funnel');
    if (!result.jobId) { toast(result.message || '无法确认授权。', true); return; }
    pollJob(result.jobId, (data) => {
      $('btn-approve-funnel').disabled = false;
      if (data.ok) {
        $('deploy-action-box').classList.add('om-hidden');
        showVerify(data.checks || [], data.serverId);
        toast('Funnel 已开启，自测通过。');
      } else {
        toast(data.message || '还没有检测到授权，请稍后重试。', true);
      }
    });
  } catch (error) {
    $('btn-approve-funnel').disabled = false;
    toast(error.message || '无法确认授权。', true);
  }
}

async function runVerifyAgain() {
  homeRequested = false;
  $('btn-verify-again').disabled = true;
  try {
    const result = await postAction('verify');
    if (!result.jobId) { toast(result.message || '自测没有启动。', true); return; }
    pollJob(result.jobId, (data) => {
      $('btn-verify-again').disabled = false;
      showVerify(data.checks || [], data.serverId);
      toast(data.ok ? '自测通过。' : '还有未通过的项目。', !data.ok);
    });
  } catch (error) {
    $('btn-verify-again').disabled = false;
    toast(error.message || '自测失败。', true);
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

async function loadQr(force, target) {
  if (force) { clearTimeout(pairTimer); pairTimer = null; }
  const ids = target || { container: 'pair-qr', hint: 'pair-qr-hint' };
  const container = $(ids.container);
  if (!container) return;
  container.innerHTML = '';
  container.classList.remove('is-hidden');
  $(ids.hint).textContent = '二维码 10 分钟后自动隐藏，只在本机显示。';
  try {
    const data = await api('/api/qr');
    if (ids.serverId) $(ids.serverId).textContent = data.serverId || '尚未读取';
    if (!data.ok || !data.payload) {
      const message = document.createElement('p');
      message.textContent = data.message || '还没有配对信息。';
      container.appendChild(message);
      if (data.code === 'SERVER_ID_UNAVAILABLE') {
        const repair = document.createElement('button');
        repair.type = 'button';
        repair.className = 'om-btn om-btn-ghost';
        repair.textContent = '一键修复并重试';
        repair.addEventListener('click', () => repairAndReloadQr(repair, ids));
        container.appendChild(repair);
      }
      return;
    }
    if (typeof QRCode === 'function') {
      new QRCode(container, { text: data.payload, width: 220, height: 220, correctLevel: QRCode.CorrectLevel.M });
    } else {
      container.textContent = '二维码库未加载，请重新打开向导。';
      return;
    }
    clearTimeout(pairTimer);
    pairTimer = setTimeout(() => {
      container.innerHTML = '';
      container.classList.add('is-hidden');
      $(ids.hint).textContent = '二维码已隐藏。需要时点“重新显示二维码”。';
    }, 10 * 60 * 1000);
  } catch (error) {
    container.textContent = '无法读取配对信息。';
  }
}

async function repairAndReloadQr(button, ids) {
  button.disabled = true;
  button.textContent = '正在修复服务…';
  try {
    const result = await postAction('repair');
    if (!result.jobId) {
      button.disabled = false;
      button.textContent = '一键修复并重试';
      toast(result.message || '修复任务没有启动。', true);
      return;
    }
    pollJob(result.jobId, (jobResult) => {
      if (jobResult.ok) {
        toast('服务已修复，正在重新生成二维码。');
        refreshState().catch(() => {});
        loadQr(true, ids);
      } else {
        button.disabled = false;
        button.textContent = '一键修复并重试';
        toast(jobResult.message || '修复失败，请查看运行状态。', true);
      }
    });
  } catch (error) {
    button.disabled = false;
    button.textContent = '一键修复并重试';
    toast(error.message || '修复失败。', true);
  }
}

function statusQrDeployed() {
  const step = currentState && currentState.step;
  return ['COMPLETE', 'PHONE_VERIFIED', 'DISABLED'].indexOf(step) >= 0;
}

function updateStatusQr(force) {
  const state = currentState || {};
  $('status-public-url').textContent = state.publicSyncUrl || '未开启';
  const container = $('status-pair-qr');
  const hint = $('status-pair-qr-hint');
  if (!statusQrDeployed()) {
    clearTimeout(pairTimer);
    container.innerHTML = '';
    container.classList.add('is-hidden');
    hint.textContent = '还没有配对信息。完成部署后这里会显示二维码。';
    $('status-server-id').textContent = '尚未读取';
    $('btn-status-qr-refresh').classList.add('om-hidden');
    return;
  }
  $('btn-status-qr-refresh').classList.remove('om-hidden');
  loadQr(!!force, { container: 'status-pair-qr', hint: 'status-pair-qr-hint', serverId: 'status-server-id' });
}

function statusLines(data) {
  const state = data.state || {};
  const watchdog = data.watchdog || {};
  return [
    ['部署状态', STEP_LABELS[state.step] || state.step || '未知'],
    ['公网地址', state.publicSyncUrl || '未开启'],
    ['手机', state.phoneVerified ? '已验证' : '未验证'],
    ['看门狗', watchdog.state === 'paused' ? '已暂停' : (watchdog.installed ? '运行中' : '未安装')],
    ['端口', `API ${state.ports ? state.ports.api : '?'} / Wallos ${state.ports ? state.ports.wallos : '?'} / 网关 ${state.ports ? state.ports.gateway : '?'}`],
    ['最近检查', state.lastCheck && state.lastCheck.at ? new Date(state.lastCheck.at).toLocaleString() : '暂无']
  ];
}

async function showStatus() {
  showScreen('status');
  try {
    // Ask the local API whether a phone has synced since the last check; a
    // failure here must not block the status screen.
    try { await postAction('phone-status'); } catch (e) { }
    const data = await refreshState(true);
    const summary = $('status-summary');
    summary.innerHTML = '';
    statusLines(data).forEach(([label, value]) => {
      const row = document.createElement('div');
      row.className = 'om-check';
      const body = document.createElement('div'); body.className = 'om-check-body';
      const title = document.createElement('div'); title.className = 'om-check-title'; title.textContent = label;
      const detail = document.createElement('div'); detail.className = 'om-check-detail'; detail.textContent = value;
      body.appendChild(title); body.appendChild(detail); row.appendChild(body); summary.appendChild(row);
    });
    (data.drift || []).forEach((issue) => {
      const row = document.createElement('div');
      row.className = 'om-check fail';
      const body = document.createElement('div'); body.className = 'om-check-body';
      const title = document.createElement('div'); title.className = 'om-check-title'; title.textContent = '状态不一致';
      const detail = document.createElement('div'); detail.className = 'om-check-detail'; detail.textContent = issue;
      body.appendChild(title); body.appendChild(detail); row.appendChild(body); summary.appendChild(row);
    });
    await refreshLogs();
    updateStatusQr();
  } catch (error) {
    $('status-summary').textContent = '无法读取状态。';
  }
}

async function refreshLogs() {
  try {
    const data = await api('/api/logs?tail=200');
    const lines = data.lines || [];
    $('status-logs').textContent = lines.join('\n') || '暂无日志。';
    $('log-lines').textContent = lines.join('\n') || '暂无日志。';
  } catch (error) {
    $('status-logs').textContent = '无法读取日志。';
  }
}

async function runSimpleAction(name, successMessage) {
  try {
    const result = await postAction(name);
    if (!result.jobId) { toast(result.message || '操作没有启动。', true); return; }
    pollJob(result.jobId, (data) => {
      if (data.ok) toast(successMessage || '完成。');
      else toast(data.message || '操作失败。', true);
      refreshState().catch(() => {});
      if (name === 'disable' || name === 'enable' || name === 'repair') showStatus();
    });
  } catch (error) {
    toast(error.message || '操作失败。', true);
  }
}

async function backup() {
  try {
    const result = await postAction('backup');
    if (!result.jobId) { toast(result.message || '备份没有启动。', true); return; }
    toast('正在备份，完成后会提示。');
    pollJob(result.jobId, (data) => {
      if (data.ok) toast('备份完成。');
      else toast(data.message || '备份失败。', true);
    });
  } catch (error) {
    toast(error.message || '备份失败。', true);
  }
}

/* ---- Offline bundle card (thread E): keep changes inside this block ---- */
let offlineImportRunning = false;

function offlineFileName(path) {
  const parts = String(path || '').split(/[\\/]/).filter(Boolean);
  return parts.length ? parts[parts.length - 1] : '';
}

function offlineSetPill(text, kind) {
  const pill = $('offline-pill');
  if (!pill) return;
  pill.textContent = text;
  pill.className = 'om-pill ' + (kind || 'om-pill-neutral');
}

function offlineSetStatus(text, isError) {
  const status = $('offline-status');
  if (!status) return;
  status.textContent = text;
  status.style.color = isError ? 'var(--om-danger)' : '';
}

function offlineFormatSize(bytes) {
  if (!bytes || isNaN(bytes)) return '大小以 Release 为准';
  return '约 ' + Math.round(bytes / (1024 * 1024)) + ' MiB';
}

function applyOfflineInfo(info) {
  if (!info) return;
  const link = $('offline-download');
  if (link && info.url) {
    link.href = info.url;
    link.textContent = '下载 ' + (info.assetName || '离线镜像包');
  }
  if (info.assetName) {
    const name = $('offline-asset-name'); if (name) name.textContent = info.assetName;
    const pathInput = $('offline-path');
    if (pathInput && !pathInput.value) pathInput.placeholder = 'D:\\Downloads\\' + info.assetName;
  }
  const sha = $('offline-sha');
  if (sha) {
    sha.textContent = info.sha256 || '未知版本：请以 Release 旁的 .sha256 为准';
    sha.title = info.sha256 || '';
  }
  const size = $('offline-size');
  if (size) size.textContent = offlineFormatSize(info.sizeBytes);
}

function renderOfflineState(state) {
  if (!state) return;
  if (state.offlineImported) {
    offlineSetPill('已导入', 'om-pill-ok');
    const version = state.offlineBundleVersion ? (' ' + state.offlineBundleVersion) : '';
    offlineSetStatus('离线镜像包' + version + '已导入；下一次部署会使用 --no-build，不再重新构建镜像。');
    const button = $('btn-offline-import');
    if (button) button.textContent = '重新导入';
  }
}

async function refreshOfflineState() {
  try {
    const data = await api('/api/status');
    if (data && data.state) renderOfflineState(data.state);
  } catch (error) { }
}

async function importOfflinePath(path) {
  const clean = String(path || '').trim().replace(/^["']|["']$/g, '');
  if (!clean) { toast('请先选择离线镜像包。', true); return; }
  if (offlineImportRunning) { toast('离线镜像包正在导入，请等待完成。'); return; }
  const choose = $('btn-offline-import');
  const manual = $('btn-offline-path-import');
  offlineImportRunning = true;
  if (choose) choose.disabled = true;
  if (manual) manual.disabled = true;
  const finish = () => {
    offlineImportRunning = false;
    if (choose) choose.disabled = false;
    if (manual) manual.disabled = false;
  };
  offlineSetPill('导入中', 'om-pill-warn');
  offlineSetStatus('正在校验并加载 ' + offlineFileName(clean) + '，可能需要几分钟，请不要关闭页面。');
  try {
    const started = await postAction('import-offline', { path: clean });
    if (!started.jobId) {
      offlineSetPill('需要处理', 'om-pill-error');
      offlineSetStatus(started.message || '导入没有启动。', true);
      toast(started.message || '导入没有启动。', true);
      finish();
      return;
    }
    pollJob(started.jobId, (data) => {
      finish();
      if (data.ok) {
        if (data.dryRun) {
          offlineSetPill('演练通过', 'om-pill-warn');
          offlineSetStatus('演练通过：SHA256 和压缩包结构有效，但没有解压或加载镜像。');
          toast('演练通过：没有加载镜像。');
          return;
        }
        offlineSetPill('已导入', 'om-pill-ok');
        const version = data.version ? (' ' + data.version) : '';
        offlineSetStatus('离线镜像包' + version + '已导入；下一次部署会使用 --no-build。');
        toast('离线镜像包已导入，可以继续部署。');
        refreshOfflineState().catch(() => {});
      } else {
        offlineSetPill('需要处理', 'om-pill-error');
        const detail = data.file ? ('（' + data.file + '）') : '';
        offlineSetStatus((data.message || '导入失败。') + detail, true);
        toast(data.message || '导入失败。', true);
      }
    }, (job) => {
      if (job && job.progress && job.progress.message) offlineSetStatus(job.progress.message);
    });
  } catch (error) {
    offlineSetPill('需要处理', 'om-pill-error');
    offlineSetStatus(error.message || '导入失败。', true);
    toast(error.message || '导入失败。', true);
    finish();
  }
}

async function chooseOfflineFile() {
  const choose = $('btn-offline-import');
  if (choose) choose.disabled = true;
  offlineSetStatus('请在弹出的窗口里选择下载好的 OpenMargin-Offline-*.tar.gz。');
  try {
    const picked = await postAction('select-offline-file');
    if (picked.ok && picked.path) {
      await importOfflinePath(picked.path);
      return;
    }
    if (picked.code === 'PICKER_CANCELLED') {
      offlineSetStatus('已取消选择。需要时再点“我已下载，导入”。');
      return;
    }
    $('offline-path-row').classList.remove('om-hidden');
    offlineSetStatus(picked.message || '无法打开文件选择框，请手动输入完整路径。', true);
    const input = $('offline-path');
    if (input) input.focus();
  } catch (error) {
    $('offline-path-row').classList.remove('om-hidden');
    offlineSetStatus(error.message || '无法打开文件选择框，请手动输入完整路径。', true);
  } finally {
    if (choose && !offlineImportRunning) choose.disabled = false;
  }
}

function initOffline() {
  if (!$('offline-card')) return;
  $('btn-offline-import').addEventListener('click', chooseOfflineFile);
  $('btn-offline-path').addEventListener('click', () => {
    const row = $('offline-path-row');
    row.classList.toggle('om-hidden');
    if (!row.classList.contains('om-hidden')) $('offline-path').focus();
  });
  $('btn-offline-path-import').addEventListener('click', () => {
    importOfflinePath($('offline-path').value);
  });
  $('offline-path').addEventListener('keydown', (event) => {
    if (event.key === 'Enter') importOfflinePath($('offline-path').value);
  });
  api('/api/config').then((data) => {
    if (data && data.ok) applyOfflineInfo(data.offline);
  }).catch(() => {});
  refreshOfflineState();
}
/* ---- /Offline bundle card (thread E) ---- */

function loadConfig() {
  api('/api/config').then((data) => {
    if (data && data.ok) {
      currentConfig = data;
      $('version').textContent = 'v' + data.version;
      if (data.dryRun) $('dry-run-banner').classList.remove('om-hidden');
      const elevationHint = $('elevation-hint');
      if (elevationHint) elevationHint.classList.toggle('om-hidden', data.elevated !== false);
      applyDockerLicenseInfo(data.dockerLicenseAcknowledged);
    }
  }).catch(() => {});
}

async function boot() {
  loadConfig();
  try {
    const data = await refreshState();
    const state = data.state || {};
    if (state.step === 'COMPLETE' || state.step === 'PHONE_VERIFIED') {
      showScreen('complete');
      loadAppQr();
      loadQr();
      return;
    }
    if (state.step === 'DISABLED') {
      showStatus();
      return;
    }
    if (state.step === 'WAITING_USER' && state.lastError && state.lastError.step === 'funnel') {
      showScreen('deploy');
      renderProgress('funnel');
      $('deploy-action-box').classList.remove('om-hidden');
      $('deploy-action-text').textContent = state.lastError.message || '需要在浏览器里允许 Tailscale Funnel。';
      $('deploy-action-link').href = 'https://login.tailscale.com/admin/machines';
      $('btn-approve-funnel').classList.remove('om-hidden');
      return;
    }
    showScreen('welcome');
  } catch (error) {
    showScreen('welcome');
  }
}

document.addEventListener('DOMContentLoaded', () => {
  $('btn-home').addEventListener('click', goHome);
  document.querySelectorAll('[data-probe-mode]').forEach((btn) => {
    btn.addEventListener('click', () => setProbeMode(btn.getAttribute('data-probe-mode')));
  });
  applyProbeMode('auto');
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
  $('btn-approve-funnel').addEventListener('click', approveFunnel);
  $('btn-deploy-logs').addEventListener('click', async () => {
    $('log-drawer').classList.remove('om-hidden');
    await refreshLogs();
  });
  $('btn-close-logs').addEventListener('click', () => $('log-drawer').classList.add('om-hidden'));
  $('btn-verify-continue').addEventListener('click', () => {
    showScreen('complete');
    loadAppQr();
    loadQr();
  });
  $('btn-verify-again').addEventListener('click', runVerifyAgain);
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
  $('btn-status-qr-refresh').addEventListener('click', () => updateStatusQr(true));
  $('btn-status-repair').addEventListener('click', () => runSimpleAction('repair', '修复完成。'));
  $('btn-watchdog-pause').addEventListener('click', () => runSimpleAction('pause-watchdog', '看门狗已暂停 1 小时。'));
  $('btn-watchdog-resume').addEventListener('click', () => runSimpleAction('resume-watchdog', '看门狗已恢复。'));
  $('btn-rotate-token').addEventListener('click', async () => {
    if (!window.confirm('重新生成后，旧二维码立即失效，手机需要重新扫码。继续吗？')) return;
    await runSimpleAction('rotate-token', '设备密钥已更新，请重新扫码。');
    if ($('screen-status').classList.contains('is-active')) {
      updateStatusQr();
    } else {
      loadQr(true);
    }
  });
  $('btn-status-enable').addEventListener('click', () => runSimpleAction('enable', '远程同步已重新开启。'));
  initOffline(); // offline bundle card (thread E)
  boot();
});
