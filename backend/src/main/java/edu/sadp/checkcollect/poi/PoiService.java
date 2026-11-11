package edu.sadp.checkcollect.poi;

import edu.sadp.checkcollect.identity.IdentityService;
import edu.sadp.checkcollect.identity.Role;
import edu.sadp.checkcollect.identity.UserAccount;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * M2 业务服务。职责边界见 docs/architecture.md 第 4、7 节：
 * 拥有 POI、采集版本、照片元数据与核验摘要；不判定错误是否成立，不处理聊天，不直接发布消息。
 * 其他模块通过本类的公开方法调用，不绕回 HTTP。
 */
@Service
public class PoiService {
    private static final int MAX_PHOTOS = 5;
    private static final int MAX_NAME_LENGTH = 120;

    private final PoiRepository pois;
    private final PhotoStorage photos;
    private final IdentityService identity;
    private final OcrAdapter ocr;
    private final MapAdapter map;
    private final PoiNotifier notifier;

    public PoiService(PoiRepository pois, PhotoStorage photos, IdentityService identity,
                      OcrAdapter ocr, MapAdapter map, PoiNotifier notifier) {
        this.pois = pois;
        this.photos = photos;
        this.identity = identity;
        this.ocr = ocr;
        this.map = map;
        this.notifier = notifier;
    }

    /** 采集者提交一条新的 POI，形成第 1 个采集版本。 */
    @Transactional
    public RevisionRef submitNew(SubmitCommand command) {
        UserAccount actor = identity.requireRole(Role.COLLECTOR);
        validate(command);
        UUID poiId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        pois.insertPoi(poiId, actor.id());
        storeRevision(poiId, revisionId, 1, SubmitPurpose.NEW, command, null, actor.id());
        pois.updateCurrentRevision(poiId, revisionId);
        pois.upsertReviewSummary(poiId, revisionId, pendingSummary(), actor.id());
        notify("POI_SUBMITTED", "REVIEWER", poiId.toString(), "有新的 POI 数据待核验：" + command.name().trim());
        return new RevisionRef(poiId, revisionId, 1);
    }

    /**
     * 采集者提交更新或整改版本。错误单仍在处理中时，整改入口由 M3 协调并调用本方法，
     * 参见 architecture.md 表二：针对错误单的整改由 M3 接收。
     */
    @Transactional
    public RevisionRef submitCorrection(UUID poiId, SubmitCommand command, UUID expectedRevisionId) {
        UserAccount actor = identity.requireRole(Role.COLLECTOR);
        validate(command);
        PoiRepository.PoiRow poi = pois.lockPoi(poiId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!poi.createdBy().equals(actor.id())) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只能更新自己采集的 POI");
        if (expectedRevisionId == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请提供当前采集版本");
        if (!expectedRevisionId.equals(poi.currentRevisionId()) || pois.hasActiveIssue(poiId))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "版本已更新或存在活动错误单，请从错误单处理");
        RevisionRef result = appendRevision(poiId, command, SubmitPurpose.CORRECTION, null, actor.id());
        pois.upsertReviewSummary(poiId, result.revisionId(), pendingSummary(), actor.id());
        notify("POI_REVISED", "REVIEWER", poiId.toString(), "POI 更新待核验：" + command.name());
        return result;
    }

    /** 由 M3 在锁定并授权错误单后调用。 */
    @Transactional
    public RevisionRef submitReviewedCorrection(UUID poiId, SubmitCommand command) {
        UserAccount actor = identity.requireRole(Role.COLLECTOR);
        validate(command);
        return appendRevision(poiId, command, SubmitPurpose.CORRECTION, null, actor.id());
    }

    public List<RevisionView> currentRevisions() {
        identity.currentUser();
        return pois.currentRevisions();
    }

    @Transactional
    public void lock(UUID poiId) {
        pois.lockPoi(poiId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "POI 不存在"));
    }

    @Transactional
    public void setWorkflow(UUID poiId, UUID issueId, UUID effectiveRevision) {
        lock(poiId);
        if (!pois.revisionBelongsTo(effectiveRevision, poiId)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        pois.setActiveIssue(poiId, issueId);
        pois.updateCurrentRevision(poiId, effectiveRevision);
    }

    private ReviewSummary pendingSummary() {
        return new ReviewSummary("PENDING_REVIEW", "PENDING_REVIEW", false, "待核验", Instant.now());
    }

    /**
     * 第三方现场采集，供 M3 在确认任务归属后调用。必须带任务标识，M2 不校验任务权限。
     */
    @Transactional
    public RevisionRef createFieldCheckRevision(UUID poiId, SubmitCommand command, UUID sourceTaskId) {
        if (sourceTaskId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "现场采集必须提供已分配的任务标识");
        }
        UserAccount actor = identity.requireRole(Role.FIELD_WORKER);
        validate(command);
        return appendRevision(poiId, command, SubmitPurpose.FIELD_CHECK, sourceTaskId, actor.id());
    }

    public PoiPage search(PoiCategory category, Double[] bbox, String progress, int page, int size) {
        identity.currentUser();
        int safeSize = Math.min(Math.max(size, 1), 200);
        int safePage = Math.max(page, 0);
        List<PoiSummary> items = pois.search(category, bbox, progress, safeSize, safePage * safeSize);
        return new PoiPage(items, safePage, safeSize, pois.count(category, bbox, progress));
    }

    public PoiDetail detail(UUID poiId) {
        identity.currentUser();
        PoiSummary summary = summary(poiId);
        return new PoiDetail(summary, pois.revisions(poiId));
    }

    /** M3 读取指定采集版本。返回内容不可变。 */
    public RevisionView getRevision(UUID revisionId) {
        identity.currentUser();
        return pois.findRevision(revisionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "采集版本不存在"));
    }

    /** M3 同步核验摘要，供地图展示错误标识与处理进展。M2 不据此改变核验状态。 */
    @Transactional
    public PoiSummary setReviewSummary(UUID poiId, UUID revisionId, ReviewSummary summary) {
        UserAccount actor = identity.currentUser();
        if (summary == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "核验摘要不能为空");
        }
        pois.findPoi(poiId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "POI 不存在"));
        if (revisionId != null && !pois.revisionBelongsTo(revisionId, poiId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "该采集版本不属于此 POI");
        }
        pois.upsertReviewSummary(poiId, revisionId, summary, actor.id());
        return summary(poiId);
    }

    public PhotoContent photo(UUID poiId, UUID photoId) {
        identity.currentUser();
        PoiRepository.PhotoRow row = pois.findPhoto(poiId, photoId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "照片不存在"));
        return new PhotoContent(photos.load(row.storedPath()), row.contentType());
    }

    /** OCR 只提供建议文字，采集者确认后才写入业务数据。 */
    public OcrAdapter.OcrSuggestion suggestCategory(MultipartFile file) {
        identity.currentUser();
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请先拍摄或选择一张照片再识别");
        }
        String contentType = PhotoStorage.normaliseType(file.getContentType());
        if (!PhotoStorage.ALLOWED_TYPES.contains(contentType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "只支持 JPEG、PNG、WebP 或 HEIC 格式的照片");
        }
        try {
            return ocr.recognizeText(UUID.randomUUID(), file.getBytes(), contentType);
        } catch (IOException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "照片读取失败，请重新选择", error);
        }
    }

    public NavigationTarget navigation(UUID poiId, Double fromLon, Double fromLat, String mode) {
        identity.currentUser();
        if (fromLon == null || fromLat == null || !Double.isFinite(fromLon) || !Double.isFinite(fromLat)
                || Math.abs(fromLon) > 180 || Math.abs(fromLat) > 90) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请先授权定位以取得有效导航起点，不能用目标点代替起点");
        }
        if (mode == null || !List.of("walk", "car").contains(mode)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "出行方式只支持步行或驾车");
        }
        PoiSummary poi = summary(poiId);
        if (!"WGS84".equals(poi.crs())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "目标点坐标系不支持，请核对采集数据");
        }
        String url = map.urlFor(fromLon, fromLat, poi.lon(), poi.lat(), mode);
        String note = map.simulated()
                ? "当前使用地图桩适配器：只能打开目标位置，未提供真实路线规划与导航。"
                : "核对起点后打开高德路线；手机可尝试调起高德 App。路线是否可用以地图平台结果为准。";
        return new NavigationTarget(map.provider(), map.simulated(), url, poi.lon(), poi.lat(), poi.crs(), note);
    }

    private RevisionRef appendRevision(UUID poiId, SubmitCommand command, SubmitPurpose purpose,
                                       UUID sourceTaskId, UUID submittedBy) {
        pois.lockPoi(poiId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "POI 不存在"));
        int revisionNo = pois.nextRevisionNo(poiId);
        UUID revisionId = UUID.randomUUID();
        storeRevision(poiId, revisionId, revisionNo, purpose, command, sourceTaskId, submittedBy);
        if (purpose != SubmitPurpose.FIELD_CHECK) pois.updateCurrentRevision(poiId, revisionId);
        return new RevisionRef(poiId, revisionId, revisionNo);
    }

    private void storeRevision(UUID poiId, UUID revisionId, int revisionNo, SubmitPurpose purpose,
                               SubmitCommand command, UUID sourceTaskId, UUID submittedBy) {
        pois.insertRevision(revisionId, poiId, revisionNo, command, purpose, sourceTaskId, submittedBy);
        for (MultipartFile file : command.photos()) {
            UUID photoId = UUID.randomUUID();
            PhotoStorage.StoredPhoto stored = photos.store(poiId, revisionId, photoId, file);
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override public void afterCompletion(int status) {
                            if (status != STATUS_COMMITTED) photos.remove(stored.relativePath());
                        }
                    });
            pois.insertPhoto(photoId, revisionId, stored, null);
        }
    }

    private PoiSummary summary(UUID poiId) {
        return pois.findSummary(poiId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "POI 不存在"));
    }

    private void validate(SubmitCommand command) {
        if (command.category() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择 POI 分类");
        }
        String name = command.name() == null ? "" : command.name().trim();
        if (name.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请填写 POI 名称");
        }
        if (name.length() > MAX_NAME_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "POI 名称不能超过 " + MAX_NAME_LENGTH + " 个字符");
        }
        if (command.photos() == null || command.photos().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请先拍摄或选择至少一张照片");
        }
        if (command.photos().size() > MAX_PHOTOS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "一次提交最多 " + MAX_PHOTOS + " 张照片");
        }
        if (!Double.isFinite(command.lon()) || command.lon() < -180 || command.lon() > 180
                || !Double.isFinite(command.lat()) || command.lat() < -90 || command.lat() > 90) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "定位坐标无效，请重新定位后再提交");
        }
        if (!"WGS84".equals(command.crs())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少坐标系标识，请由设备重新定位");
        }
        if (command.accuracyMeters() != null && (!Double.isFinite(command.accuracyMeters()) || command.accuracyMeters() < 0)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "定位精度无效");
        }
        if (command.description() != null && command.description().length() > 2000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "描述不能超过 2000 字");
        }
        if (command.locatedAt() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少拍照定位时间，请由设备重新定位");
        }
        if (command.locatedAt().isAfter(Instant.now().plusSeconds(600))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "定位时间异常，请重新定位");
        }
    }

    private void notify(String type, String target, String reference, String text) {
        notifier.notify(type, target, reference, text);
    }

    public record PhotoContent(Resource resource, String contentType) {}
}
