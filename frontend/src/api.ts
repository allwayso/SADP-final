import { ref } from 'vue'

export type Role = 'COLLECTOR' | 'REVIEWER' | 'FIELD_WORKER' | 'ADMIN'
export type User = {
  id: string
  username: string
  displayName: string
  enabled: boolean
  mustChangePassword: boolean
  roles: Role[]
  wechatBound: boolean
}

export const user = ref<User | null>(null)

async function csrfToken(): Promise<string> {
  const response = await fetch('/api/auth/csrf', { credentials: 'same-origin' })
  if (!response.ok) throw new Error('无法取得请求凭证，请刷新页面重试')
  const body = await response.json() as { token: string }
  return body.token
}

export async function api<T>(path: string, options: RequestInit = {}): Promise<T> {
  const method = options.method?.toUpperCase() ?? 'GET'
  const headers = new Headers(options.headers)
  if (options.body && !(options.body instanceof FormData)) headers.set('Content-Type', 'application/json')
  if (!['GET', 'HEAD', 'OPTIONS'].includes(method)) headers.set('X-XSRF-TOKEN', await csrfToken())
  const response = await fetch(`/api${path}`, { ...options, headers, credentials: 'same-origin' })
  if (response.status === 401) user.value = null
  if (!response.ok) {
    const body = await response.json().catch(() => null) as { message?: string } | null
    const labels: Record<number, string> = {
      400: '提交内容无效，请检查输入',
      401: '账号或密码错误，或者登录已失效',
      403: '没有权限执行此操作，首次登录请先修改密码',
      404: '所需记录不存在',
      409: '账号或微信绑定已存在，请核对后重试',
      503: '微信登录尚未配置',
    }
    throw new Error(body?.message ?? labels[response.status] ?? `操作失败（${response.status}）`)
  }
  if (response.status === 204 || response.headers.get('content-length') === '0') return undefined as T
  return response.json() as Promise<T>
}

export async function loadMe(): Promise<User | null> {
  try {
    user.value = await api<User>('/me')
  } catch {
    user.value = null
  }
  return user.value
}

export async function login(username: string, password: string): Promise<User> {
  user.value = await api<User>('/auth/login', { method: 'POST', body: JSON.stringify({ username, password }) })
  return user.value
}

export async function logout(): Promise<void> {
  await api<void>('/auth/logout', { method: 'POST' })
  user.value = null
}
