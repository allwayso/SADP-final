package edu.sadp.checkcollect.review.port;

import java.util.List;
import java.util.Optional;

/**
 * M3 对 M2（POI 采集与地图）的依赖接口，对应 architecture.md 表二中的
 * {@code getRevision}、采集版本创建及 {@code setReviewSummary}。
 *
 * <p>成员 B 提供真实实现后，将 {@code app.review.poi-stub} 设为 {@code false}，
 * M3 自带的内存桩即不再装配。M3 只经此接口读写 POI 数据，不直接访问 M2 的表。
 */
public interface PoiRevisionPort {
    default void lockPoi(String poiId) {}

    /** 读取不可变的采集版本；不存在时返回空。 */
    Optional<PoiRevision> getRevision(String revisionId);

    /** 每个 POI 当前的最新采集版本，用于计算待检列表。 */
    List<PoiRevision> latestRevisions();

    /** 指定 POI 的最新采集版本；核验只针对最新版本，防止旧版本与新内容混淆。 */
    Optional<PoiRevision> latestRevision(String poiId);

    /**
     * 创建新的采集版本。M3 只以 {@link RevisionPurpose#CORRECTION}（整改）和
     * {@link RevisionPurpose#FIELD_CHECK}（第三方现场采集）调用，调用前已完成身份与任务校验。
     */
    PoiRevision createRevision(RevisionDraft draft);

    /** 同步地图展示用的核验摘要。地图进展以此为准，核验历史以 M3 记录为准。 */
    void setReviewSummary(String poiId, String revisionId, ReviewSummary summary);
}
