# 成员 B 实现与联调

成员 B 负责 M2「POI 采集与地图」：拍照与自动定位、照片保存、分类与描述、采集版本；地图分类查询、错误与进展展示、路线导航入口；OCR 辅助。对应分工见 [四人分工说明](work-allocation.md)，接口边界见 [架构设计](architecture.md) 表二与第 7 节。

代码位置：后端 `backend/src/main/java/edu/sadp/checkcollect/poi`，数据库迁移 `backend/src/main/resources/db/migration/V200__poi.sql`，前端 `frontend/src/modules/poi/api.ts` 与 `frontend/src/views/Poi*View.vue`。

## 1. 与既有模块的关系

- 身份与角色使用 M1 的 `IdentityService.currentUser()`、`requireRole(Role)`，不自建登录态。采集提交要求 `COLLECTOR` 角色且已完成首次改密。
- 页面入口沿用 A 在 `frontend/src/router.ts` 预留的 `/poi`，已替换为真实页面并新增 `/poi/new`、`/poi/:id`、`/poi/:id/revisions/new`。`SecurityConfig` 中对应的 SPA 白名单由 `/poi` 调整为 `/poi/**`。
- M2 不判定错误是否成立、不处理聊天、不直接发布 RabbitMQ 消息。地图上的错误标识与处理进展全部来自 M3 通过 `setReviewSummary` 同步的摘要。

## 2. 启动与新增配置

启动步骤同 [成员 A 实现与联调](member-a.md)：`.env` + PostgreSQL + `powershell -File scripts/run-backend.ps1` + `frontend` 的 `npm run dev`。新增配置项（缺省即可运行）：

| 配置 | 缺省 | 说明 |
| --- | --- | --- |
| `PHOTO_DIR` | `./data/photos` | 照片目录，相对后端运行目录；已在 `.gitignore` 的 `data/` 内。部署时指向可写目录并纳入备份 |
| `MAP_PROVIDER` | `stub` | 地图适配器，当前只有桩实现 |
| `OCR_PROVIDER` | `stub` | 文字识别适配器，当前只有桩实现 |

启动时 Flyway 自动执行 `V200__poi.sql`，新增 `pois`、`poi_revisions`、`poi_photos`、`poi_review_summaries` 四张表。`spring.servlet.multipart` 上限调整为单张 8MB、单次请求 24MB，手机照片超过默认 1MB 时不需要额外改配置。

手机通过局域网的 `http://<电脑IP>:5173` 访问时，浏览器通常禁止定位；演示建议用 `localhost`、HTTPS 或真机加 HTTPS 隧道，否则只能看到“定位被拒绝”的提示，这是浏览器限制，不是接口问题。

## 3. 采集与地图接口

所有写请求需要 `X-XSRF-TOKEN`（`src/api.ts` 已统一处理；multipart 请求由 `src/modules/poi/api.ts` 的 `upload()` 单独取 token）。位置、定位时间与坐标系由前端从 `navigator.geolocation` 取得，接口不接受“只传文字位置”的提交。

| 方法和路径 | 说明 |
| --- | --- |
| `POST /api/pois` | multipart：`photos`（1—5 张）、`category`、`name`、`description`、`lon`、`lat`、`crs`、`locatedAt`（ISO-8601）、`accuracyMeters`。生成 POI 与第 1 个采集版本 |
| `POST /api/pois/{id}/revisions` | 同上，生成新版本，`purpose=CORRECTION`，历史版本保留 |
| `GET /api/pois?category=&bbox=&progress=&page=&size=` | `bbox=minLon,minLat,maxLon,maxLat`；`progress=NONE` 表示尚未同步摘要；返回位置、错误标识、进展摘要与当前版本号 |
| `GET /api/pois/{id}` | POI 摘要加全部采集版本，版本按新到旧排列 |
| `GET /api/pois/{id}/photos/{photoId}` | 照片文件，需登录；路径限定在 `PHOTO_DIR` 内 |
| `POST /api/pois/ocr` | multipart `photo` 一张，返回 `text`、`suggestedCategory`、`simulated`。结果只作建议 |
| `GET /api/pois/{id}/navigation?fromLon=&fromLat=&mode=walk` | 必须提供有效 WGS84 起点，缺少/非法返回 400，不会用终点代替；`mode` 支持 `walk`/`car`，默认 `walk`。返回 `provider`、`simulated`、`url`、原始 WGS84 目标坐标与说明；高德 URL 内部使用转换后的 GCJ-02 起终点 |

错误约定：`400` 表示缺照片、缺定位、坐标或时间不合法、分类非法；`401` 未登录；`403` 非采集者或未改初始密码；`404` POI、版本或照片不存在；`409` 版本号冲突。

## 4. 数据归属与不可变约定

| 表 | 内容 | 写入方 |
| --- | --- | --- |
| `pois` | POI 主体、`current_revision_id`、创建人、创建/更新时间 | M2 |
| `poi_revisions` | 一次采集版本：分类、名称、描述、经纬度、坐标系、定位时间、精度、用途、现场任务标识、提交人 | M2，仅新增，不修改 |
| `poi_photos` | 照片相对路径、内容类型、字节数、`ocr_text` | M2 |
| `poi_review_summaries` | 状态、进展、错误标识、说明、同步时间 | 仅 M3 通过 `setReviewSummary` 写入，M2 只读 |

照片标识、POI 标识、采集版本标识三者不同（`architecture.md` 6.1）。修改照片、位置或描述都会新建版本，核验必须明确针对某个 `revisionId`，避免更新后的内容与旧的错误说明混淆。同一 POI 的版本号在事务内用 `SELECT … FOR UPDATE` 取最大值加一，并发提交不会重号。

## 5. 给 M3（成员 C）的接口

进程内调用，不经 HTTP：

| 方法 | 用途 |
| --- | --- |
| `RevisionView getRevision(UUID revisionId)` | 读取不可变版本内容，含照片元数据；版本不存在抛 `404` |
| `UUID createFieldCheckRevision(UUID poiId, SubmitCommand command, UUID sourceTaskId)` | 第三方现场采集。必须带任务标识，M3 先校验任务归属与处理人权限；M2 只保存 `source_task_id` |
| `PoiSummary setReviewSummary(UUID poiId, UUID revisionId, ReviewSummary summary)` | 同步核验摘要，`state`、`progress` 由 M3 定义，`errorFlag` 决定地图是否显示错误标识 |

`PoiSummary` 是地图展示的唯一依据，核验历史仍以 M3 的记录为准。建议 M3 在修改错误单的同一事务内调用 `setReviewSummary`，使地图进展与核验状态一致；调用方不需要为它单独开事务，方法本身带 `@Transactional`，同事务传播即可。整改入口（`POST /api/issues/{id}/revisions`）由 M3 接收，其内部调用 `submitCorrection` 或 `createFieldCheckRevision`，M2 不另建核验状态机。

## 6. 给 M4（成员 D）的通知接口

`poi/PoiNotifier` 与表二的 `notify(type, target, reference, text)` 对应。M4 实现该接口并注册为 Bean 后自动生效，当前事件为：

- `POI_SUBMITTED`，`target=REVIEWER`，`reference=<poiId>`：新采集待核验；
- `POI_REVISED`，`target=REVIEWER`，`reference=<poiId>`：出现新版本待核验。

M2 只投递必要字段，不决定通知的存储与重试；通知抛异常只记日志，不回滚已提交的采集结果。M4 未落地时没有实现类，采集流程照常可用。

## 7. 地图与 OCR 适配

两者都是接口加桩实现，`MAP_PROVIDER`、`OCR_PROVIDER` 选择实现：

- `MapAdapter`：`provider()`、`simulated()`、`urlFor(fromLon, fromLat, toLon, toLat)`。桩实现生成 OpenStreetMap 位置链接，`simulated=true`，页面显示“模拟入口”。
- `OcrAdapter`：`recognizeText(photoId, image, contentType)` 返回文字与建议分类。桩实现返回空结果、`simulated=true`，页面提示“平台未接入，可人工填写”。

接入真实平台时新增实现类并加 `@Component`、`@ConditionalOnProperty(name = "app.map.provider", havingValue = "amap")`（或 `app.ocr.provider`），把凭据放环境变量。平台 SDK 只能出现在适配器实现内，其他模块与页面不直接调用。

坐标为设备原始 `WGS84` 值，`crs` 字段随版本保存，不与其他坐标系混值。接入高德、百度等使用 GCJ-02/BD-09 的平台时，转换放在适配器内，数据库仍保留原始坐标及其标识。

## 8. 验收检查

从空库启动，确认 `V200__poi.sql` 执行成功，四张表存在。

1. 用 `member-b` 账号（`COLLECTOR`）在手机或浏览器（允许定位）打开 `/poi/new`，拍照后位置自动出现，提交成功并跳转详情；换一个终端用同一账号查看，记录与照片都在。
2. 拒绝定位权限后重试：页面停在采集页并提示定位被拒绝，草稿照片保留，无法提交。用 `curl` 直接提交缺 `lon` 的表单应返回 `400`。
3. 同一 POI 再提交一次，详情页出现第 2 版，第 1 版内容与照片不变，`currentRevisionId` 指向新版本。
4. `/poi` 按分类筛选、点“按我的位置筛选”限定范围后，列表与点位数同步变化。
5. 用核验者或管理员账号调用 `setReviewSummary`（或在 C 完成后通过核验页面），`/poi` 中出现错误标识与进展，详情页“核验摘要”同步。
6. 非采集者（`REVIEWER`）调用 `POST /api/pois` 返回 `403`；未登录访问 `/api/pois` 返回 `401`。
7. 详情页“获取起点并规划导航”展示设备起点、精度与距离；高德模式在站内展示路线与距离，外部链接需用户点击，支持步行/驾车；桩模式明确标注为模拟入口。定位被拒绝不生成伪造起点，低精度或超过 100 公里的起点需再次确认。

## 9. 已知限制

- 照片先落盘再写库，事务回滚时可能留下孤立文件，需要一个清理任务或定期比对，初版不做。
- 地图为示意散点图，不是真实底图；接入 SDK 后替换 `PoiMapView` 的绘图区即可，接口不变。
- 依据 `architecture.md` 表二，针对错误单的整改由 M3 接收；M2 目前只在普通更新场景开放 `POST /api/pois/{id}/revisions`，等 C 的错误单落地后由 C 的入口协调，避免双向依赖。
- 照片只做格式与大小检查，不做内容或地理位置真实性校验；若需要防止伪造位置，应由设备签名或后续规则另行处理，不在本模块范围内。
