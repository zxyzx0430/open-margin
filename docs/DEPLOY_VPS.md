# 清衡 Open Margin｜VPS / Linux 部署

与 Windows 使用**同一份源代码、同一套双向同步协议**。区别只有部署主机是否长期开机及 HTTPS 访问方式。

## 新安装

需要 Linux、Docker Engine + Compose v2、自己的有效 HTTPS 域名、Nginx 或等效反向代理。安装：

    git clone https://github.com/zxyzx0430/open-margin.git
    cd open-margin
    cp .env.example .env
    python3 scripts/init-config.py --url https://your-finance-domain.example/finance --owner-id 1
    docker compose config -q
    docker compose up -d --build
    docker compose ps
    curl http://127.0.0.1:8767/health

把 deploy/nginx-locations.conf.example 的示例添加到已启用 HTTPS 的 server 块里。**不要把明文端口 8767 和 18082 暴露到公网。** 安全地注册首位 Wallos 用户后再允许外部访问登录页。

### 手机扫码配对（新版）

账本所有者登录 VPS 的清衡 Wallos 管理页面后，通过头像菜单 →「手机配对」→「生成配对二维码」显示二维码。安卓清衡 App 的「同步 → 重新配对」直接扫描；二维码使用 Windows 已有的 v1 格式（HTTPS URL、设备密钥、`server_id`）。手机先做只读身份验证，用户确认后才开始同步。

二维码仅在所有者点击后通过后端接口生成，不写入静态 HTML、日志或浏览器持久存储；页面 5 分钟后隐藏。但现有 v1 二维码携带**长期有效设备密钥**，隐藏不等于令牌过期，勿截图/分享，泄露时需安全轮换密钥并重新配对。此轮不自动轮换已有密钥以免断开已连接手机。

后台通过 Wallos 的服务器端密钥访问受保护的 Finance API；不得将密钥或配对 JSON 暴露到公开页面或 CDN 缓存。生产升级需重建 Finance API 和 Wallos 镜像，但不能重建数据库卷。

**手动备用方式：**

Android 设置 → 双向同步：
- **服务器 HTTPS 地址：** https://your-finance-domain.example/finance
- **设备密钥：** secrets/backend.env 内的 FINANCE_DEVICE_TOKEN
- 发送一笔合成测试记录，检查服务器保存；随后在网站上修改商户/金额并验证下载到手机，再测试手机删除及服务器的删除墓碑。

## 现有 VPS 升级

生产部署不能直接重建数据库。先使用 SQLite 在线备份交易库、Wallos 库和 OAuth 库，验证完整性；用隔离测试库跑回归、保留当前容器镜像和代码回滚点，再把精确版本部署上线。详见 docs/SINGLE_SOURCE_OPERATIONS.md。

双向同步会新增服务器同步日志和操作幂等表，但不会覆盖旧交易主表，也不会改变你已有的 Wallos 订阅与动态汇率。

## 同步语义

- 手机和网站的新增、修改、删除都通过递增版本日志同步；被删除交易有墓碑，旧客户端重试无法复活。
- 冲突按修改时间和版本判断；时钟偏差或近同时发生的冲突保留待确认，不会静默覆盖。
- 电脑/VPS 离线时 Android 本地记录照常产生；网络恢复后上传和下载。
- 手机和服务器之间只双向复制**交易**。订阅计划、服务器预算与 OAuth 配置由各自原有组件管理。
- Windows 部署与 VPS 部署是二选一，使用相同的 Android HTTPS URL 配置界面；两台不同服务器不会自动合并成一份账本。
