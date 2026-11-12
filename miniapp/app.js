// 正式环境指向已部署站点；开发者工具里默认连本机联调实例。
const platform = wx.getDeviceInfo().platform
App({
  globalData: {
    apiBase: platform === 'devtools' ? 'http://localhost:8081' : 'https://collect.allwayso.top'
  }
})
