# 微信登录：修复、离线发布、回滚与真机验收

本方案在 PR #8 的小程序登录、绑定与注册基础上补全网站登录。发布基线以 `manifest.json` 中的完整提交 SHA 为准，不以分支名或仅拉取源码作为部署成功依据。

## 1. 已解决什么，仍需现场确认什么

| 原问题 | 本次处理 | 验收依据 |
| --- | --- | --- |
| 微信登录只到账号卡，网站仍需密码 | 网页生成配对码，小程序确认，原网页建立自己的会话 | 两个客户端 `/api/me` 的 userId 相同，网页无需密码进入业务 |
| 小程序密码框属性不正确 | 使用 `type="text" password="{{true}}"` | 真机检查输入遮蔽 |
| 退出 403 被显示为成功 | 放行首次改密账号的退出权限，保留 CSRF，检查 HTTP 响应，成功才清理会话 | 真实权限链测试、手机退出后再次检查 |
| 旧状态请求覆盖新登录 | 页面请求版本号和 Cookie 请求代次隔离 | 异步响应乱序测试 |
| 假设小程序自动管理 Cookie | 统一请求封装显式保存/发送 JSESSIONID，并获取 CSRF token | 请求封装测试和手机重入验证 |
| 服务器旧镜像/缺少小程序配置 | 校验固定提交发布物，只更新 app 镜像与环境 | 发布清单、镜像 revision、容器 image ID及接口 |

`configured=true` **仅代表 AppID/Secret 非空**，不代表微信凭据有效。真实 `wx.login → jscode2session` 成功才证明凭据可用。开发预览成功也不代表已正式发布。

## 2. 登录与安全约定

1. 在网站登录页点“用微信登录”，得到 8 位配对码，五分钟有效。
2. 手机打开对应版本的小程序并完成微信登录；未绑定时可绑定本人已有账号，或注册采集者。
3. 账号卡点“登录网站”，输入本人网页上的配对码，核对账号及目标地址后确认。
4. 原网页每两秒检查状态，收到授权后一次性消费，轮换网页会话 ID并进入工作台；需改初始密码时进入改密页。

短码只用于小程序授权，不能直接兑换登录态；请求还绑定发起浏览器的会话。不能把小程序 Cookie 复制给浏览器，也不按昵称合并账号。确认和消费均为原子操作；消费时重新检查账号是否启用。

接口前缀：`/api/auth/wechat/mini`。

| 方法与路径 | 访问与行为 |
| --- | --- |
| `GET /status` | 匿名；配置项齐全与否 |
| `POST /session` | 小程序真实微信 code；登录/绑定/注册。只有此接口豁免 CSRF |
| `POST /logout` | 带会话和 CSRF；清除当前端会话 |
| `POST /pairings` | 浏览器带 CSRF，创建 `{id, code, expiresAt}` |
| `GET /pairings/{id}` | 仅原浏览器会话；`pending/approved/expired/consumed/cancelled` |
| `POST /pairings/confirm` | 小程序登录会话及 CSRF，提交 `{code}` |
| `POST /pairings/{id}/consume` | 原浏览器会话及 CSRF；返回登录用户 |
| `DELETE /pairings/{id}` | 原浏览器会话及 CSRF；取消请求 |

每浏览器一分钟最多创建三次，每账号一分钟最多尝试确认五次；最多保存 1,000 条未到期请求（含已消费/取消的短期记录），过期自动回收。每浏览器只保留一个可用请求。存储仅在当前单实例内存，重启使配对失效；不新增数据库迁移，不引入 Redis。

## 3. 发布前核对与配置

Linux 已有工程：`~/Projects/SADP-final`；部署材料：`~/.local/share/sadp-deploy`。项目名固定为 `sadp-final`。以下不会重建数据库、照片、消息和 OCR 卷。

先只读核对：

```bash
cd ~/Projects/SADP-final
git rev-parse HEAD
git status --short
cat ~/.local/share/sadp-deploy/compose.offline.yaml
docker compose -p sadp-final -f compose.deploy.yaml \
  -f "$HOME/.local/share/sadp-deploy/compose.offline.yaml" ps
```

存在本地源码修改时先保留和处理，不强制覆盖。源码 SHA 和容器版本须分别记录；之前的覆盖文件曾固定 `sadp-final-app:location-nav-20261111`，不能假定目前仍然相同。

在原 `.env` 补充/核对以下项，文件权限保持 600。AppSecret 从受保护的文件取得，不写入仓库、命令参数、截图或日志：

```dotenv
WECHAT_MINI_APP_ID=wx6aecef3696a530ac
WECHAT_MINI_APP_SECRET=<实际小程序密钥>
FRONTEND_BASE_URL=https://collect.allwayso.top
SESSION_COOKIE_SECURE=true
```

不修改原数据库密码、账号、地图、OCR 和 RabbitMQ 配置。`WECHAT_APP_ID/SECRET` 是旧网站应用凭据，不可代替小程序凭据。本方案不需要填写网站应用回调，也不调用 `getwxacodeunlimit`。

微信公众平台 → 开发管理 → 开发设置 → 服务器域名，把 `https://collect.allwayso.top` 加入 **request 合法域名**，按平台当前要求完成备案与 HTTPS 校验。若平台拒绝，正式发布仍不满足条件；仅能另记开发调试结果。

小程序 `config.js` 默认正式域名，开发工具和手机使用同一后端。局域网联调必须显式改为开发机可达地址；localhost 只能由开发机自身访问。开发者工具“不校验合法域名”仅用于开发调试，不能作为正式方案。

## 4. 构建固定提交的离线发布包

构建机需要 Python 3.9+、Node、Java 17+，以及已缓存的 Maven 依赖。先固定并提交全部发布源码；脚本拒绝未提交的源码，防止“SHA 与发布内容不一致”。依赖首次未缓存时，在可联网构建机执行 Maven 获取依赖，不改为服务器现场在线构建。

Windows 构建示例（在修复分支工作目录）：

```powershell
git status --short
git rev-parse HEAD
python scripts/package-wechat-release.py
```

脚本自动执行前端构建、定位测试、小程序测试、后端全部测试与打包，校验 JAR 内的前端文件及 Java 17 字节码。输出：

```text
release/<完整SHA>.tar.gz
release/<完整SHA>.tar.gz.sha256
```

包内包含 `app.jar`、`miniapp.zip`、`source.bundle`、`manifest.json`、`SHA256SUMS`、离线运行时 Dockerfile、发布/回滚脚本。不会包含 `.env`、密钥文件或开发者工具私有配置。

`manifest.json` 记录源码 SHA、产物 SHA-256、Java 目标及通过的检查。从同一包导入 `miniapp.zip`，避免前后端版本错配。

## 5. 传输、备份并发布

通过已经验证主机密钥的 SSH/SCP 连接，把 tar.gz 及外部校验文件传到 Linux 的 `~/.local/share/sadp-deploy/incoming`。把下方 `<完整SHA>` 替换为发布清单 SHA：

```bash
set -euo pipefail
umask 077
release_sha=<完整SHA>
stage="$HOME/.local/share/sadp-deploy"
cd "$stage/incoming"
sha256sum -c "$release_sha.tar.gz.sha256"
mkdir "$release_sha"
tar -xzf "$release_sha.tar.gz" -C "$release_sha"
bash "$release_sha/deploy-wechat-release.sh" "$stage/incoming/$release_sha"
```

使用新的目录，避免混入旧发布物。自动发布按顺序完成：

1. 校验包内所有文件，读取固定 SHA；检查源码没有未提交的跟踪文件修改，Compose 可解析。
2. 创建权限 700 的独立备份目录，备份 `.env`、覆盖文件、Compose 文件及 PostgreSQL `pg_dump -Fc`；保留当前运行镜像的回滚标签和原镜像 ID。
3. 以该运行镜像为本地运行时基线，仅替换 `/app/app.jar`，网络禁用，不下载依赖；新镜像标记完整源码 revision。
4. 从 `source.bundle` 导入并切到固定 SHA，更新覆盖文件中的 **app.image**，保留 OCR 和其他配置。
5. 使用固定项目名，`up -d --force-recreate --no-build --pull never --no-deps app`，仅重建 app。`.env` 修改随此次重建生效。
6. 最长等待约两分钟，核对本机回源及公网健康、小程序配置状态和登录页；核对容器 image ID 与新镜像及 revision。

预期：健康为 `UP`；本机、公网 `/api/auth/wechat/mini/status` 均 HTTP 200、`configured=true`；脚本输出发布 SHA、运行镜像 ID 和具体回滚命令。

备份在 `.env` 更新后产生，保留本次发布前的完整配置；若要同时保留修改前的配置，请编辑前另作权限 600 的副本。不要打印完整 `docker compose config` 或容器环境，因为其中包含密钥。

## 6. 回滚与失败处理

切换后自动检查失败时，发布脚本尝试恢复原应用镜像和发布前配置。手动命令以发布输出的路径为准：

```bash
bash ~/.local/share/sadp-deploy/backups/<本次目录>/rollback.sh \
  ~/.local/share/sadp-deploy/backups/<本次目录>
```

回滚校验旧镜像 ID，恢复旧源码、`.env` 和覆盖文件，仅替换 app，再检查健康。数据库、照片、RabbitMQ、OCR 继续使用当前持久卷；**不会自动恢复数据库备份**，避免丢弃部署后的新业务数据。备份镜像不得在验收完成前清理。

如果服务器检查成功，但真实微信兑换或配对失败，可用同一命令回滚。回滚后旧版没有本次配对功能，重新打开的配对请求须重新发起。

| 现象 | 排查动作 |
| --- | --- |
| `/mini/status` 为 401 | 检查运行镜像 ID/revision，及本机回源与公网是否一致；新代码允许匿名访问此接口 |
| `configured=false` 或 503 | 核对两个小程序变量是否非空并已重建 app；非空不代表密钥有效 |
| 微信 code 兑换失败 | 核对 AppID 与实际小程序一致、Secret 是否轮换；每次重新 `wx.login`，不可重用 code |
| 写接口 403 | 核对 Cookie 与 CSRF；首次改密账号的退出/配对确认应放行，其他业务仍需先改密 |
| 配对 410 | 已过期或应用重启，回网页重新发起；不要复用旧码 |
| 配对 409 | 尚未确认、已经确认/消费或取消；重新发起新的网页请求 |
| 配对 429 | 等一分钟，勿连续重试；达到请求容量时等待过期释放 |
| 其他浏览器消费 403 | 正常的浏览器绑定保护，必须回到发起请求的浏览器 |
| 手机请求域名被拦 | 检查合法域名、HTTPS/备案及手机网络；开发预览配置不可冒充正式发布 |
| 绑定 409 | 该微信或账号已有绑定；核对环境和账号，不删除正式绑定来绕过 |
| 旧账号卡偶尔消失 | 先确认手机使用同一发布包；检查 `/api/me` 的状态和会话延续 |

日志可用 `docker compose ... logs --tail 80 app` 查看；提交诊断前删去 Cookie、code、密码和密钥。服务器请求日志不应记录认证请求体。

## 7. 你需要在微信完成的真机验收

开发者工具导入本次发布包的 `miniapp.zip`，核对 AppID 和 `config.js`，登录后“预览”或上传体验版，由有访问权限的微信打开。体验版不等于正式版。

1. 手机“微信一键登录”。只有该部署数据库**尚未绑定**时才选择绑定本人已有账号；已绑定直接登录。小程序与网站“我的账号”显示同一 userId。
2. 绑定/注册表单输入测试字符串检查密码遮蔽；不要截图或分享真实密码。
3. 切到后台再返回两次，账号卡应保留；由开发工具确认 `/api/me` 为 200、userId 一致。
4. 小程序退出后再次返回，应保持未登录；再用微信登录应无需密码。另用首次改密测试账号确认退出不会 403。
5. 浏览器退出网站，点“用微信登录”，记下 8 位配对码。
6. 小程序账号卡点“登录网站”，输入该码，核对当前账号和域名后确认。
7. 原网页自动进入工作台/改密页，无需输入网站密码；核对 userId、角色，并打开本人有权限的采集/核验页面。
8. 原请求再消费，或另一浏览器消费，应被拒绝；取消或五分钟后的码不可确认。
9. 如需注册测试，使用独立未绑定的测试微信，确认只能得到 COLLECTOR，不能自行选择管理员等角色。

本人微信操作为必需环节，自动测试不能代替。记录后端 SHA、小程序包 SHA、时间、手机系统、账号/userId、每步结果及错误提示，不记录密钥、密码、Cookie 或微信 code。

## 8. 当前验证记录

修复版本的自动测试、部署及真机结果分别见 [微信修复验收记录](wechat-release-acceptance.md)。未执行的检查明确标记“未执行”，此前队友的局域网记录不替代本次正式环境验收。
