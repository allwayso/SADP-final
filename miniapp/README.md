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
