import { api } from '../../api'

let loading: Promise<any> | undefined

/** 共用一个 SDK，安全密钥仍由后端代理附加。加载地图本身不请求设备定位。 */
export function loadAmap(): Promise<any> {
  if (!loading) loading = initialize().catch(error => { loading = undefined; throw error })
  return loading
}

async function initialize(): Promise<any> {
  const config = await api<{ provider: string; key: string }>('/pois/platform')
  if (config.provider !== 'amap' || !config.key) throw new Error('地图尚未配置')
  ;(window as any)._AMapSecurityConfig = { serviceHost: window.location.origin + '/api/_AMapService' }
  if ((window as any).AMap) return (window as any).AMap
  await new Promise<void>((resolve, reject) => {
    const script = document.createElement('script')
    const timer = window.setTimeout(() => { script.remove(); reject(new Error('高德地图加载超时，请重试')) }, 15000)
    script.onload = () => { clearTimeout(timer); resolve() }
    script.onerror = () => { clearTimeout(timer); script.remove(); reject(new Error('高德地图加载失败')) }
    script.src = `https://webapi.amap.com/maps?v=2.0&key=${encodeURIComponent(config.key)}`
    document.head.appendChild(script)
  })
  return (window as any).AMap
}

export function convertGps(amap: any, point: { lon: number; lat: number }): Promise<any> {
  return new Promise((resolve, reject) => {
    amap.convertFrom([point.lon, point.lat], 'gps', (status: string, result: any) => {
      if (status === 'complete' && result.locations?.[0]) resolve(result.locations[0])
      else reject(new Error('高德坐标转换失败，请重试'))
    })
  })
}
