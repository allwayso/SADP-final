import type {
  FieldTaskStatus,
  IssueAction,
  IssueStatus,
  PoiRevision,
  ReviewProgress,
  RevisionPurpose,
  Verdict,
} from './types'

export const issueStatusLabels: Record<IssueStatus, string> = {
  OPEN: '待处理',
  PENDING_RECHECK: '待复核',
  RESOLVED: '已解决',
  DISPUTED: '有异议',
  IN_ARBITRATION: '第三方处理中',
  CLOSED: '已关闭',
}

/** 状态色调：与 style.css 中的 tone-* 类对应。 */
export const issueStatusTones: Record<IssueStatus, string> = {
  OPEN: 'warn',
  PENDING_RECHECK: 'info',
  RESOLVED: 'ok',
  DISPUTED: 'alert',
  IN_ARBITRATION: 'info',
  CLOSED: 'neutral',
}

export const taskStatusLabels: Record<FieldTaskStatus, string> = {
  UNASSIGNED: '待分配',
  ASSIGNED: '已分配',
  COMPLETED: '已裁定',
}

export const taskStatusTones: Record<FieldTaskStatus, string> = {
  UNASSIGNED: 'alert',
  ASSIGNED: 'info',
  COMPLETED: 'ok',
}

export const verdictLabels: Record<Verdict, string> = {
  ORIGINAL_CORRECT: '原数据无误',
  REPLACED: '以现场版本替换',
}

export const purposeLabels: Record<RevisionPurpose, string> = {
  NEW: '新采集',
  CORRECTION: '整改',
  FIELD_CHECK: '现场复核',
}

export const progressLabels: Record<ReviewProgress, string> = {
  PENDING_REVIEW: '待核验',
  PASSED: '核验通过',
  ISSUE_OPEN: '已标识错误',
  PENDING_RECHECK: '待复核',
  DISPUTED: '有异议',
  IN_ARBITRATION: '第三方处理中',
  FINAL: '最终裁定',
}

export const actionLabels: Record<IssueAction, string> = {
  CREATED: '建立错误单',
  NOTE: '补充说明',
  CORRECTED: '提交整改',
  REJECTED: '驳回错误标识',
  RESUMED: '撤回驳回',
  CONFIRMED: '确认整改通过',
  CHANGES_REQUESTED: '退回继续整改',
  WITHDRAWN: '撤回错误标识',
  DISPUTED: '提交异议',
  ASSIGNED: '分配第三方',
  FIELD_COLLECTED: '提交现场采集版本',
  DECIDED: '提交最终裁定',
}

export function formatTime(value: string | null | undefined): string {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '—'
  return date.toLocaleString('zh-CN', { hour12: false })
}

export function revisionTitle(revision: PoiRevision | null | undefined): string {
  if (!revision) return '—'
  return revision.name?.trim() ? revision.name : revision.category
}

export function position(revision: PoiRevision | null | undefined): string {
  if (!revision) return '—'
  const accuracy = revision.accuracyMeters === null ? '' : `，精度约 ${revision.accuracyMeters.toFixed(0)} 米`
  return `${revision.latitude.toFixed(6)}, ${revision.longitude.toFixed(6)}（${revision.coordinateSystem}${accuracy}）`
}
