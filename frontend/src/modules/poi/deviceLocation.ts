export type DeviceFix = {
  lon: number
  lat: number
  accuracyMeters: number | null
  locatedAt: string
  crs: 'WGS84'
}

function locationMessage(error: GeolocationPositionError): string {
  switch (error.code) {
    case 1: return '定位被拒绝：请在浏览器的网站权限和系统设置中允许定位后重试，照片会保留'
    case 2: return '暂时无法取得定位：请到开阔处或打开系统定位后重试'
    case 3: return '定位超时：请重试，照片会保留'
    default: return '定位失败，请重试'
  }
}

/** 只由用户操作调用。短时监听高精度位置，避免直接采用第一条粗略网络定位。 */
export function locate(timeout = 15000, signal?: AbortSignal): Promise<DeviceFix> {
  return new Promise((resolve, reject) => {
    if (signal?.aborted) { reject(new Error('定位已取消')); return }
    if (!navigator.geolocation) {
      reject(new Error('此设备或浏览器不支持定位，无法完成采集'))
      return
    }
    let best: DeviceFix | null = null
    let watchId: number | undefined
    let finished = false
    const finish = (error?: Error) => {
      if (finished) return
      finished = true
      clearTimeout(timer)
      signal?.removeEventListener('abort', onAbort)
      if (watchId !== undefined) navigator.geolocation.clearWatch(watchId)
      if (error) reject(error)
      else if (best) resolve(best)
      else reject(new Error('定位超时：未取得有效位置，请开启精确定位后重试'))
    }
    const onAbort = () => finish(new Error('定位已取消'))
    const timer = window.setTimeout(() => finish(), timeout)
    signal?.addEventListener('abort', onAbort, { once: true })
    try { watchId = navigator.geolocation.watchPosition(position => {
      if (finished) return
      const { longitude: lon, latitude: lat, accuracy } = position.coords
      if (!Number.isFinite(lon) || !Number.isFinite(lat) || Math.abs(lon) > 180 || Math.abs(lat) > 90
        || !Number.isFinite(position.timestamp) || Date.now() - position.timestamp > 60000) return
      const accuracyMeters = Number.isFinite(accuracy) && accuracy >= 0 ? accuracy : null
      if (!best || (accuracyMeters ?? Infinity) < (best.accuracyMeters ?? Infinity)) {
        best = { lon, lat, accuracyMeters, locatedAt: new Date(position.timestamp).toISOString(), crs: 'WGS84' }
      }
      if (accuracyMeters !== null && accuracyMeters <= 30) finish()
    }, error => {
      // 拒绝授权必须失败；临时信号错误则保留已取得的最佳位置。
      if (error.code === 1 || !best) finish(new Error(locationMessage(error)))
      else finish()
    }, { enableHighAccuracy: true, timeout, maximumAge: 0 })
    } catch (cause) { finish(cause instanceof Error ? cause : new Error('定位失败，请重试')) }
    if (finished && watchId !== undefined) navigator.geolocation.clearWatch(watchId)
  })
}

export function distanceMeters(from: { lon: number; lat: number }, to: { lon: number; lat: number }): number {
  const radians = Math.PI / 180
  const a = Math.sin((to.lat - from.lat) * radians / 2) ** 2
    + Math.cos(from.lat * radians) * Math.cos(to.lat * radians) * Math.sin((to.lon - from.lon) * radians / 2) ** 2
  return 6371000 * 2 * Math.asin(Math.sqrt(Math.min(1, a)))
}
