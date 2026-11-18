<!-- open-margin-components: android,server -->

# 清衡 1.41

`versionCode 42` / `versionName "1.41"`

## 手机扫码配对

- 安卓扫码接受任意标准 HTTPS 域名（`https://你的域名/finance`），不再只认 `*.ts.net`；VPS 与 Windows 走同一套配对流程
- 校验仍是白名单式：必须 HTTPS、无端口、无账号密码、无查询串和锚点、路径必须是 `/finance`；IP 直连和 `.local` 一律拒绝
- VPS 的 Wallos 后台新增「手机配对」页：账本所有者登录后点「生成配对二维码」，扫码即完成配对
- 二维码只由后端临时生成，不写进静态页面、日志或浏览器存储；页面 5 分钟后自动隐藏
- 仅账本所有者（`FINANCE_WALLOS_OWNER_ID`）能生成二维码，普通 Wallos 用户看不到入口也拿不到数据

## 同步地址

- 配对二维码里的同步地址可用 `FINANCE_SYNC_PUBLIC_URL` 单独指定，不设置时回落到 `FINANCE_PUBLIC_URL`
- Windows 端取账本身份时先问本机服务，公网 DNS 或代理异常不再阻断配对

## 其他修复

- 修复 Wallos service worker 语法错误（配对页不会被缓存，也不再阻塞整个离线缓存）
- 修复配对页重复 `<main>` 导致的页面结构错误
- 初始化脚本拒绝带端口的 URL，避免生成手机端会拒绝的二维码
- Windows 一键部署：ACL 改为按安装用户 SID 授权、启动器支持恢复流程、离线包与看门狗脚本加固

## 已知

- 二维码里是长期有效的设备密钥，隐藏不等于失效；泄露需在服务器轮换 `FINANCE_DEVICE_TOKEN` 并重新配对
- 通知点击不跳账单详情
- 更新包约 120MB，不支持断点续传
