# 微信小程序登录端

小程序负责**微信登录、绑定、注册采集者，以及确认网站配对登录**。POI 业务仍在网站完成；网站显示短码，小程序确认后原网页无需密码即可进入业务。

页面：`pages/index/index`（登录 / 未绑定时的选择 / 绑定或注册表单 / 已登录的账号卡与退出）。

## 登录流程

```
小程序点击「微信一键登录」
  → wx.login 取一次性 code
  → POST /api/auth/wechat/mini/session { code }
      ├─ 该 openid 已绑定账号        → 建立会话，返回 { status: "logged-in", user }
      ├─ 未绑定，action=bind          → 校验账号密码，绑定 openid 后登录 { status: "bound", user }
      ├─ 未绑定，action=register      → 创建采集者账号并绑定，登录 { status: "registered", user }
      └─ 未绑定且未指定 action        → { status: "need-binding" }，由小程序让用户二选一
```

- 请求封装 `utils/request.js` 显式保存/发送 `JSESSIONID`，Cookie 按 API 地址隔离；写接口从 `/api/auth/csrf` 获取 token。小程序与浏览器各持有自己的会话。
- `POST /api/auth/wechat/mini/logout` 结束小程序会话，不影响其他端。
- 角色规则：注册只能得到**采集者**；核验者、第三方工作人员与管理员必须由管理员分配。
- 密码由本人设置，不再要求首次改密；账号名沿用 `[A-Za-z0-9._-]{3,64}`。
- 微信 `code` 只能用一次且很快过期，每次提交都重新 `wx.login`；AppSecret 只存在后端，小程序不接触。

## 后端接口

| 方法与路径 | 说明 |
| --- | --- |
| `GET /api/auth/wechat/mini/status` | 是否已配置小程序 AppID/Secret |
| `POST /api/auth/wechat/mini/session` | 登录/绑定/注册，参数 `code`、可选 `action`、`username`、`password`、`displayName` |
| `POST /api/auth/wechat/mini/logout` | 退出小程序会话 |

只有 `session` 豁免 CSRF；退出与配对确认需发送 CSRF token。尚需改初始密码的账号可以退出、确认网页登录，随后在网站改密。

网站登录页点“用微信登录”，小程序账号卡点“登录网站”，输入 8 位配对码并确认。码五分钟有效，绑定原浏览器并一次性消费，不需要服务器生成二维码。完整接口、发布与回滚见 [部署文档](../docs/wechat-teammate-deployment.md)。

## 本地联调（真机）

1. 手机与开发机连同一 Wi-Fi。
2. `miniapp/config.js` 的 `apiBase` 指向开发机，例如 `http://<开发机局域网 IP>:8081`（默认正式地址，开发工具和手机使用同一环境）。
3. 开发者工具「详情 → 本地设置」勾选**不校验合法域名**；开发版预览/真机调试允许 http 与局域网 IP。
4. 用开发者工具的「预览」把小程序装到手机上，打开即登录页，点「微信一键登录」。
5. 验证未绑定分支使用独立测试微信；不要删除正式绑定记录来制造测试条件。

## 正式环境

把后端域名（如 `https://collect.allwayso.top`）在微信公众平台加入 **request 合法域名**，满足平台要求，再确认 `config.js` 指向该地址。发布包同时包含对应版本的小程序 zip。

## 验证记录

- 2026-11-12 队友的旧版本局域网记录：一键登录后显示 `wxcollector` 账号卡。本次修复后真机结果另见 [验收记录](../docs/wechat-release-acceptance.md)，不能用旧账号卡截图代替新会话和网页配对验收。
- 同期单元测试覆盖：已绑定直接登录、未绑定提示、账号密码绑定、注册采集者、非法动作、缺少 code、未配置小程序。
