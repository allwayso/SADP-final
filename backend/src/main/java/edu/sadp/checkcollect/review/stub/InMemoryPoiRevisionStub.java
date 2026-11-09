package edu.sadp.checkcollect.review.stub;

import edu.sadp.checkcollect.review.port.PoiRevision;
import edu.sadp.checkcollect.review.port.PoiRevisionPort;
import edu.sadp.checkcollect.review.port.ReviewSummary;
import edu.sadp.checkcollect.review.port.RevisionDraft;
import edu.sadp.checkcollect.review.port.RevisionPurpose;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * M2 尚未接入时的 POI 版本桩。数据保存在内存，应用重启后清空，仅用于 M3 独立开发与演示。
 * 版本不可变；每次创建都会生成新的版本标识与递增版本号。
 */
public class InMemoryPoiRevisionStub implements PoiRevisionPort {

    /** 已同步的核验摘要，便于在桩模式下观察地图进展。 */
    public record StoredSummary(String poiId, String revisionId, ReviewSummary summary, Instant updatedAt) {}

    private final Map<String, PoiRevision> revisions = new LinkedHashMap<>();
    private final Map<String, List<String>> revisionsByPoi = new LinkedHashMap<>();
    private final Map<String, StoredSummary> summaries = new LinkedHashMap<>();

    @Override
    public synchronized Optional<PoiRevision> getRevision(String revisionId) {
        return Optional.ofNullable(revisions.get(revisionId));
    }

    @Override
    public synchronized List<PoiRevision> latestRevisions() {
        return revisionsByPoi.values().stream()
                .map(ids -> revisions.get(ids.get(ids.size() - 1)))
                .sorted(Comparator.comparing(PoiRevision::createdAt))
                .toList();
    }

    @Override
    public synchronized Optional<PoiRevision> latestRevision(String poiId) {
        List<String> ids = revisionsByPoi.get(poiId);
        return ids == null ? Optional.empty() : Optional.of(revisions.get(ids.get(ids.size() - 1)));
    }

    @Override
    public synchronized PoiRevision createRevision(RevisionDraft draft) {
        String poiId = draft.poiId() == null ? "poi-" + UUID.randomUUID() : draft.poiId();
        if (draft.purpose() != RevisionPurpose.NEW && !revisionsByPoi.containsKey(poiId)) {
            throw new IllegalArgumentException("POI 不存在：" + poiId);
        }
        List<String> history = revisionsByPoi.computeIfAbsent(poiId, key -> new ArrayList<>());
        Instant now = Instant.now();
        PoiRevision revision = new PoiRevision("rev-" + UUID.randomUUID(), poiId, history.size() + 1, draft.purpose(),
                draft.submittedBy(), draft.category(), draft.name(), draft.description(), draft.latitude(), draft.longitude(),
                draft.accuracyMeters(), draft.coordinateSystem(), draft.locatedAt() == null ? now : draft.locatedAt(),
                draft.photoId(), now);
        revisions.put(revision.id(), revision);
        history.add(revision.id());
        return revision;
    }

    @Override
    public synchronized void setReviewSummary(String poiId, String revisionId, ReviewSummary summary) {
        summaries.put(poiId, new StoredSummary(poiId, revisionId, summary, Instant.now()));
    }

    public synchronized List<PoiRevision> history(String poiId) {
        return revisionsByPoi.getOrDefault(poiId, List.of()).stream().map(revisions::get).toList();
    }

    public synchronized Optional<StoredSummary> summary(String poiId) {
        return Optional.ofNullable(summaries.get(poiId));
    }

    public synchronized List<StoredSummary> summaries() {
        return List.copyOf(summaries.values());
    }
}
