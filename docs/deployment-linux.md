# Linux 笔记本部署与集成验收

本部署用于一台 x86_64 Linux 笔记本。前端随 Spring Boot 打包，PostgreSQL、RabbitMQ 和 PaddleOCR 分别运行在容器中。部署使用真实业务模块，关闭 POI、通知和消息演练桩。

## 1. 准备环境

CachyOS / Arch 安装并启用 Docker。

```sh
sudo pacman -Syu --needed docker docker-compose git python cloudflared
sudo systemctl enable --now docker
sudo docker compose version
```

使用 Docker 的账户需有相应权限。尚未配置普通用户 Docker 权限时，可在下面的部署命令前使用 sudo。笔记本接通电源，在 KDE 电源管理中关闭接电时自动睡眠，并根据需要调整合盖行为。

## 2. 初始化配置

在仓库根目录执行以下命令。fish 用户同样可以通过 bash 运行脚本。

```sh
bash scripts/init-env.sh
```

脚本仅在 .env 不存在时创建文件，生成数据库、管理员、四个测试账号的初始密码和 RabbitMQ 密码。通过编辑器查看本机 .env，填写配置。已有本地配置时，参考 .env.example 补齐新增项目。环境文件被 Git 忽略。

| 变量 | 用途 |
| --- | --- |
| DATABASE_PASSWORD | PostgreSQL 密码 |
| INITIAL_ADMIN_USERNAME / INITIAL_ADMIN_PASSWORD | 空库首次启动创建的管理员 |
| APP_SEED_DEMO / DEMO_PASSWORD | 为 member-a 至 member-d 创建独立测试账号，各自首次登录需改密 |
| RABBITMQ_PASSWORD | 容器内 checkcollect 用户密码 |
| MAP_PROVIDER=amap | 启用高德地图 |
| AMAP_JS_KEY | Web 端 JS API Key，页面加载 SDK 时使用 |
| AMAP_JS_SECURITY_CODE | 高德安全密钥，仅由后端代理附加 |
| OCR_PROVIDER=paddle | 启用本地 PaddleOCR |
| FRONTEND_BASE_URL | 浏览器实际访问的站点地址，本机可先用 http://localhost:8080 |
| SESSION_COOKIE_SECURE | 本机 HTTP 调试使用 false，公网 HTTPS 使用 true |
| WECHAT_APP_ID / WECHAT_APP_SECRET / WECHAT_REDIRECT_URI | 微信网站应用凭据与完整回调地址 |

部署文件将容器内部数据库、消息服务和 OCR 地址配置为服务名，照片目录配置为持久卷。Java 应用使用 17，镜像内前端构建使用 Node 24，宿主机只需要 Docker 和 Compose。

## 3. 启动和检查

```sh
bash scripts/deploy-linux.sh
docker compose -f compose.deploy.yaml ps
docker compose -f compose.deploy.yaml logs --tail=80 app
docker compose -f compose.deploy.yaml logs --tail=80 ocr
curl -I http://localhost:8080/login
```

首次构建需要下载运行镜像、Java 与 Python 依赖。PaddleOCR 首次启动还需联网下载移动端中文识别模型，缓存保存在 ocr_models 卷。待 OCR 健康检查通过后再测试识别。使用 CPU 和两个推理线程，单工作进程串行执行识别。图片识别失败时仍可人工填写采集内容。

应用只映射本机 127.0.0.1:8080。数据库、RabbitMQ 和 OCR 通过容器网络连接。RabbitMQ 创建独立用户，避免跨容器使用 guest 的限制。

从空库迁移顺序为 V1、V200、V201、V300、V400。曾执行旧 V2 的开发库先备份，使用新库或按修复方案迁移保留数据。此部署不自动重写旧迁移历史。

## 4. 手机 HTTPS 入口

临时联调可以将本机入口发布为 HTTPS 测试隧道。

```sh
cloudflared tunnel --url http://127.0.0.1:8080
```

取得地址后，将 .env 的 FRONTEND_BASE_URL 改成实际 HTTPS 地址，并设置 SESSION_COOKIE_SECURE=true，再重建应用容器配置。

```sh
docker compose -f compose.deploy.yaml up -d app
```

浏览器使用同一个公开地址登录和采集。手机通过移动网络测试访问、拍照及设备定位。临时隧道地址会变化，固定入口可配置为 collect.allwayso.top。固定入口仍需完成 DNS、网络连通性和微信平台回调域验证。

域名用于高德 JS API 时，在高德控制台核对 Key 的平台类型及域名限制。数据库保留设备原始 WGS84 坐标；地图显示与站内路线预览使用 SDK 转换后的高德坐标，外部导航 URI 由适配器将起终点转换为 GCJ-02 后生成，不依赖路径规划接口未列出的 `coordinate=wgs84` 参数。详情页先获取并展示起点、精度和距离，支持步行/驾车，在站内显示路线并保留外部导航链接；电脑端高德外部网页可能要求登录，因此不将外部跳转等同于已展示路线。

采集、整改和现场复核均先由用户拍照或通过系统文件选择器授权选图，再点击“授权并获取位置”。打开页面和选择照片本身不请求定位，也不会自动上传。定位拒绝时保留照片，换图后清除旧位置；已有浏览器授权可能不再弹窗。旧照片不会自动提供其拍摄地点，现场采集仍须使用当前设备定位。定位会短时监听高精度样本，使用最佳有效结果，并显示精度供用户核对。

## 5. 微信配置

固定 HTTPS 地址可访问后，在已有微信网站应用中核对授权回调域并配置回调。

```dotenv
FRONTEND_BASE_URL=https://collect.allwayso.top
SESSION_COOKIE_SECURE=true
WECHAT_REDIRECT_URI=https://collect.allwayso.top/api/auth/wechat/callback
```

每人用自己的团队账号登录、改密，在“我的账号”绑定个人微信，然后退出并扫码登录，核对用户 ID。公网入口、平台审核与实际微信授权均需在部署环境完成验收。

## 6. 业务验收

1. member-b 提交实际照片、定位、分类和描述。member-c 在待检列表看到同一版本，并收到新数据通知。
2. member-c 查看照片并标错。member-b 收到通知，打开实际错误单，通过“与对方讨论”进入双方私聊。member-d 无权读取该私聊。
3. member-b 上传补拍照片整改。member-c 确认通过，POI 地图显示该版本核验通过。普通更新产生新版本后重新显示待核验。
4. 另一错误单中，采集者驳回、双方交流后提交异议。member-d 收到现场任务，上传现场照片及定位。分别验证“原数据无误”和“现场替换”两种裁定。两种情况都保留现场证据，地图有效版本与裁定一致。
5. 管理员通过消息页正式公告表单群发通知。采集者和核验者分别进入对应角色群聊。普通用户无法调用消息故障接口。
6. 关闭 RabbitMQ 后提交采集，记录仍可读。恢复 RabbitMQ 后等待补投，查看消费日志确认投递。重投同一通知不会增加收件记录。

## 7. 停止、恢复和备份

```sh
docker compose -f compose.deploy.yaml stop
docker compose -f compose.deploy.yaml up -d
```

postgres_data、rabbit_data、photos 和 ocr_models 分别持久化数据库、消息、照片和模型缓存。备份时导出 PostgreSQL，并在同一备份批次保存照片。恢复到副本后核对 POI、版本、照片与用户标识，再切换使用。Compose 的 volumes 删除操作会删除对应持久数据，日常停止使用 stop。

接口与回归测试见 backend/src/test/java/edu/sadp/checkcollect/review/IntegratedWorkflowTest.java。测试使用一次性 PostgreSQL，真实 M1/M2/M3/M4 服务，以及进程内消息传输。真实 RabbitMQ 和 OCR 的运行结果需结合部署日志核对。

接入资料采用 [高德 JS API 安全代理](https://lbs.amap.com/api/javascript-api-v2/guide/abc/jscode)、[高德 URI 导航](https://lbs.amap.com/api/uri-api/gettingstarted)、[PaddleOCR 使用指南](https://paddlepaddle.github.io/PaddleOCR/main/en/version3.x/pipeline_usage/OCR.html)及 [Cloudflare 临时隧道文档](https://developers.cloudflare.com/tunnel/get-started/quick-tunnels/)。
