// 正式验收时开发工具与手机使用同一后端。局域网联调请显式修改 config.js。
const config = require('./config')
App({
  globalData: {
    apiBase: config.apiBase
  }
})
