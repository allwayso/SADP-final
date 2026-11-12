# 给部署同学：微信小程序登录上线清单

适用项目：check@collect；目标分支 `xcf`（主仓库 [PR #8](https://github.com/allwayso/SADP-final/pull/8)）。

## 先看结论

- 微信登录方式已改为**小程序内 `wx.login` 直接登录**，不再需要网站生成二维码，也不再有 ticket、配对码、回调隧道。所以 `getwxacodeunlimit` 的 `85412` 限制与我们无关了。
- 我们这边已完成真机验证：微信一键登录、用账号密码绑定已有账号、未绑定微信自助注册采集者，都使用真实 openid，会话用 `JSESSIONID` Cookie 维持。
- 你这边要做的是：**部署新代码 + 填两个环境变量 + 在小程序后台加 request 合法域名 + 在 Linux 环境里重新绑定一次微信**。

## 1. 部署新代码

```sh
cd ~/Projects/SADP-final
git status --short                 # 确认没有未提交的本地改动
git fetch https://github.com/xcfsllsz/SADP-final.git xcf
git switch -c deploy/wechat-login FETCH_HEAD
git rev-parse HEAD                 # 记录这个 SHA，验收时填进记录
docker compose -p sadp-final -f compose.deploy.yaml -f "$HOME/.local/share/sadp-deploy/compose.offline.yaml" up -d --build
docker compose -p sadp-final -f compose.deploy.yaml -f "$HOME/.local/share/sadp-deploy/compose.offline.yaml" ps
```

不重建数据库、不覆盖 `.env`。构建失败时贴 `docker compose ... build` 的末尾 30 行。

## 2. 填小程序凭据

在 Linux 的 `.env` 里补两行（值向徐晨帆要，不要写进仓库、不要贴群里）：

```dotenv
WECHAT_MINI_APP_ID=wx6aecef3696a530ac
WECHAT_MINI_APP_SECRET=<小程序密钥>
```

改完重启应用容器：

```sh
docker compose -p sadp-final -f compose.deploy.yaml -f "$HOME/.local/share/sadp-deploy/compose.offline.yaml" up -d app
curl -fsS https://collect.allwayso.top/api/auth/wechat/mini/status
```

期望返回 `{"configured":true}`。返回 `false` 说明变量没进容器，检查变量名与容器环境。

## 3. 小程序后台加域名

微信公众平台 → 开发 → 开发管理 → 开发设置 → 服务器域名 → **request 合法域名**，加入：

```text
https://collect.allwayso.top
```

要求该域名已备案且为 HTTPS。加完在开发者工具里把 `miniapp/app.js` 的 `apiBase` 设为 `https://collect.allwayso.top`，重新「预览」或上传体验版。

## 4. 在 Linux 环境重新绑定并验收

Windows 上已经绑定过的微信不会同步到 Linux，需要重新绑定一次：

1. 用管理员账号密码登录 `https://collect.allwayso.top`，进入「我的账号」，确认微信显示“尚未绑定”。
2. 手机打开「check@collect」小程序 → 点「微信一键登录」→ 因为该微信未绑定，页面给出两个选项 → 选「用已有账号绑定微信」→ 输入刚才的管理员账号密码 → 页面显示账号卡（账号名、角色、微信已绑定）。
3. 回到网站刷新「我的账号」，应显示“已绑定”。
4. 小程序里退出登录 → 再点「微信一键登录」→ 这次应**不用输密码**直接进入账号卡。

把第 4 步的结果、账号名、userId 和时间回报即可；失败时贴后端日志中 `wechat/mini/session` 附近的内容。

## 5. 已知边界

- 小程序登录只是身份入口；采集、核验、地图、消息仍在网站完成，小程序不重复实现业务页面。
- 自助注册只能得到**采集者**；核验者、第三方、管理员仍由管理员在「用户管理」分配。
- 微信 `code` 一次性、很快过期；AppSecret 只放后端环境变量。
- 若手机打不开或请求被拦，先确认域名是否已备案并加入 request 合法域名；开发版联调可用局域网 IP + 开发者工具“不校验合法域名”，但那只适合演示，不是正式入口。
