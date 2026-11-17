清衡 Open Margin Windows 安装说明
==================================

这个安装包（OpenMargin-Setup.exe）安装的是本机部署向导和运行文件，不包含
Docker 镜像，也不包含任何密钥。Docker、Tailscale、WSL2 会在向导里按需安装。

一、Windows SmartScreen 提示
----------------------------
v1 安装包没有购买代码签名证书，Windows 可能显示“Windows 已保护你的电脑”。

如果你确认文件来自本仓库的 GitHub Releases 页面：
  1. 点击“更多信息”
  2. 点击“仍要运行”

不要因为安装器未签名就关闭整个杀毒软件或系统防护。请先用下面的 SHA256
校验文件；校验值以 Release 页面和同目录的 .sha256 文件为准。
如果来源不明，请不要运行。

二、校验 SHA256
---------------
在 PowerShell 中执行：

  Get-FileHash .\OpenMargin-Setup.exe -Algorithm SHA256

把输出的 Hash 与 Release 页面公布的 SHA256 逐字符比较。二者一致才继续。

三、安装位置与快捷方式
----------------------
- 程序目录：默认 D:\OpenMargin\app；没有 D: 时默认 E:\OpenMargin\app；都没有
  才退回系统盘（如 C:\OpenMargin\app）。安装向导会显示“选择安装位置”页面，
  可以改成任意有空间的磁盘。
- 安装向导会分别检查安装盘和 Docker 数据盘：安装盘至少 2GB（建议 5GB），
  Docker 数据盘至少 3GB（建议 5GB）。磁盘不足时会明确显示是哪一个盘。
- 开始菜单：
  - “清衡设置”：打开本机设置向导（http://127.0.0.1:18770/ 起）
  - “清衡账本”：打开本机账本 http://127.0.0.1:18082/
- 安装完成后会自动打开“清衡设置”向导并打开浏览器，表示安装成功；
  如果浏览器没有自动打开，可从开始菜单点“清衡设置”。
- 向导服务器以管理员权限运行（复用安装器的一次 UAC），这样配置 Docker、
  Tailscale 和 URL ACL 时不再反复提权；浏览器通过 explorer.exe 以当前普通用户
  打开，不会变成管理员浏览器。向导只监听 127.0.0.1，并使用一次性 token。
- 自动化/无人值守安装可给 `OpenMargin-Setup.exe` 加 `/NOAUTOSTART`，安装后不自动
  启动向导；加 `/NOELEVATE` 则按旧方式以当前用户权限启动向导（后续依赖安装
  可能再次弹 UAC）。
- 安装器不会覆盖已有的 secrets 目录或 Docker 数据卷。

四、管理员权限与重启
--------------------
- 安装器需要管理员权限（一次 UAC）。
- 向导安装 WSL2 / Docker Desktop / Tailscale 时会再请求一次 UAC；拒绝 UAC
  不会安装任何东西，可以重试。
- WSL2 / Docker 首次安装后可能需要重启。重启后从开始菜单重新打开“清衡设置”，
  向导会读取已保存的状态继续。v1 尚未实现重启后自动弹出向导。

五、网络与 Tailscale
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

六、离线镜像包
--------------
国内网络拉取 Docker 镜像失败时，可另外下载 Release 中的离线镜像包。
它是独立资产，不在安装器内。校验 SHA256 后解压并执行：

  docker load -i .\images.tar

当前 v1 网页向导仍以 `docker compose up --build` 启动；离线包可以避免重复下载镜像。
命令行部署支持 `open-margin-setup.ps1 deploy --no-build` 直接使用已加载的镜像；
完全离线的网页向导安装尚未验证。

七、日志与数据
--------------
- 向导/引擎日志：%LOCALAPPDATA%\OpenMargin\logs
- Inno Setup 安装日志：%TEMP%\Setup Log*.txt（临时目录，不长期保留）
- 账本数据在 Docker 命名卷中；卸载程序默认不删除数据卷。
- 日志和状态文件不应包含设备令牌或管理员密码。发现泄露请立即轮换设备密钥。

八、更多文档
------------
- docs\DEPLOY_WINDOWS.md
- docs\WINDOWS_PRIVATE_FIRST_CONNECTION.md（私有 Tailscale Serve CLI，保留给高级用户）
- GitHub Releases: https://github.com/zxyzx0430/open-margin/releases
