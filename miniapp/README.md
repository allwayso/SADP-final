# 微信小程序登录端

小程序只负责**用微信身份登录、绑定到已有账号、或注册采集者账号**。POI 采集、核验、地图、消息仍在网站完成，不在这里重复实现一套业务页面。

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

- 会话沿用网站同一套 `JSESSIONID` Cookie；小程序 `wx.request` 会自动保存并携带，已实测登录后能继续读取 `/api/me`。
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

`session` 与 `logout` 已加入 CSRF 忽略与匿名放行，否则小程序请求会被 403 拦在入口。

## 本地联调（真机）

1. 手机与开发机连同一 Wi-Fi。
2. `miniapp/app.js` 的 `apiBase` 指向开发机，例如 `http://<开发机局域网 IP>:8081`（仓库里默认是正式地址，联调时临时改）。
3. 开发者工具「详情 → 本地设置」勾选**不校验合法域名**；开发版预览/真机调试允许 http 与局域网 IP。
4. 用开发者工具的「预览」把小程序装到手机上，打开即登录页，点「微信一键登录」。
5. 想验证"未绑定"分支：先删除 `wechat_bindings` 中该微信的记录，再登录，小程序会给出"绑定已有账号 / 注册采集者账号"两个选择。

## 正式环境

把后端域名（如 `https://collect.allwayso.top`）在微信公众平台加入**request 合法域名**（要求 HTTPS 且已备案），再把 `app.js` 的 `apiBase` 设为该地址。之后不需要服务器生成任何二维码，小程序打开即可登录。

## 验证记录

- 2026-11-12：真机（局域网）打开小程序 → 微信一键登录 → 直接显示 `wxcollector` 账号卡（角色 COLLECTOR、微信已绑定），证明真实 openid 登录与 Cookie 会话均可用。
- 同期单元测试覆盖：已绑定直接登录、未绑定提示、账号密码绑定、注册采集者、非法动作、缺少 code、未配置小程序。
