import { ref } from 'vue'
import { reviewApi } from './api'
import type { FieldTaskStatus, IssueStatus, IssueView, PendingReview, TaskView } from './types'

/**
 * 核验模块的共享列表状态。列表同时用于页面内容与页签上的计数，
 * 因此由模块级单例保存，而不是每个面板各查一次。
 */
export const pendingItems = ref<PendingReview[]>([])
export const issueItems = ref<IssueView[]>([])
export const taskItems = ref<TaskView[]>([])
export const issueStatuses = ref<IssueStatus[]>(['OPEN', 'PENDING_RECHECK', 'DISPUTED', 'IN_ARBITRATION', 'CLOSED'])
export const taskStatuses = ref<FieldTaskStatus[]>(['UNASSIGNED', 'ASSIGNED', 'COMPLETED'])
export const issueMine = ref(true)
export const taskMine = ref(true)
export const error = ref('')

export function failure(cause: unknown): string {
  return cause instanceof Error ? cause.message : '操作失败，请稍后重试'
}

export async function loadPending(): Promise<void> {
  try {
    pendingItems.value = (await reviewApi.pending()).items
  } catch (cause) {
    error.value = failure(cause)
  }
}

export async function loadIssues(): Promise<void> {
  try {
    issueItems.value = (await reviewApi.issues(issueStatuses.value, issueMine.value)).items
  } catch (cause) {
    error.value = failure(cause)
  }
}

export async function loadTasks(): Promise<void> {
  try {
    taskItems.value = (await reviewApi.tasks(taskStatuses.value, taskMine.value)).items
  } catch (cause) {
    error.value = failure(cause)
  }
}

/** 状态流转后刷新受影响的列表，保持页签计数与内容一致。 */
export async function refreshAll(): Promise<void> {
  await Promise.all([loadPending(), loadIssues(), loadTasks()])
}
