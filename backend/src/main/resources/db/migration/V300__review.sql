-- M3 核验与异议处理（成员 C）。POI 与采集版本归 M2 所有，这里只保存其不透明标识。

-- 核验记录：每次核验者对某一采集版本作出的结论
CREATE TABLE review_records (
  id UUID PRIMARY KEY,
  poi_id VARCHAR(64) NOT NULL,
  revision_id VARCHAR(64) NOT NULL,
  reviewer_id UUID NOT NULL REFERENCES app_users(id),
  outcome VARCHAR(16) NOT NULL CHECK (outcome IN ('PASSED', 'ISSUE')),
  issue_id UUID,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
-- 每个采集版本只做一次首次核验；整改版本的复核记在错误单历史中
CREATE UNIQUE INDEX review_records_revision_idx ON review_records(revision_id);
CREATE INDEX review_records_poi_idx ON review_records(poi_id, created_at);

-- 错误单：OPEN 待处理，PENDING_RECHECK 待复核，RESOLVED 已解决，DISPUTED 有异议，
-- IN_ARBITRATION 第三方处理中，CLOSED 已关闭（撤回错误标识或第三方最终裁定）
CREATE TABLE review_issues (
  id UUID PRIMARY KEY,
  poi_id VARCHAR(64) NOT NULL,
  flagged_revision_id VARCHAR(64) NOT NULL,
  current_revision_id VARCHAR(64) NOT NULL,
  collector_id UUID NOT NULL REFERENCES app_users(id),
  reviewer_id UUID NOT NULL REFERENCES app_users(id),
  description VARCHAR(2000) NOT NULL,
  status VARCHAR(20) NOT NULL CHECK (status IN ('OPEN', 'PENDING_RECHECK', 'RESOLVED', 'DISPUTED', 'IN_ARBITRATION', 'CLOSED')),
  final_revision_id VARCHAR(64),
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  closed_at TIMESTAMPTZ,
  CHECK (collector_id <> reviewer_id)
);
-- 初版约定：同一 POI 同时只处理一张未关闭错误单
CREATE UNIQUE INDEX review_issues_one_active_per_poi ON review_issues(poi_id) WHERE status NOT IN ('RESOLVED', 'CLOSED');
CREATE INDEX review_issues_collector_idx ON review_issues(collector_id);
CREATE INDEX review_issues_reviewer_idx ON review_issues(reviewer_id);
ALTER TABLE review_records ADD CONSTRAINT review_records_issue_fk FOREIGN KEY (issue_id) REFERENCES review_issues(id);

-- 错误单处理历史：建立、补充说明、整改、驳回、确认、撤回、异议与裁定均留痕，不修改既有记录
CREATE TABLE review_issue_events (
  id UUID PRIMARY KEY,
  issue_id UUID NOT NULL REFERENCES review_issues(id),
  actor_id UUID NOT NULL REFERENCES app_users(id),
  action VARCHAR(24) NOT NULL,
  from_status VARCHAR(20),
  to_status VARCHAR(20) NOT NULL,
  revision_id VARCHAR(64),
  note VARCHAR(2000),
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX review_issue_events_issue_idx ON review_issue_events(issue_id, created_at);

-- 异议单：当事一方在沟通无果后提交，每张错误单最多一次（最终裁定不可再次修改）
CREATE TABLE review_disputes (
  id UUID PRIMARY KEY,
  issue_id UUID NOT NULL UNIQUE REFERENCES review_issues(id),
  submitted_by UUID NOT NULL REFERENCES app_users(id),
  reason VARCHAR(2000) NOT NULL,
  status VARCHAR(16) NOT NULL CHECK (status IN ('OPEN', 'DECIDED')),
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  decided_at TIMESTAMPTZ
);

-- 第三方现场任务：UNASSIGNED 待分配，ASSIGNED 已分配，COMPLETED 已裁定
CREATE TABLE review_field_tasks (
  id UUID PRIMARY KEY,
  dispute_id UUID NOT NULL UNIQUE REFERENCES review_disputes(id),
  issue_id UUID NOT NULL REFERENCES review_issues(id),
  assignee_id UUID REFERENCES app_users(id),
  status VARCHAR(16) NOT NULL CHECK (status IN ('UNASSIGNED', 'ASSIGNED', 'COMPLETED')),
  verdict VARCHAR(20) CHECK (verdict IN ('ORIGINAL_CORRECT', 'REPLACED')),
  field_revision_id VARCHAR(64),
  final_revision_id VARCHAR(64),
  reason VARCHAR(2000),
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  assigned_at TIMESTAMPTZ,
  decided_at TIMESTAMPTZ,
  CHECK ((status = 'UNASSIGNED') = (assignee_id IS NULL)),
  CHECK ((status = 'COMPLETED') = (verdict IS NOT NULL AND final_revision_id IS NOT NULL AND reason IS NOT NULL))
);
CREATE INDEX review_field_tasks_assignee_idx ON review_field_tasks(assignee_id, status);
