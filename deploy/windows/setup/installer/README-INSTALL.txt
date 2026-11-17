清衡 Open Margin Windows 安装说明
==================================

这个安装包（OpenMargin-Setup.exe）安装的是本机部署向导和运行文件，不包含
Docker 镜像，也不包含任何密钥。Docker、Tailscale、WSL2 会在向导里按需安装。

安装器界面默认中文，也可以在安装器中选择 English。安装前请先阅读同目录的
REQUIREMENTS.txt；Docker Desktop 的许可说明见 DockerDesktopNotice.txt，
安装器启动后也会显示“Docker Desktop 许可与依赖说明”页。

一、系统要求
------------
- 64 位 Windows 10 21H2（内部版本 19044）或更高版本；推荐 Windows 10
  22H2（19045）或 Windows 11。低于 19044 的系统会被安装器阻止。
- BIOS/UEFI 中开启硬件虚拟化（Intel VT-x / AMD-V）。
- 系统盘至少 15 GB 可用空间，建议 30 GB 以上。
- 管理员账户和一次 UAC 授权；向导安装依赖时会再请求一次 UAC。
- 可访问 Docker Hub、GitHub 和 Tailscale 的网络；国内网络失败时用离线包。

完整的硬件、网络、Docker/Tailscale 和迁移说明见 REQUIREMENTS.txt。

二、Windows SmartScreen 提示
----------------------------
v1 安装包没有购买代码签名证书，Windows 可能显示“Windows 已保护你的电脑”。

如果你确认文件来自本仓库的 GitHub Releases 页面：
  1. 点击“更多信息”
  2. 点击“仍要运行”

不要因为安装器未签名就关闭整个杀毒软件或系统防护。请先用下面的 SHA256
校验文件；校验值以 Release 页面和同目录的 .sha256 文件为准。
如果来源不明，请不要运行。

三、校验 SHA256
---------------
在 PowerShell 中执行：

  Get-FileHash .\OpenMargin-Setup.exe -Algorithm SHA256

把输出的 Hash 与 Release 页面公布的 SHA256 逐字符比较。二者一致才继续。

四、Docker Desktop 许可
-----------------------
- 安装器本身不安装 Docker Desktop；向导中的“一键安装依赖”可能会安装。
- 向导调用 Docker 官方安装程序时会传入 --accept-license，即代表你接受
  Docker 的许可条款。安装器会在安装前显示说明并要求勾选确认；未勾选不能
  继续。只有确认后安装器才会写入 docker-license-ack.txt；向导在缺少该文件
  时会拒绝带 --accept-license 安装，并提示你先确认许可。
- Docker Desktop 通常对个人使用、教育、非商业开源项目，以及员工少于
  250 人且年收入低于 1000 万美元的小型企业免费；其他商业使用需要付费
  订阅。具体条件以 Docker 官方页面为准。
- 如果你是超过上述规模的商业组织，请先购买 Docker 订阅，或使用你已获得
  授权的 Docker 安装方式。不要默认认为“安装器会自动接受”就没有许可问题。
- 你的确认会记录在安装目录的 docker-license-ack.txt（不含账号或密钥）。

Docker 官方链接：
  https://www.docker.com/legal/docker-subscription-service-agreement/
  https://www.docker.com/pricing/
  https://docs.docker.com/subscription/desktop-license/

五、安装位置、升级与迁移
------------------------
- 默认程序目录：优先 D:\OpenMargin\app；没有 D: 时默认 E:\OpenMargin\app；
  D:/E: 都没有才退回系统盘（如 C:\OpenMargin\app）。安装器会显示“选择安装
  位置”页面，可以改成任意有空间的磁盘。
- 安装器会分别检查安装盘和 Docker 数据盘：安装盘至少 2GB（建议 5GB），
  Docker 数据盘至少 3GB（建议 5GB）。磁盘不足时会明确显示是哪一个盘。
- 开始菜单：
  - “清衡设置”：打开本机设置向导（http://127.0.0.1:18770/ 起）
  - “清衡账本”：打开本机账本 http://127.0.0.1:18082/
  - “安装说明”“Docker Desktop 许可说明”：离线文档
- 安装完成后会自动打开“清衡设置”向导并打开浏览器，表示安装成功；
  如果浏览器没有自动打开，可从开始菜单点“清衡设置”。
- 向导服务器以管理员权限运行（复用安装器的一次 UAC），这样配置 Docker、
  Tailscale 和 URL ACL 时不再反复提权；浏览器通过 explorer.exe 以当前普通用户
  打开，不会变成管理员浏览器。向导只监听 127.0.0.1，并使用一次性 token。
- 自动化/无人值守安装可给 `OpenMargin-Setup.exe` 加 `/NOAUTOSTART`，安装后不自动
  启动向导；加 `/NOELEVATE` 则按旧方式以当前用户权限启动向导（后续依赖安装
  可能再次弹 UAC）。
- 安装器会记住上一次的安装目录（UsePreviousAppDir），升级/修复时优先沿用。
- 安装器不会覆盖已有的 secrets 目录或 Docker 数据卷。
- 如果你已经在别处安装过 Open Margin（例如 D:\OpenMargin\app），再装到
  新路径会留下两套程序目录、secrets 和看门狗；旧容器/命名卷不会自动迁移。
  安装器在选择目录时会再次警告。
- 换路径前建议先运行（默认只检测，不改动数据）：
    powershell -NoProfile -ExecutionPolicy Bypass -File .\deploy\windows\setup\installer\Migrate-OpenMargin.ps1
  加 -Backup -Yes 备份 secrets；加 -StopOld -Yes 停止旧容器；需要保留手机
  配对时加 -CopySecrets -Yes 把旧 secrets 复制到新目录。
- 绝对不要执行 docker compose down -v，也不要删除旧数据卷。迁移完成并
  验证前请保留旧目录。
- 卸载程序默认保留 secrets 和数据卷；卸载前请先备份。卸载会停止向导、
  看门狗和 compose 容器，并移除本程序的 Tailscale Funnel/Serve 路由与 URL ACL。

六、浏览器没有自动打开 / 单实例
-------------------------------
- 安装完成后会打开“清衡设置”本机向导，地址类似
  http://127.0.0.1:18770/（端口被占用时顺延到 18779）。
- 如果浏览器没有自动打开：向导会弹出对话框显示可点击的地址（含一次性
  token 时也会显示），也可以查看 %LOCALAPPDATA%\OpenMargin\setup-web.json
  中的 port，再手动打开 http://127.0.0.1:<port>/。
- 只允许同时运行一个向导。再次双击“清衡设置”会复用已经在运行的向导并直接
  打开它的地址；如果 setup-web.json 指向已经失效的进程，启动器会清理残留
  状态后再启动新实例。
- 不要在多人共用的电脑上把带 token 的地址发给别人。

七、管理员权限与重启
--------------------
- 安装器需要管理员权限（一次 UAC）。
- 向导安装 WSL2 / Docker Desktop / Tailscale 时会再请求一次 UAC；拒绝 UAC
  不会安装任何东西，可以重试。
- WSL2 / Docker 首次安装后可能需要重启。重启后从开始菜单重新打开“清衡设置”，
  向导会读取已保存的状态继续。v1 尚未实现重启后自动弹出向导。

八、网络与 Tailscale
--------------------
- v1 只提供“远程同步（Tailscale Funnel）”模式，向导不提供私有/远程模式选择。
- 手机不需要安装 Tailscale；手机保持商业 VPN 即可，但该 VPN 必须能访问
  https://<电脑名>.<tailnet>.ts.net/finance。
- Windows 上的 Tailscale 与商业 VPN 通常可以共存；如果 VPN 强制全局接管所有
  流量并阻断 ts.net，Funnel 会不可达。
- Funnel 仍是 Tailscale 的 beta 功能，带宽有限，只适合小数据同步；公共 DNS
  最长可能需要约 10 分钟。
- 电脑关机、休眠、Docker 停止或 Tailscale 掉线时同步暂停；手机继续本地记账，
  恢复后补传。

九、离线镜像包
--------------
国内网络拉取 Docker 镜像失败时，可另外下载 Release 中的离线镜像包。
它是独立资产，不在安装器内。打开“清衡设置” → 环境检查 → “Docker 下载失败？
使用离线镜像包”，点“我已下载，导入”并选择 .tar.gz 即可。向导会校验 SHA256、
安全解压并执行 docker load；成功后自动记录 offlineImported，下一次部署自动
使用 --no-build。也可手动：

  Get-FileHash .\OpenMargin-Offline-<版本>.tar.gz -Algorithm SHA256
  tar -xzf .\OpenMargin-Offline-<版本>.tar.gz
  docker load -i .\images.tar

十、日志与数据
--------------
- 向导/引擎日志：%LOCALAPPDATA%\OpenMargin\logs
- Inno Setup 安装日志：%TEMP%\Setup Log*.txt（临时目录，不长期保留）
- 账本数据在 Docker 命名卷中；卸载程序默认不删除数据卷。
- 日志和状态文件不应包含设备令牌或管理员密码。发现泄露请立即轮换设备密钥。

十一、更多文档
--------------
- REQUIREMENTS.txt：完整系统要求、Docker/Tailscale、迁移和离线说明
- DockerDesktopNotice.txt：Docker 订阅服务协议与免费/商业使用说明
- docs\DEPLOY_WINDOWS.md
- docs\WINDOWS_PRIVATE_FIRST_CONNECTION.md（私有 Tailscale Serve CLI，保留给高级用户）
- GitHub Releases: https://github.com/zxyzx0430/open-margin/releases
