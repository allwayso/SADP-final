const kindLabels: Record<string, string> = {
  NEW_DATA: '新数据',
  ANNOUNCEMENT: '公告',
  ISSUE_RAISED: '错误标识',
  ISSUE_RESPONSE: '整改反馈',
  DISPUTE_OPENED: '异议',
  FIELD_TASK: '现场任务',
  FINAL_RESULT: '最终结果',
}

export function kindLabel(kind: string): string {
  return kindLabels[kind] ?? '通知'
}

export function formatDateTime(value: string | null | undefined): string {
  if (!value) return ''
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return ''
  const sameYear = date.getFullYear() === new Date().getFullYear()
  return new Intl.DateTimeFormat('zh-CN', {
    year: sameYear ? undefined : 'numeric',
    month: 'numeric',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  }).format(date)
}

export function statusLabel(status: string): string {
  if (status === 'DELIVERED') return '已投递'
  if (status === 'FAILED') return '待重试'
  if (status === 'PENDING') return '投递中'
  return status
}
