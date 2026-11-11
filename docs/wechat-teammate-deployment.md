# 给部署同学：微信小程序接入更新教程

适用项目：check@collect；目标分支：xcfsllsz/SADP-final 的 xcf，主仓库 PR #7。

## 先看结论

你负责把新版本和微信配置部署到现有 Linux 服务。开发已经完成本机真实微信身份交换、绑定 member-a、退出后新会话登录同一用户的验证。不是模拟 openid。前后端检查已通过。

但部署本身不会解除微信平台限制：当前个人小程序调用 getwxacodeunlimit 返回 85412 / personal entity not verified。手机实测也确认临时域名被 request 域名校验拦截。现在不能宣布正式手机扫码登录全部完成。

正常入口：平台允许生码且合法域名配置成功后，网站展示二维码，微信扫码并确认，原网页自动绑定/登录，无需输配对码。受限入口：开发者预览码或配对码，仅用于限定人员开发验收。每次动态预览需要开发者工具，不适合作为普通用户的正式登录服务。

## 1. 分清两台电脑

- Linux：现有 Docker 应用、PostgreSQL、RabbitMQ、OCR 和公网隧道。不需要安装微信开发者工具，也不必安装宿主机 Java/Node（Dockerfile 内会构建）。
- Windows：徐晨帆的微信开发者工具，负责导入 miniapp、生成预览包。其本机 .env 和开发数据库不会自动同步到 Linux。
- 正式账号与 Windows 测试账号是两个数据库，Windows 绑定成功不会让 Linux 自动显示已绑定。Linux 需要再绑定一次。

## 2. Linux 要有的软件

```sh
git --version
docker --version
docker compose version
curl --version
```

缺失时请用系统包管理器安装。现有主机为 CachyOS：管理员执行 `sudo pacman -Syu git docker docker-compose curl`，首次启用 Docker 用 `sudo systemctl enable --now docker`。这条更新命令会更新系统；现有软件齐全就跳过安装。其他发行版按各自官方说明安装，不直接复制 pacman 命令。

已有固定 Cloudflare 隧道就沿用，不新建域名或临时隧道。若 docker 提示权限不足，使用已有授权账号或在下面 docker 命令前加 sudo，不为部署重建数据库。

## 3. 拉代码，不覆盖本地配置

```sh
cd ~/Projects/SADP-final
git status --short
git fetch https://github.com/xcfsllsz/SADP-final.git xcf
git switch -c deploy/wechat-update FETCH_HEAD
git rev-parse HEAD
```

记录 SHA。若有本地修改或分支名已存在，先核对，不使用 reset --hard。PR 合并后也可从主仓库 main 更新。不要把 Windows 的整个 .env 覆盖服务器配置。

## 4. 保存旧镜像和备份

沿用已有项目名 `sadp-final` 和原覆盖文件，防止启动出另一套空数据库。

```sh
umask 077
mkdir -p ~/.local/share/sadp-deploy/wechat-backup
cp .env ~/.local/share/sadp-deploy/wechat-backup/env.before
cp ~/.local/share/sadp-deploy/compose.offline.yaml ~/.local/share/sadp-deploy/wechat-backup/compose.before.yaml
docker inspect $(docker compose -p sadp-final -f compose.deploy.yaml -f ~/.local/share/sadp-deploy/compose.offline.yaml ps -q app) --format '{{.Config.Image}}'
docker compose -p sadp-final -f compose.deploy.yaml -f ~/.local/share/sadp-deploy/compose.offline.yaml exec -T postgres pg_dump -U checkcollect checkcollect > ~/.local/share/sadp-deploy/wechat-backup/database.sql
```

记下旧 app 镜像标签，不删除它。微信改动没有新增迁移。照片卷也应保留；本次只重建 app，禁止 `down -v`、删卷或全库初始化。备份文件包含凭据/个人信息，不上传 Git。

## 5. 配置微信（由小程序管理员提供）

在服务器 `.env` 末尾填写或更新，避免同名变量重复：

```dotenv
WECHAT_MINI_APP_ID=wx6aecef3696a530ac
WECHAT_MINI_APP_SECRET=填写完整小程序密钥
WECHAT_MINI_ENV_VERSION=trial
FRONTEND_BASE_URL=https://collect.allwayso.top
SESSION_COOKIE_SECURE=true
```

```sh
chmod 600 .env
```

AppSecret 从微信公众平台的小程序开发管理页面获取，只通过可信渠道交给服务器管理员。不要贴在 PR、截图、日志、微信群或小程序源码。旧 WECHAT_APP_ID/SECRET 是另一套“网站应用扫码”配置，不用小程序密钥替换它们。小程序新入口有独立配置。

## 6. 构建和更新 app

有网络时在仓库根目录构建，只重启 app：

```sh
docker build -t sadp-final-app:wechat-update .
```

用编辑器打开 `~/.local/share/sadp-deploy/compose.offline.yaml`，只把 app 的 image 改为 `sadp-final-app:wechat-update`，保留 OCR 等其他镜像设置。

```sh
docker compose -p sadp-final -f compose.deploy.yaml -f ~/.local/share/sadp-deploy/compose.offline.yaml up -d --no-deps --no-build --pull never app
docker compose -p sadp-final -f compose.deploy.yaml -f ~/.local/share/sadp-deploy/compose.offline.yaml ps
curl --fail http://127.0.0.1:8080/api/auth/health
curl --fail https://collect.allwayso.top/api/auth/wechat/mini/status
```

预期 health 为 UP，小程序 status 为 configured=true。后者只表示配置非空，不表示微信已审核或扫码成功。不要使用裸 `docker compose up` 启动新的项目。

如果构建时 Docker Hub/npm/Maven 网络不通，可由另一台能构建的电脑执行同样的 docker build，再 `docker save -o sadp-wechat-update.tar sadp-final-app:wechat-update`；传入 Linux 后执行 `docker load -i sadp-wechat-update.tar`，然后照上面的覆盖文件与 up 命令更新。该 tar 不含 .env，但仍应通过可信方式传输。注意两台构建机需兼容 Linux x86_64 镜像。

## 7. 微信后台及版本准备（小程序管理员操作）

1. 登录 mp.weixin.qq.com，确认 AppID 与本项目一致。
2. 开发管理/开发设置：配置 request 合法域名 `https://collect.allwayso.top`。如平台因备案或资格拒绝，需要按实际要求解决；购买域名、HTTPS 和隧道不等于备案。
3. 导入 Windows 上的 miniapp 工程；后端服务使用自己的 Spring Boot，不启用云开发。
4. 上传代码后在版本管理设置体验版，添加实际测试成员；确认页路径 `pages/confirm/confirm`。正式版需完成平台要求、备案及审核；trial 仅指体验版，不意味着所有微信可访问。
5. 如果 `85412` 持续出现，必须核对主体验证和接口权限。部署新代码、设置 check_path=false 或安装开发工具都不会自动解除该限制。

## 8. 受限主体下的不输码开发预览

仅在新后端已部署、同一微信已在该 Linux 数据库绑定，或正在创建 BIND 时使用：

1. 网站登录页发起小程序登录；或密码登录后，在我的账号发起小程序绑定。保留该页面，它会持续轮询。
2. 平台生码失败时页面显示“请求标识”。复制这个 32 位十六进制 ticket，不复制别人的请求；有效期五分钟。
3. Windows PowerShell 在仓库根目录执行：

```powershell
powershell -File scripts/wechat-preview.ps1 -Ticket 这里换成32位请求标识 -ApiBase https://collect.allwayso.top -OutputPath D:/Temp/checkcollect-preview.png -CliPath "D:/微信开发者工具/微信web开发者工具/cli.bat"
```

CLI 路径按实际安装位置调整。开发者工具需登录；服务端口若未开启，由本人在工具的安全设置中开启。脚本会短暂把 ticket 和 ApiBase 写入 preview-context.js，打包后恢复；不会写入 AppSecret。确认 `git diff -- miniapp/preview-context.js` 没有残留请求。

4. 用授权微信扫生成的图片。应直接看到确认按钮，不需要输入配对码；本人点击确认，原网站应完成登录/绑定。生成预览时会上传小程序代码到微信，不要将密钥或 .env 放入 miniapp。
5. 手机报 `url not in domain list` 表示请求未到后端。正式使用须配置合法域名。限定开发测试可由用户在手机小程序菜单查看是否有“开发调试/打开调试”，实际是否提供以微信界面为准；不要把调试设置算作正式部署完成。电脑的“不校验”不能证明手机已放行。
6. 临时隧道只用于本机测试。网站创建 ticket 与小程序 confirm 必须指向同一实例；不能用 Linux 网站的 ticket 发给 Windows 后端，也不能用 Windows ticket 发给 Linux。

## 9. 最终验收（需要真实手机）

- 密码登录 member-a，记下“我的账号”用户 ID 和角色。
- 绑定微信，确认页面刷新后仍显示已绑定。
- 退出，重新通过微信登录，确认用户 ID 相同、角色和历史数据不变。
- 错误/过期请求失败，取消确认不登录；同一微信不能绑定其他本地账号。
- 明确记录普通生码还是开发者预览，实际设备、版本 SHA、结果。不使用 Windows 本机成功记录冒充 Linux 验收。
- 当前未开放扫码自动注册：未绑定微信会提示先用团队账号绑定，这是有意保留的行为，避免自动赋予工作角色。

## 10. 常见故障与回滚

| 现象 | 排查 |
| --- | --- |
| status 401/404 | 确认新 app 镜像已运行；只拉源码不会更新旧镜像 |
| configured=false | 确认变量名正确，改 .env 后重建 app 容器；不要把密钥打印出来 |
| 85412 | 微信主体/生码权限问题，不能靠服务器重启解决 |
| request url not in domain list | 手机域名校验，先核对服务器域名与实际请求地址 |
| 410/过期 | 重建请求并生成新预览，五分钟过期或后端重启会失效 |
| 提示先绑定 | 当前数据库里还没有该微信关联，先用密码登录绑定 |
| 公网 530/502 | 查 Linux 是否开机联网、app 健康、固定隧道状态 |
| 构建 jar 无法重命名 | 本机旧 java 正在占用 jar；用 Docker 隔离构建，勿误停正式服务 |

查看日志（分享前遮蔽敏感信息）：

```sh
docker compose -p sadp-final -f compose.deploy.yaml -f ~/.local/share/sadp-deploy/compose.offline.yaml logs --tail=100 app
systemctl --user status checkcollect-fixed-tunnel.service --no-pager
```

回滚：恢复备份中的 compose.before.yaml 到原覆盖文件，必要时恢复 env.before 到 .env，再执行第 6 节同一条 up 命令。保留数据库和照片卷，不运行数据库恢复来回滚纯应用版本。恢复应用后检查 health 和已有账号/照片。

执行完成后反馈：部署 SHA、health、configured 是否为 true、实际扫码路径及结果、如失败则提供去除敏感内容的错误。不要回复密钥。
