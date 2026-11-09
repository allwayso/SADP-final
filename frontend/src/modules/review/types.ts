/** M3 核验与异议处理的前端类型，与后端 ReviewViews 中的 DTO 一一对应。 */

export type IssueStatus = 'OPEN' | 'PENDING_RECHECK' | 'RESOLVED' | 'DISPUTED' | 'IN_ARBITRATION' | 'CLOSED'
export type FieldTaskStatus = 'UNASSIGNED' | 'ASSIGNED' | 'COMPLETED'
export type Verdict = 'ORIGINAL_CORRECT' | 'REPLACED'
export type RevisionPurpose = 'NEW' | 'CORRECTION' | 'FIELD_CHECK'
export type ReviewOutcome = 'PASSED' | 'ISSUE'
export type ReviewProgress =
  | 'PENDING_REVIEW'
  | 'PASSED'
  | 'ISSUE_OPEN'
  | 'PENDING_RECHECK'
  | 'DISPUTED'
  | 'IN_ARBITRATION'
  | 'FINAL'
export type IssueAction =
  | 'CREATED'
  | 'NOTE'
  | 'CORRECTED'
  | 'REJECTED'
  | 'RESUMED'
  | 'CONFIRMED'
  | 'CHANGES_REQUESTED'
  | 'WITHDRAWN'
  | 'DISPUTED'
  | 'ASSIGNED'
  | 'FIELD_COLLECTED'
  | 'DECIDED'

export type UserRef = { id: string; displayName: string }

export type PoiRevision = {
  id: string
  poiId: string
  version: number
  purpose: RevisionPurpose
  submittedBy: string
  category: string
  name: string | null
  description: string | null
  latitude: number
  longitude: number
  accuracyMeters: number | null
  coordinateSystem: string
  locatedAt: string
  photoId: string
  photoIds: string[]
  createdAt: string
}

/** 采集内容：位置来自设备定位，没有手工录入坐标的入口。 */
export type CollectionInput = {
  photos?: File[]
  category: string
  name?: string
  description?: string
  latitude: number
  longitude: number
  accuracyMeters?: number | null
  coordinateSystem: string
  locatedAt: string
  photoId: string
}

export type PendingReview = { revision: PoiRevision; collector: UserRef }

export type ReviewRecordView = {
  id: string
  poiId: string
  revisionId: string
  reviewer: UserRef
  outcome: ReviewOutcome
  issueId: string | null
  createdAt: string
}

export type IssueView = {
  id: string
  poiId: string
  flaggedRevisionId: string
  currentRevisionId: string
  finalRevisionId: string | null
  status: IssueStatus
  description: string
  collector: UserRef
  reviewer: UserRef
  createdAt: string
  updatedAt: string
  closedAt: string | null
}

export type EventView = {
  action: IssueAction
  actor: UserRef
  fromStatus: IssueStatus | null
  toStatus: IssueStatus
  revisionId: string | null
  note: string | null
  createdAt: string
}

export type DisputeView = {
  id: string
  issueId: string
  submittedBy: UserRef
  reason: string
  status: 'OPEN' | 'DECIDED'
  createdAt: string
  decidedAt: string | null
}

export type TaskView = {
  id: string
  disputeId: string
  issueId: string
  poiId: string
  status: FieldTaskStatus
  assignee: UserRef | null
  verdict: Verdict | null
  fieldRevisionId: string | null
  finalRevisionId: string | null
  reason: string | null
  disputeReason: string | null
  createdAt: string
  assignedAt: string | null
  decidedAt: string | null
}

export type IssueDetail = {
  conversationId: string | null
  issue: IssueView
  flaggedRevision: PoiRevision | null
  currentRevision: PoiRevision | null
  finalRevision: PoiRevision | null
  events: EventView[]
  dispute: DisputeView | null
  task: TaskView | null
  viewerRole: 'COLLECTOR' | 'REVIEWER' | 'FIELD_WORKER' | 'ADMIN'
  allowedActions: IssueAction[]
}

export type ReviewHistory = {
  poiId: string
  reviews: ReviewRecordView[]
  issues: IssueView[]
}

export type Page<T> = { items: T[]; page: number; size: number }

/** M2 桩中同步给地图的核验摘要与通知桩记录，仅联调时可见。 */
export type StubPoi = {
  poiId: string
  revisionId: string
  name: string | null
  category: string
  revisionCount: number
  progress: ReviewProgress | null
  note: string | null
}

export type StubNotification = {
  id: string
  type: string
  target: string
  referenceType: string
  referenceId: string
  text: string
  createdAt: string
}
