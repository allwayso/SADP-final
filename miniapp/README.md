# 微信小程序确认端（接入开发中）

这是扫码身份确认页面，不是完整 POI 客户端。后端已实现 create、confirm、consume、status 接口，网站登录页与账号页已接入；真实绑定登录尚待实机联调。

1. 在 https://mp.weixin.qq.com/ 注册小程序并完成平台要求，取得小程序 AppID。
2. 用微信开发者工具导入本目录，填写实际 AppID；AppSecret 仅放后端环境，不能写入此目录。
3. 开发工具可用 scene=<后端产生的32位hex ticket> 作为启动参数；必须真实调用 wx.login，再由后端换取 openid，不能手填用户标识。
4. 手机开发/体验访问范围和正式发布不同。按当前平台要求添加开发者/体验成员、配置域名并上传版本，不能将未发布的开发测试说成公众可用。
5. 页面要求用户显式确认，确认后回原浏览器。后端需把二维码绑定到原浏览器会话并一次性消费。

## 平台拒绝生码时的联调路径

个人主体未通过验证时，`getwxacodeunlimit` 返回 `85412`，服务器无法生成小程序码。此时
`POST /api/auth/wechat/mini/create` 仍返回 ticket 和微信给出的原因（`warning` 字段），网站页面会显示
ticket 而不是假装成功；轮询照旧进行，五分钟内确认即可完成登录。

用开发者工具生成预览码（需要工具已登录，并在“设置 → 安全设置”开启服务端口）：

```powershell
powershell -File scripts/wechat-preview.ps1 -Ticket <32位ticket>
```

脚本会把 `ticket=<id>` 写入编译模式，调用 `cli preview` 生成二维码图片并打开，用手机微信扫码确认。
预览码只对已获授权的开发者/体验成员有效，不等同于正式发布。

前端不会接收或保存微信 session_key、AppSecret 或网站登录令牌。

## 2026-11-11 实际微信接口预检

已用本机配置调用微信，access_token 获取成功；未打印或保存返回的凭证。调用 getwxacodeunlimit（trial、check_path=false）返回 85412 / personal entity not verified，当前主体不能生成小程序码。此问题不是 AppSecret 错误，不能用模拟图片冒充真实扫码。

可继续使用微信开发者工具编译模式携带后端生成的 scene 测试确认页；要实现面向用户的扫码闭环仍需解决主体接口限制。开发/体验版本不等于正式上线。

二维码五分钟有效，服务内存保存、重启即失效，仅支持当前单实例部署。绑定沿用数据库中 (app_id, open_id) 唯一关联；不会按昵称创建或合并本地账号。

## 接手复核（2026-11-11）

- 修复确认页缺少 confirm.json 导致的页面注册失败和模拟器白屏；已在开发者工具实际看到确认页与可用输入框。
- 配对码改为 SecureRandom 生成的十二位十六进制值（48 bit），五分钟有效；六位码不足以承受公开确认接口的猜测。
- CLI preview 不保证使用编译模式的 query；脚本不再覆盖用户编译模式或宣称启动参数已生效。配对页为开发/体验入口，不能描述为正式公众扫码入口。
- 本机 localhost:8080 的开发服务可达，但公网域名的小程序 status 接口当前返回 401，说明正式环境尚未具备可验证的新接口。手机请求公网与本机创建 ticket 不能混用：二维码配对请求和 consume 必须到同一个后端实例。
- 实际 wx.login 换取 openid、绑定、退出、再次登录仍待完成；页面显示成功或单元测试通过不能替代该验收。

## 2026-11-11 21:11 本机真实微信核验

在开发者工具模拟器中，用户点击确认后，后端使用真实 wx.login code 向微信换取身份，原会话 consume 返回 HTTP 409「请先用团队账号登录并绑定微信」。此错误发生在身份核验之后的本地绑定查询，证明确认请求已经到达同一本机后端并获得微信身份；不等于绑定或登录已完成。未保存微信 code、openid、session_key 或 access_token 到仓库。

本轮使用独立 8081 端口，连接已有本机开发数据库，未更新 Linux 正式环境。当前本机初始 admin 仍要求改密；必须先按应用流程完成首次改密并登录，再发起 BIND，不能用未绑定的 LOGIN 流程声称绑定成功。

## 2026-11-11 21:23 本机绑定与重新登录闭环通过

1. 本机 member-a 按正常密码接口完成首次改密，测试密码仅保存在忽略提交的 .env.wechat-local-accounts.json。
2. 已登录的原浏览器会话创建 BIND 请求，用户在开发者工具小程序确认；后端真实换取微信身份并消费请求，返回 complete、wechatBound=true。
3. 原会话调用 logout，另建未登录会话创建 LOGIN 请求，用户再次确认。
4. 后端返回 complete；使用新会话读取 /api/me 得到 member-a，用户 ID 与绑定时相同，ADMIN、COLLECTOR 角色一致。

范围：微信真实身份、数据库绑定、退出后重新建立会话已验证；确认端为开发者工具模拟器和配对码，不是手机正式扫码验收。微信 getwxacodeunlimit 的 85412 限制仍存在，Linux 正式部署尚未更新。不得将本记录表述为公众扫码入口已经可用。

## 2026-11-12 真机（局域网）绑定与微信登录验收

手机与开发机连同一 Wi-Fi，小程序 `apiBase` 指向开发机 `http://192.168.31.165:8081`；预览包在打包时通过 `preview-context.js` 内嵌本次一次性 ticket 与后端地址，扫码后页面直接确认，**无需输入配对码**。

1. 绑定：手机微信扫码 → 小程序 `wx.login` 取 code → 后端 `jscode2session` 换取 openid → 写入 `wechat_bindings`；`consume` 返回 `complete`，`/api/me` 中 `wechatBound=true`，userId 与发起绑定的账号一致。
2. 登录：退出登录后重新发起扫码；后端用 openid 找到同一账号并建立会话，**全程未使用密码**；同一进程内立即读取 `/api/me` 返回 `admin`，与绑定时相同 userId。
3. 预览上传耗时较长，ticket 有效期可用 `MINI_TICKET_SECONDS` 调整（本次演示 1800 秒，生产建议保持默认 300）。ticket 是 256 位不可猜测的一次性凭证，只出现在发起请求的页面；绑定请求额外要求仍登录为发起绑定时的账号，登录请求不绑会话。
4. 本地验证脚本用 PowerShell 跨进程搬运 cookie 时会话不连续，不代表浏览器行为；页面轮询与确认始终使用同一个浏览器会话。

配对码仍保留为开发者工具/体验版的兜底入口。面向公众的正式扫码仍受两点限制：生码需要可调用 `getwxacodeunlimit` 的主体资格，真机请求需要已备案并配置为 request 合法域名的 HTTPS 地址。

## 2026-11-12 自助注册（扫完码没有账号时）

`/api/auth/wechat/mini/confirm` 现在返回 `registered`：openid 已绑定账号时为 `true`，否则为 `false`，小程序据此显示注册表单。提交 `/api/auth/wechat/mini/register`（`ticket`、`username`、`password`、`displayName`）后：

- 创建账号并绑定 openid，角色**固定为采集者**；核验者、第三方与管理员仍由管理员分配，不能自助申领。
- 密码由本人设置，不再要求首次改密；账号名沿用 `[A-Za-z0-9._-]{3,64}`，与管理员开号规则一致。
- 原浏览器的 `consume` 随后用同一请求完成登录，页面直接进入工作台。

实测结果：未绑定的微信扫码 → 注册 `wxcollector` → 数据库新增该账号（COLLECTOR）、`wechat_bindings` 变为 1 → `consume` 返回 `complete`，会话用户为 `wxcollector`。

注意：`/register` 必须在 `SecurityConfig` 中同时加入 CSRF 忽略与 permitAll，否则小程序请求会被 403 拦在入口，页面表现为“点注册没有反应”。
