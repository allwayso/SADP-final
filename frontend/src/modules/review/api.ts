import { api } from '../../api'
import type {
  CollectionInput,
  DisputeView,
  FieldTaskStatus,
  IssueDetail,
  IssueStatus,
  IssueView,
  Page,
  PendingReview,
  PoiRevision,
  ReviewHistory,
  ReviewRecordView,
  ReviewOutcome,
  StubNotification,
  StubPoi,
  TaskView,
  Verdict,
} from './types'

function uploadBody(request: Record<string, unknown>, collection: CollectionInput | null): FormData {
  const form = new FormData()
  const { photos, ...fields } = collection ?? {}
  form.append('request', new Blob([JSON.stringify({ ...request, collection: fields })], { type: 'application/json' }))
  photos?.forEach(photo => form.append('photos', photo))
  return form
}

/** M3 的 HTTP 调用封装。共享 api() 已处理 CSRF、登录失效和后端错误信息。 */
export const reviewApi = {
  pending: () => api<Page<PendingReview>>('/reviews/pending?size=50'),
  review: (revisionId: string, outcome: ReviewOutcome, description?: string) =>
    api<ReviewRecordView>('/reviews', { method: 'POST', body: JSON.stringify({ revisionId, outcome, description }) }),
  history: (poiId: string) => api<ReviewHistory>(`/reviews/pois/${encodeURIComponent(poiId)}`),

  issues: (statuses: IssueStatus[], mine: boolean) => {
    const params = new URLSearchParams({ mine: String(mine), size: '50' })
    if (statuses.length) params.set('status', statuses.join(','))
    return api<Page<IssueView>>(`/issues?${params.toString()}`)
  },
  issue: (id: string) => api<IssueDetail>(`/issues/${id}`),
  act: (id: string, action: string, body: Record<string, unknown>) =>
    api<IssueDetail>(`/issues/${id}/${action}`, { method: 'POST', body: JSON.stringify(body) }),
  correct: (id: string, revisionId: string, collection: CollectionInput, note?: string) =>
    api<IssueDetail>(`/issues/${id}/revisions`, { method: 'POST', body: uploadBody({ revisionId, note }, collection) }),
  submitDispute: (issueId: string, revisionId: string, reason: string) =>
    api<DisputeView>('/disputes', { method: 'POST', body: JSON.stringify({ issueId, revisionId, reason }) }),

  tasks: (statuses: FieldTaskStatus[], mine: boolean) => {
    const params = new URLSearchParams({ mine: String(mine), size: '50' })
    if (statuses.length) params.set('status', statuses.join(','))
    return api<Page<TaskView>>(`/field-tasks?${params.toString()}`)
  },
  task: (id: string) => api<TaskView>(`/field-tasks/${id}`),
  assign: (id: string, assigneeId: string | null) =>
    api<TaskView>(`/field-tasks/${id}`, { method: 'PATCH', body: JSON.stringify({ assigneeId }) }),
  submitResult: (id: string, verdict: Verdict, reason: string, collection: CollectionInput | null) =>
    api<TaskView>(`/field-tasks/${id}/result`, { method: 'POST', body: uploadBody({ verdict, reason }, collection) }),

  stubPois: () => api<StubPoi[]>('/review-stub/pois'),
  stubNotifications: () => api<StubNotification[]>('/review-stub/notifications'),
}

export type { PoiRevision }
