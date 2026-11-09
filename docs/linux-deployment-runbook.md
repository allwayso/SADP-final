# Linux 实际部署与运行记录

部署日期：2026-11-09。应用源码版本：`96b6f32`（`feature/complete-system`，PR #5）。此文档不包含密码、地图凭据、服务器 IP 或临时公网地址。

## 已部署的环境

Linux 笔记本运行 CachyOS x86_64，Intel i5-8250U，约 8 GB 内存。Docker Engine 为 29.9.3，Docker Compose 为 5.7.0。数据库采用新建的独立持久卷，没有迁移 Windows 联调数据库。

| 服务 | 镜像 | 验证结果 |
| --- | --- | --- |
| Vue 前端与 Spring Boot 应用 | `sadp-final-app:96b6f32` | `/api/auth/health` 返回 `UP` |
| PostgreSQL | `postgres:17` | 健康检查通过，迁移成功 |
| RabbitMQ | `rabbitmq:4-management` | 健康检查通过，通知队列有 1 个消费者 |
| PaddleOCR | `sadp-final-ocr:96b6f32` | 健康检查通过，实际图片识别成功 |

应用只发布到 `127.0.0.1:8080`，其余服务没有发布宿主机端口。公网通过 Cloudflare 命名隧道进入应用，正式入口为 **https://collect.allwayso.top/login**，`SESSION_COOKIE_SECURE=true`。应用容器设置为故障或主机重启后恢复运行；固定隧道由用户级 systemd 管理，已启用该用户的 linger。

## 文件与账号

| 路径 | 用途 |
| --- | --- |
| `~/Projects/SADP-final` | Git 仓库与应用配置 |
| `~/Projects/SADP-final/.env` | 当前环境配置，权限 600 |
| `~/.local/share/sadp-deploy` | 本机部署材料目录，权限 700 |
| `~/.local/share/sadp-deploy/compose.offline.yaml` | 固定镜像标签的 Compose 覆盖文件 |
| `~/.local/share/sadp-deploy/accounts.json` | 四个联调账号的独立密码与用户 ID，权限 600 |
| `~/.local/share/sadp-deploy/public-url.txt` | 当前正式 HTTPS 入口 |
| `~/.local/share/sadp-deploy/fixed-tunnel.yml` | 命名隧道及 hostname 到本机应用的路由配置 |
| `~/.cloudflared` | Cloudflare 授权证书和隧道私有凭据，不加入版本控制 |

`member-a` 为管理员和采集者，`member-b` 为采集者，`member-c` 为核验者，`member-d` 为第三方工作人员。四个账号均已通过首次改密流程，使用不同密码和不同用户 ID。Windows 试用环境与本次 Linux 环境的账号密码分别保存。

## 启动、停止和检查

在仓库目录执行，命令中的项目名和两个 Compose 文件需要保持一致：

```sh
cd ~/Projects/SADP-final
docker compose -p sadp-final -f compose.deploy.yaml -f "$HOME/.local/share/sadp-deploy/compose.offline.yaml" up -d --no-build --pull never
docker compose -p sadp-final -f compose.deploy.yaml -f "$HOME/.local/share/sadp-deploy/compose.offline.yaml" ps
curl --fail http://127.0.0.1:8080/api/auth/health
```

停止时保留数据库、照片、消息和 OCR 模型卷：

```sh
docker compose -p sadp-final -f compose.deploy.yaml -f "$HOME/.local/share/sadp-deploy/compose.offline.yaml" stop
```

隧道状态与日志：

```sh
systemctl --user status checkcollect-fixed-tunnel.service
journalctl --user -u checkcollect-fixed-tunnel.service --no-pager -n 100
```

本次 Linux 连接 Docker Hub 超时，采用本机构建、镜像归档、SHA-256 校验后离线导入的方式部署。OCR 模型缓存也已预置。更新应用时需要重新构建镜像并同步标签，不能仅拉取源码后继续使用旧镜像。

安装 Docker 时，原包索引引用的 Docker、Compose 旧版本在镜像上已被替换，导致下载 404。修复时从该机器已配置的可用 CachyOS 镜像读取当前仓库元数据，下载所需 5 个包及签名，校验 SHA-256 后由用户通过 pacman 本地安装。后续常规维护应保持包索引与系统版本同步。

## 本次验收范围

- 四个账号登录后 `/api/me` 返回不同 ID，改密成功，角色符合分工；普通用户访问管理员接口返回 403，退出后访问 `/api/me` 返回 401。
- 公网 HTTPS 下实际浏览器登录成功，会话 Cookie 具有 Secure 和 HttpOnly 属性。
- 通过正式上传接口发送测试图片，PaddleOCR 返回 `SHOP\n123`，`simulated=false`。
- 高德地图在新 HTTPS 来源下加载底图并显示点位，浏览器没有脚本错误。点位由测试浏览器临时提供，没有写入数据库。
- 390 px 宽度的消息页面没有横向溢出。
- PostgreSQL、RabbitMQ、OCR 健康检查通过；RabbitMQ 通知队列存在消费者。

完整业务流程的既有集成测试与联调记录见 [integration-status.md](integration-status.md)。本次部署验收不重复声称已在 Linux 上重新执行全部业务回归。

## 固定域名与后续配置

固定隧道名称为 `sadp-final`，发布的 hostname 为 `collect.allwayso.top`，回源地址为 `http://127.0.0.1:8080`，其他 hostname 返回 404。固定隧道已建立四条到 Cloudflare 的连接。隧道重启不改变正式域名；笔记本仍需保持开机、联网并避免自动睡眠。

域名注册和续费仍由腾讯云管理，权威 DNS 已切换到 Cloudflare 分配的 `monroe.ns.cloudflare.com` 和 `piers.ns.cloudflare.com`。`collect` 的代理 CNAME 已指向命名隧道。应用的 `FRONTEND_BASE_URL` 已更新为 `https://collect.allwayso.top`，旧临时隧道已停用。

已在正式域名下重新验证四个账号的身份、权限和退出限制，以及实际浏览器登录、Secure/HttpOnly Cookie、高德底图和临时点位、PaddleOCR 识别与 390 px 手机页面。地图点位仍仅由测试浏览器临时提供，没有写入数据库。

`WECHAT_REDIRECT_URI` 已配置为 `https://collect.allwayso.top/api/auth/wechat/callback`。真实微信登录仍需网站应用的 AppID、AppSecret、平台授权回调域及绑定验收；仅配置域名和回调 URL 不会自动启用扫码登录。
