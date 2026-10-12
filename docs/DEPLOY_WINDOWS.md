# 清衡 Open Margin｜Windows 本地部署

**不要求 VPS、固定公网 IP 或自己的域名。** Windows + Docker Desktop 运行 Finance API、Wallos 和 SQLite；Android 仍然本地记录，电脑上线后补传和下载。

## 必备

- Windows 10/11、Docker Desktop Linux containers / WSL2、Git、PowerShell 5.1+。
- 手机需要同步时：Windows 与 Android 登录同一 Tailscale 网络，开启 MagicDNS 和 HTTPS 证书。
- 避免抢注：第一次只能在本机 localhost 注册 Wallos 管理员；完成注册后再开启 Tailscale Serve。

## 安装命令（PowerShell）

    git clone https://github.com/zxyzx0430/open-margin.git
    cd open-margin

第一次只在 Windows 本机部署（**不需要 Tailscale、域名或 HTTPS 证书**）：

    powershell -ExecutionPolicy Bypass -File .\deploy\windows\Install-OpenMargin.ps1

本地部署只提供 Web 管理；Android 仍可离线记账，但要让手机与电脑同步，必须随后配置私有 HTTPS 入口。

访问本机 http://127.0.0.1:18082/ ，注册第一位 Wallos 用户。只有需要手机同步时，再在电脑和手机安装、登录 Tailscale，然后执行：

    powershell -ExecutionPolicy Bypass -File .\deploy\windows\Install-OpenMargin.ps1 -PublicBase "https://desk-01.example-tailnet.ts.net/finance" -ConfigureTailscale

该脚本用 Tailscale Serve（**不是公开的 Funnel**）将 HTTPS 的 /finance 映射到本机 8767 端口，/finance/wallos 映射到本机 18082 端口。先用手机浏览器验证以下网址能访问：
- https://desk-01.example-tailnet.ts.net/finance/health
- https://desk-01.example-tailnet.ts.net/finance/wallos/

Android 设置 → 清衡双向同步：
- **HTTPS 服务器地址：** https://desk-01.example-tailnet.ts.net/finance
- **设备密钥：** secrets/backend.env 文件内的 FINANCE_DEVICE_TOKEN。
- 开启双向同步，点击「立即同步」。若 HTTPS 路由返回 404，不要关闭证书校验；检查 tailscale serve status 与路径重写是否正确。

**不能直接填 http://192.168.1.100:8767。** Android 的同步客户端只允许 HTTPS；局域网 IP 会变化，Tailscale 节点域名更稳定。若执意使用局域网 IP，需要自己安装可被手机信任的 HTTPS 证书和反向代理。

## 日常运行与备份

- 电脑关闭 / Docker Desktop 停止：手机离线继续自动记账，编辑和删除保存为待同步操作；重新开机联网再同步。
- WorkManager 后台任务约每 15 分钟尝试补偿同步，受 Android 省电系统影响。App 有「立即同步」按钮。
- SQLite 在 Docker 命名卷里，升级源码不会主动删库。**严禁使用 docker compose down -v**。
- 可运行：docker compose --profile maintenance run --rm backup。加密备份应另存到不同硬盘。
- 旧版 Android 已经删除、但从未记录同步删除标记的交易无法自动还原当时删除时间。第一次开启双向同步应人工核对这些历史记录。
- 如果两端对同一条交易的修改先后不明确，先保留待处理状态，在 App 设置里选「使用本机」或「使用服务器」。
- 双向范围是交易记录；Wallos 订阅和预算仍由网站管理，不是 Android 本地的完整复制。
- 签名不同的 APK 无法覆盖旧应用，不能为了更新直接卸载。

Tailscale 官方：https://tailscale.com/docs/reference/tailscale-cli/serve
