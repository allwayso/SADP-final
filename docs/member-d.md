# 成员 D 实现与联调：M4 消息与交流

成员 D 负责 M4 消息与交流：系统通知的角色群发与点发、离线可读与已读状态、错误单私聊、两个角色群聊，以及 RabbitMQ 的发布消费、失败重试和重复消费去重。实现建立在成员 A 的 M1 身份与前后端骨架上，未改动 M1 的登录、账号和角色规则。

## 1. 交付内容

| 内容 | 位置 |
| --- | --- |
| 数据库迁移（通知、收件、会话、聊天消息） | `backend/src/main/resources/db/migration/V2__messaging.sql` |
| 后端 M4 模块 | `backend/src/main/java/edu/sadp/checkcollect/messaging/` |
| 前端 M4 页面 | `frontend/src/modules/messaging/` |
| 导航与路由接入 | `frontend/src/App.vue`、`frontend/src/router.ts`（`/messages`） |
| 开发消息桩与故障注入 | `MessagingStubController`、`BrokerSimulation` |
| 端到端验收脚本 | `scripts/smoke-m4.ps1` |
| 配置示例 | `.env.example`、`application.yml` 的 `app.messaging` 与 `spring.rabbitmq` |

M4 不实现采集、核验和裁定规则：通知由 B/C 在业务动作发生时调用，M4 只负责持久化、投递和页面展示；聊天的访问范围由业务传入的当事双方决定。

## 2. 启动与配置

### 2.1 依赖与启动

1. 启动 PostgreSQL：`docker compose up -d postgres`（或使用本地 PostgreSQL，并设置 `DATABASE_URL`）。
2. 启动 RabbitMQ：`docker compose --profile messaging up -d rabbitmq`；管理页 `http://localhost:15672`，默认账号 `guest/guest`。
3. 复制 `.env.example` 为 `.env`，确认数据库、管理员、演示账号和消息配置。
4. 启动后端：`powershell -File scripts/run-backend.ps1`。Flyway 自动执行 V1、V2 迁移，首次启动后 M4 预建“采集者交流”“核验者交流”两个群聊。
5. 前端：进入 `frontend/` 运行 `npm ci`、`npm run dev`，打开 `http://localhost:5173/messages`。

演示账号由 `APP_SEED_DEMO=true` 创建，初始密码为 `DEMO_PASSWORD`，首次登录必须改密：

| 账号 | 角色 | 可看到的会话 |
| --- | --- | --- |
| member-a | 管理员、采集者 | 采集者交流 |
| member-b | 采集者 | 采集者交流 |
| member-c | 核验者 | 核验者交流 |
| member-d | 第三方工作人员 | 由错误单/异议流程建立的私聊 |

### 2.2 消息配置

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `MESSAGING_TRANSPORT` | `rabbit` | `rabbit` 使用 RabbitMQ；`in-process` 不依赖中间件，直接调用同一套幂等投递逻辑，供无 RabbitMQ 的本地联调。 |
| `MESSAGING_EXCHANGE` | `checkcollect.notifications` | 通知主题交换机（TopicExchange，持久化）。 |
| `MESSAGING_QUEUE` | `checkcollect.notifications.delivery` | 通知消费队列（持久化），一个应用实例消费一个队列。 |
| `MESSAGING_ROUTING_KEY` | `notification` | 发布路由键。 |
| `MESSAGING_RETRY_INTERVAL_MS` | `15000` | 重试扫描间隔与失败退避时间。 |
| `MESSAGING_MAX_ATTEMPTS` | `20` | 单条通知最多发布次数，超过后保持“待重试”记录，不再自动补投，避免无限循环。 |
| `MESSAGING_STUB_ENABLED` | `true` | 开发消息桩开关；生产环境必须设为 `false`。 |
| `RABBITMQ_HOST/PORT/USERNAME/PASSWORD` | `localhost/5672/guest/guest` | RabbitMQ 连接参数；`spring.rabbitmq.connection-timeout` 默认 3 秒。 |

没有 RabbitMQ 时也可以完整演示：在 `.env` 中设置 `MESSAGING_TRANSPORT=in-process`，通知仍会写入数据库并按相同的幂等逻辑确认收件；页面、群聊、私聊、已读、重试和去重行为不变。该模式明确属于开发联调降级，不代表已经使用消息中间件。

## 3. 数据模型

| 表 | 作用 | 关键约束/说明 |
| --- | --- | --- |
| `notifications` | 一条系统通知 | `target_kind` 为 `USER`（点发）或 `ROLE`（角色群发），二者互斥；`status` 为 `PENDING/FAILED/DELIVERED`；保存 `reference_kind`、`reference_id`、发送人和尝试次数。 |
| `notification_recipients` | 收件记录 | 主键 `(notification_id, user_id)`；`delivered_at` 是投递确认时间，`read_at` 是已读时间。 |
| `conversations` | 会话 | `kind` 为 `ISSUE`（错误单私聊）或 `ROLE_GROUP`（角色群聊）；`(kind, role)` 和 `(kind, issue_id)` 唯一，保证同一错误单只有一个私聊、每个角色只有一个群聊。 |
| `conversation_members` | 错误单私聊的当事双方 | 角色群聊不写成员表，成员按当前角色动态确定。 |
| `chat_messages` | 聊天消息 | 按会话和时间建立索引，保存发送人、正文和服务端时间。 |

**接收范围在创建通知时固定。** `notifyRole` 调用 `IdentityService.listUsers(role)` 取得当时启用的角色成员，写入收件记录；之后即使角色成员变化或消息被重试，收件人也不会改变，符合架构文档“角色群发以创建通知时的角色成员为接收范围”的要求。

**已读按收件人记录。** 同一条群发通知对每个收件人分别保存已读时间，所以“离线后登录仍能看到、自己读过的不会变成别人已读”。

## 4. 投递流程、失败重试与去重

### 4.1 正常流程

1. B/C 调用 `notifyRole`/`notifyUser`。M4 在一个事务中写入 `notifications` 与按接收快照生成的 `notification_recipients`（`delivered_at` 为空），提交后立即返回通知标识，不把通知失败带回业务事务。
2. 提交完成后由 `NotificationPublisher` 发布：先记录一次尝试，再调用传输层。
   - `rabbit`：`RabbitTemplate` 向交换机发送通知标识（字符串），路由到 `MESSAGING_QUEUE`。
   - `in-process`：直接调用同一套投递服务。
3. RabbitMQ 消费端 `RabbitNotificationConsumer` 收到标识后执行幂等投递：
   - `UPDATE notification_recipients SET delivered_at = NOW() WHERE notification_id = ? AND delivered_at IS NULL`，只确认尚未确认的收件人；
   - `UPDATE notifications SET status = 'DELIVERED' ... WHERE id = ? AND status <> 'DELIVERED'`，只有第一次真正改变状态；
   - 收件人数量、新增确认数量写入日志，便于演示时观察。
4. 页面通过 `GET /api/notifications` 读取收件记录；读取与是否已投递无关，因此中间件短暂不可用时通知也不会丢失，只是稍后完成投递确认。

**与架构文档 §6.4 的实现细化**：架构阶段写的是“消费端为收件人建立通知收件记录”。实现时把收件记录提前到创建通知的同一事务中建立（否则无法在创建时固定角色成员范围），消费端改为按“通知标识 + 收件人”幂等确认。去重键仍是 `(notification_id, user_id)` 主键，重试和重复消费都不会产生重复收件记录。`architecture.md` §6.4 已按此同步措辞。

### 4.2 失败重试

- 发布失败（RabbitMQ 不可用、模拟故障）时，`NotificationPublisher` 把状态改为 `FAILED` 并记录错误摘要，业务接口仍成功返回。
- `NotificationRetryJob` 每 `MESSAGING_RETRY_INTERVAL_MS` 扫描一次：`status <> 'DELIVERED' AND attempts < maxAttempts`，并避开刚刚尝试过的记录（退避）。
- 重试成功后消费端把状态改为 `DELIVERED`；重复投递由上面的幂等语句兜底。
- 消费端捕获异常后不重新入队，避免毒消息循环，依赖数据库状态重试；这保证“中间件短暂不可用”不会丢通知，也不会无限重投。

### 4.3 去重证据

消息桩提供 `POST /api/messaging/stub/redeliver/{id}`，直接对同一条通知再执行一次消费逻辑。返回值中：

- 第一次（已投递）`statusChanged=false`、`newlyDeliveredRecipients=0`；
- 重复调用仍是 `0`，`recipients` 不变；
- 收件人的通知列表里该通知始终只有一条。

验收脚本第 6 节会自动检查这些条件。

## 5. 接口

### 5.1 浏览器接口

所有接口走登录态和 CSRF（写请求带 `X-XSRF-TOKEN`），身份一律取自会话，不接受前端传入操作人。

| 方法和路径 | 说明 |
| --- | --- |
| `GET /api/notifications?page=0&size=20` | 当前用户的通知分页，返回 `items`、`total`、`unread` |
| `GET /api/notifications/unread-count` | 未读数量，用于导航角标 |
| `POST /api/notifications/{id}/read` | 标记单条已读；只能操作自己的收件记录 |
| `POST /api/notifications/read-all` | 标记全部已读，返回更新条数 |
| `GET /api/conversations` | 当前用户可参与的会话：匹配角色的群聊 + 作为当事一方的错误单私聊 |
| `GET /api/conversations/{id}/members` | 会话成员；角色群聊按当前角色成员返回 |
| `GET /api/conversations/{id}/messages?after=ISO-8601` | 拉取会话消息；不带 `after` 取最近 200 条，带 `after` 取增量，用于轮询 |
| `POST /api/conversations/{id}/messages` | 发送消息，`{"body":"..."}`；仅会话成员可发 |

权限失败返回 `403`，会话或通知不存在返回 `404`；首次登录未改密由 M1 的过滤器统一返回 `403`。

### 5.2 供 B、C 调用的进程内接口

`MessagingService` 在同进程内调用，不绕回 HTTP：

```java
// M2：新采集/整改版本进入待核验，群发全部核验者
messaging.notifyRole(NotificationKind.NEW_DATA, Role.REVIEWER,
        NotificationReference.of("POI", poiId.toString()),
        "有新的 POI 待核验",
        "采集者提交了点位 " + poiId + "，请核验照片与位置。");

// M3：错误标识后点发原采集者
messaging.notifyUser(NotificationKind.ISSUE_RAISED, collectorId,
        NotificationReference.of("ISSUE", issueId),
        "你的采集数据被标记为错误",
        "请查看错误说明并选择重新采集、更新或驳回。");

// M3：建立错误单私聊，当事双方固定
messaging.openIssueConversation(issueId, "错误单 " + issueId + " 讨论", collectorId, reviewerId);

// M3：异议任务点发第三方、最终裁定点发双方
messaging.notifyUser(NotificationKind.FIELD_TASK, fieldWorkerId,
        NotificationReference.of("TASK", taskId), "你有一个现场复核任务", "...");
messaging.notifyUser(NotificationKind.FINAL_RESULT, collectorId,
        NotificationReference.of("TASK", taskId), "异议已有最终结果", "...");
```

- 发送人默认取当前登录用户；后台任务或系统事件使用 `notifyRoleFrom(null, ...)`、`notifyUserFrom(null, ...)` 表示系统发出。
- 通知类型见 `NotificationKind`：`NEW_DATA`、`ANNOUNCEMENT`、`ISSUE_RAISED`、`ISSUE_RESPONSE`、`DISPUTE_OPENED`、`FIELD_TASK`、`FINAL_RESULT`。
- 关联对象只保存 `kind + id`（如 `POI`、`ISSUE`、`TASK`），M4 不读取 B/C 的业务表。
- 收件人不存在时抛 `404`；标题超过 200 字、正文超过 2000 字抛 `400`。

### 5.3 开发消息桩接口

消息桩在 B/C 的真实流程接入前模拟其 `notify` 调用，并暴露投递诊断；由 `MESSAGING_STUB_ENABLED` 控制，生产关闭。

| 方法和路径 | 说明 |
| --- | --- |
| `GET /api/messaging/stub/status` | 当前投递通道、模拟中间件状态、各状态数量、当前账号最近 12 条通知的尝试次数/收件人数/错误摘要 |
| `POST /api/messaging/stub/scenario` | `{"scenario":"NEW_DATA"}` 等五种演练场景 |
| `POST /api/messaging/stub/fault` | `{"down":true|false}` 模拟中间件中断与恢复 |
| `POST /api/messaging/stub/retry` | 忽略退避立即重试待投递通知 |
| `POST /api/messaging/stub/redeliver/{id}` | 模拟重复投递，验证去重；只能操作自己发出或收到的通知 |

五种场景：`NEW_DATA`（群发核验者）、`ANNOUNCEMENT`（群发采集者）、`ISSUE_RAISED`（点发采集者并建立私聊）、`FIELD_TASK`（点发第三方）、`FINAL_RESULT`（分别点发当事双方）。场景使用真实账号和模拟编号，不写入 POI/核验业务表，页面上标注“模拟”。

## 6. 页面

`/messages` 是 M4 的页面，导航栏“消息”带未读角标；进入后分两栏页签：

- **通知**：按时间倒序列出通知类型、标题、内容、来源与关联对象；“标为已读/全部标为已读”；分页；每 20 秒轮询一次（标签页不可见时暂停）。
- **交流**：左侧会话列表（角色群聊、错误单讨论）显示最近发言摘要；右侧聊天面板显示成员、消息气泡和输入框；每 5 秒增量拉取新消息（`after` 参数），发送后立即追加。
- **开发消息桩**：折叠面板，提供五个场景按钮、模拟中断/恢复、立即重试和每条通知的“重复投递”；表格展示状态、尝试次数、收件/已确认数量，便于演示失败重试与去重。
- 移动端单列布局；无会话、无通知有对应空状态；`aria-live`、键盘 Enter 发送、Shift+Enter 换行。

角色与权限在服务端再次校验，不依赖前端隐藏：非角色成员看不到群聊，非当事双方看不到错误单私聊，通知只能读自己的收件记录。

## 7. 验收与演示

### 7.1 自动验收

后端启动后执行：

```powershell
powershell -File scripts/smoke-m4.ps1 -Password <演示账号当前密码>
# 首次登录时：
powershell -File scripts/smoke-m4.ps1 -Password <初始密码> -NewPassword <新密码>
```

脚本对 member-a 到 member-d 分别建立会话，覆盖 23 项检查：角色群发、未读/已读、两个角色群聊、非成员越权被拒、点发隔离、错误单私聊双方可读而第三方被拒、故障注入后的待重试与补投、重复投递去重。`rabbit` 和 `in-process` 两种通道都可以运行；脚本中“失败重试”一节的故障注入与传输通道无关。

### 7.2 手工演示

1. **角色群发**：member-a 在消息页打开“开发消息桩”，点“新数据待检”，切到 member-c 的账号，通知页出现未读通知，点“标为已读”后未读数减一。
2. **两个群聊**：member-b 进入“交流”，看到“采集者交流”并发言；member-c 看到“核验者交流”；member-d 看不到这两个群，直接请求接口返回 `403`。
3. **错误单私聊**：member-a 点“错误标识”，系统点发 member-b 并建立私聊；member-b 在“交流”回复；member-a 能看到回复；member-d 打开该私聊返回 `403`。
4. **失败重试**：member-a 点“模拟中间件中断”，再点“暂停工作公告”，通知变成“待重试”并累计尝试次数；点“恢复中间件”“立即重试”，状态变为“已投递”，member-b 能读取公告。
5. **重复消费去重**：在消息桩表格中点两次“重复投递”，提示“重复投递已去重：收件人 N，本次新增确认 0”，收件人数不变。

使用 RabbitMQ 时可在管理页 `http://localhost:15672` 观察 `checkcollect.notifications` 交换机、`checkcollect.notifications.delivery` 队列的发布与消费曲线；关闭 RabbitMQ 再发通知即可看到 `FAILED` 状态，启动后由重试任务补投。

### 7.3 本次开发环境的验收记录

- 环境：Windows、Java 21（编译目标 17）、Spring Boot 4.0.9、PostgreSQL 18.3、Node.js 20。
- 从空数据库启动，Flyway 自动执行 V1、V2；`scripts/smoke-m4.ps1` 在真实 PostgreSQL 上以 `MESSAGING_TRANSPORT=in-process` 运行，**23/23 项检查通过**（角色群发、点发、已读、两个群聊、越权拒绝、错误单私聊、故障注入后的待重试与补投、重复投递去重）。
- 另以 `MESSAGING_TRANSPORT=rabbit` 启动（本机未安装 RabbitMQ）：应用正常启动，日志显示消费容器尝试连接 `localhost:5672` 并声明 `checkcollect.notifications.delivery` 队列；此时发通知接口仍返回成功，通知记录为 `FAILED` 并带有连接错误摘要，证明发布失败不会回滚业务、重试机制接管。真实 broker 上的收发需按 `compose.yaml` 启动 RabbitMQ；两种通道共用 `NotificationDeliveryService`，投递、确认、去重逻辑相同。
- 浏览器端用 Edge 无头模式检查：登录、通知列表与未读角标、角色群聊与错误单私聊切换、发送消息、消息桩五个场景、投递诊断表格均正常；1360px 与 390px 宽度下无横向溢出，除登录前的预期 401 探测外无前端报错。

## 8. 与其他模块的边界

- **D → M1**：只使用 `IdentityService.currentUser()`、`listUsers(Role)`；未修改 M1 的接口和账号规则，未新增跨模块表访问。
- **M2/M3 → D**：只通过 `MessagingService.notify*`、`openIssueConversation` 调用，不直接写通知表、不自行发布 RabbitMQ 消息；M4 不根据消息内容改变核验或异议状态。
- **M4 不反向依赖 B/C**：通知中的 `reference` 只是跳转线索，M4 不读取 POI、核验、异议表。
- **前端**：公共布局仍由 A 维护，D 只在 `router.ts` 注册 `/messages`、在 `App.vue` 增加带未读角标的导航入口；模块样式集中在 `frontend/src/modules/messaging/messaging.css`。
- **对 A 文件的兼容性改动**：`scripts/run-backend.ps1`、`scripts/build-app.ps1` 增加 UTF-8 BOM，修正 Windows PowerShell 5.1 以 ANSI 读取中文脚本导致的解析错误；未改变脚本逻辑。

## 9. 已知限制

- 初版使用定时轮询（通知 20 秒、聊天 5 秒），未引入 WebSocket；符合架构“业务不依赖用户持续在线”的取舍。
- 角色群发在单实例内按创建时的成员快照投递；若以后多实例部署，需要把重试任务做成分布式锁或独立任务。
- 消息桩仅用于联调演示，生产必须设置 `MESSAGING_STUB_ENABLED=false`；它创建的示例通知带“模拟”字样。
- 未实现聊天消息撤回、附件、@ 提醒和复杂已读回执；错误单私聊在创建后不动态增加成员。
- 通知投递使用数据库重试而非 RabbitMQ 死信队列，避免为课程场景增加额外组件；超过最大尝试次数后保留待重试记录供人工排查。
- 微信、OCR、地图等外部平台不在 M4 范围内。

## 10. 需求对照

| 课程要求 | 实现与验证 |
| --- | --- |
| 系统向所有采集者群发通知（如暂停工作） | `notifyRole(ANNOUNCEMENT, COLLECTOR, ...)`；场景“暂停工作公告”；member-b 收到 |
| 系统向所有核验者群发任务（如新数据待检） | `notifyRole(NEW_DATA, REVIEWER, ...)`；member-c 收到 |
| 发现错误提交后向采集者点发质量问题 | `notifyUser(ISSUE_RAISED, collectorId, ...)`；只发当事人 |
| 采集者与核验者私聊、无法达成共识提交异议 | 错误单私聊（当事双方）；消息桩 `ISSUE_RAISED` 场景演示 |
| 系统收到异议向第三方发消息、结果通知双方 | `FIELD_TASK`、`FINAL_RESULT` 通知类型与场景 |
| 两个角色群聊 | 启动时预建“采集者交流”“核验者交流”，成员按角色 |
| 离线可读与已读 | 收件记录持久化，`read_at` 按用户；重新登录仍可读 |
| 消息中间件（群发、点发） | RabbitMQ TopicExchange + 单消费队列；失败重试、幂等去重 |
