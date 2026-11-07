import { api } from '../../api'

export type PoiCategory = 'HOUSE' | 'SHOP' | 'BUS_STOP' | 'PARK' | 'TREE' | 'SCENIC' | 'OTHER'
export type Purpose = 'NEW' | 'CORRECTION' | 'FIELD_CHECK'

export type ReviewSummary = {
  state: string | null
  progress: string | null
  errorFlag: boolean
  text: string | null
  updatedAt: string | null
}

export type PoiSummary = {
  id: string
  currentRevisionId: string
  category: PoiCategory
  categoryLabel: string
  name: string
  description: string
  lon: number
  lat: number
  crs: string
  revisionNo: number
  revisionCount: number
  createdAt: string
  updatedAt: string
  review: ReviewSummary | null
}

export type PhotoView = { id: string; contentType: string; byteSize: number; ocrText: string | null }

export type RevisionView = {
  id: string
  poiId: string
  revisionNo: number
  purpose: Purpose
  category: PoiCategory
  name: string
  description: string
  lon: number
  lat: number
  crs: string
  locatedAt: string
  accuracyMeters: number | null
  sourceTaskId: string | null
  submittedBy: string
  submittedAt: string
  photos: PhotoView[]
}

export type PoiDetail = { poi: PoiSummary; revisions: RevisionView[] }
export type PoiPage = { items: PoiSummary[]; page: number; size: number; total: number }
export type RevisionRef = { poiId: string; revisionId: string; revisionNo: number }

export type NavigationTarget = {
  provider: string
  simulated: boolean
  url: string
  targetLon: number
  targetLat: number
  crs: string
  note: string
}

export type OcrSuggestion = { text: string; suggestedCategory: PoiCategory | null; simulated: boolean }

export type DeviceFix = {
  lon: number
  lat: number
  accuracyMeters: number | null
  locatedAt: string
  crs: 'WGS84'
}

export const categories: { value: PoiCategory; label: string }[] = [
  { value: 'HOUSE', label: '房屋' },
  { value: 'SHOP', label: '商铺' },
  { value: 'BUS_STOP', label: '公交站' },
  { value: 'PARK', label: '花坛绿地' },
  { value: 'TREE', label: '树木' },
  { value: 'SCENIC', label: '景点' },
  { value: 'OTHER', label: '其他' },
]

export const purposeLabels: Record<Purpose, string> = {
  NEW: '新采集',
  CORRECTION: '更新或整改',
  FIELD_CHECK: '第三方现场核验',
}

async function csrfToken(): Promise<string> {
  const response = await fetch('/api/auth/csrf', { credentials: 'same-origin' })
  if (!response.ok) throw new Error('无法取得请求凭证，请刷新页面重试')
  return (await response.json() as { token: string }).token
}

function failure(status: number, message?: string): Error {
  const labels: Record<number, string> = {
    400: '提交内容无效，请检查照片、分类和定位信息',
    401: '登录已失效，请重新登录',
    403: '没有权限执行此操作，只有采集者可以提交采集',
    404: '所需记录不存在',
    409: '该 POI 的版本已被其他人更新，请刷新后重试',
    413: '照片过大，请压缩后重试',
  }
  return new Error(message || labels[status] || `操作失败（${status}）`)
}

/** multipart 提交：照片与位置必须同一次请求，不能用 api() 的 JSON 逻辑。 */
async function upload<T>(path: string, form: FormData): Promise<T> {
  const response = await fetch(`/api${path}`, {
    method: 'POST',
    body: form,
    headers: { 'X-XSRF-TOKEN': await csrfToken() },
    credentials: 'same-origin',
  })
  if (!response.ok) {
    const body = await response.json().catch(() => null) as { message?: string } | null
    throw failure(response.status, body?.message)
  }
  return response.json() as Promise<T>
}

export function listPois(filters: { category?: string; progress?: string; bbox?: string; page?: number; size?: number } = {}): Promise<PoiPage> {
  const params = new URLSearchParams()
  if (filters.category) params.set('category', filters.category)
  if (filters.progress) params.set('progress', filters.progress)
  if (filters.bbox) params.set('bbox', filters.bbox)
  if (filters.page !== undefined) params.set('page', String(filters.page))
  if (filters.size !== undefined) params.set('size', String(filters.size))
  const query = params.toString()
  return api<PoiPage>(`/pois${query ? `?${query}` : ''}`)
}

export function getPoi(id: string): Promise<PoiDetail> {
  return api<PoiDetail>(`/pois/${id}`)
}

export function navigationTarget(id: string, from?: { lon: number; lat: number }): Promise<NavigationTarget> {
  const params = new URLSearchParams()
  if (from) {
    params.set('fromLon', String(from.lon))
    params.set('fromLat', String(from.lat))
  }
  const query = params.toString()
  return api<NavigationTarget>(`/pois/${id}/navigation${query ? `?${query}` : ''}`)
}

export function photoUrl(poiId: string, photoId: string): string {
  return `/api/pois/${poiId}/photos/${photoId}`
}

export function suggestFromPhoto(file: File): Promise<OcrSuggestion> {
  const form = new FormData()
  form.append('photo', file)
  return upload<OcrSuggestion>('/pois/ocr', form)
}

export function submitNewPoi(form: FormData): Promise<RevisionRef> {
  return upload<RevisionRef>('/pois', form)
}

export function submitRevision(poiId: string, form: FormData): Promise<RevisionRef> {
  return upload<RevisionRef>(`/pois/${poiId}/revisions`, form)
}

function locationMessage(error: GeolocationPositionError): string {
  switch (error.code) {
    case error.PERMISSION_DENIED: return '定位被拒绝：请在浏览器设置中允许定位后重试，位置不能手动填写'
    case error.POSITION_UNAVAILABLE: return '暂时无法取得定位：请到开阔处或打开系统定位后重试'
    case error.TIMEOUT: return '定位超时：请重试，照片草稿会保留'
    default: return '定位失败，请重试'
  }
}

/** 由设备感知拍照位置，不经用户手工输入；坐标系为 WGS84。 */
export function locate(timeout = 15000): Promise<DeviceFix> {
  return new Promise((resolve, reject) => {
    if (!('geolocation' in navigator)) {
      reject(new Error('此设备或浏览器不支持定位，无法完成采集'))
      return
    }
    navigator.geolocation.getCurrentPosition(
      position => resolve({
        lon: Math.round(position.coords.longitude * 1e6) / 1e6,
        lat: Math.round(position.coords.latitude * 1e6) / 1e6,
        accuracyMeters: Number.isFinite(position.coords.accuracy) ? Math.round(position.coords.accuracy * 10) / 10 : null,
        locatedAt: new Date(position.timestamp).toISOString(),
        crs: 'WGS84',
      }),
      error => reject(new Error(locationMessage(error))),
      { enableHighAccuracy: true, timeout, maximumAge: 0 },
    )
  })
}
