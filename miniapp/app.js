// 模拟器与网站联调使用本机独立的 8081 实例；手机/体验版使用正式 HTTPS 服务。
// 正式服务部署新接口之前，手机端不能消费本机实例产生的配对请求。
const platform = wx.getDeviceInfo().platform
App({ globalData: { apiBase: platform === 'devtools' ? 'http://localhost:8081' : 'https://collect.allwayso.top' } })
