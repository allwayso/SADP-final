-- M4 消息与交流：系统通知、收件与已读状态、错误单私聊、按角色管理的群聊。
-- 接收范围在创建通知时快照为 notification_recipients，重试不会改变收件人。

CREATE TABLE notifications (
  id UUID PRIMARY KEY,
  kind VARCHAR(40) NOT NULL,
  target_kind VARCHAR(8) NOT NULL CHECK (target_kind IN ('USER', 'ROLE')),
  target_user_id UUID REFERENCES app_users(id),
  target_role VARCHAR(32),
  reference_kind VARCHAR(20),
  reference_id VARCHAR(64),
  title VARCHAR(200) NOT NULL,
  body VARCHAR(2000) NOT NULL,
  sender_id UUID REFERENCES app_users(id),
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'FAILED', 'DELIVERED')),
  attempts INTEGER NOT NULL DEFAULT 0,
  last_error VARCHAR(1000),
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  last_attempt_at TIMESTAMPTZ,
  delivered_at TIMESTAMPTZ,
  CONSTRAINT notifications_target_check CHECK (
    (target_kind = 'USER' AND target_user_id IS NOT NULL AND target_role IS NULL)
    OR (target_kind = 'ROLE' AND target_role IS NOT NULL AND target_user_id IS NULL)
  )
);

CREATE INDEX notifications_status_idx ON notifications (status, last_attempt_at);

CREATE TABLE notification_recipients (
  notification_id UUID NOT NULL REFERENCES notifications(id) ON DELETE CASCADE,
  user_id UUID NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  delivered_at TIMESTAMPTZ,
  read_at TIMESTAMPTZ,
  PRIMARY KEY (notification_id, user_id)
);

CREATE INDEX notification_recipients_user_idx ON notification_recipients (user_id, read_at);

CREATE TABLE conversations (
  id UUID PRIMARY KEY,
  kind VARCHAR(16) NOT NULL CHECK (kind IN ('ISSUE', 'ROLE_GROUP')),
  title VARCHAR(200) NOT NULL,
  role VARCHAR(32),
  issue_id VARCHAR(64),
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (kind, role),
  UNIQUE (kind, issue_id),
  CONSTRAINT conversations_kind_target_check CHECK (
    (kind = 'ROLE_GROUP' AND role IS NOT NULL AND issue_id IS NULL)
    OR (kind = 'ISSUE' AND role IS NULL AND issue_id IS NOT NULL)
  )
);

CREATE TABLE conversation_members (
  conversation_id UUID NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
  user_id UUID NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
  joined_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  PRIMARY KEY (conversation_id, user_id)
);

CREATE TABLE chat_messages (
  id UUID PRIMARY KEY,
  conversation_id UUID NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
  sender_id UUID NOT NULL REFERENCES app_users(id),
  body VARCHAR(2000) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX chat_messages_conversation_idx ON chat_messages (conversation_id, created_at, id);
