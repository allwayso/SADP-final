package edu.sadp.checkcollect.review.adapter;

import edu.sadp.checkcollect.poi.*;
import edu.sadp.checkcollect.review.port.*;
import java.time.Instant;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.review.poi-stub", havingValue = "false", matchIfMissing = true)
public class PoiRevisionAdapter implements PoiRevisionPort {
  private final PoiService service;

  public PoiRevisionAdapter(PoiService service) {
    this.service = service;
  }

  public void lockPoi(String id) {
    service.lock(UUID.fromString(id));
  }

  public Optional<PoiRevision> getRevision(String id) {
    try {
      return Optional.of(view(service.getRevision(UUID.fromString(id))));
    } catch (org.springframework.web.server.ResponseStatusException e) {
      if (e.getStatusCode().value() == 404) return Optional.empty();
      throw e;
    }
  }

  public List<PoiRevision> latestRevisions() {
    return service.currentRevisions().stream().map(this::view).toList();
  }

  public Optional<PoiRevision> latestRevision(String id) {
    return Optional.of(
        view(service.getRevision(service.detail(UUID.fromString(id)).poi().currentRevisionId())));
  }

  public PoiRevision createRevision(RevisionDraft draft) {
    SubmitCommand command =
        new SubmitCommand(
            PoiCategory.parse(draft.category()),
            draft.name(),
            draft.description(),
            draft.longitude(),
            draft.latitude(),
            draft.coordinateSystem(),
            draft.locatedAt(),
            draft.accuracyMeters(),
            draft.photos());
    RevisionRef result =
        draft.purpose() == RevisionPurpose.FIELD_CHECK
            ? service.createFieldCheckRevision(
                UUID.fromString(draft.poiId()), command, UUID.fromString(draft.fieldTaskId()))
            : service.submitReviewedCorrection(UUID.fromString(draft.poiId()), command);
    return view(service.getRevision(result.revisionId()));
  }

  public void setReviewSummary(
      String poiId, String revisionId, edu.sadp.checkcollect.review.port.ReviewSummary summary) {
    boolean active =
        Set.of(
                ReviewProgress.ISSUE_OPEN,
                ReviewProgress.PENDING_RECHECK,
                ReviewProgress.DISPUTED,
                ReviewProgress.IN_ARBITRATION)
            .contains(summary.progress());
    UUID poi = UUID.fromString(poiId), revision = UUID.fromString(revisionId);
    service.setWorkflow(poi, active ? UUID.fromString(summary.issueId()) : null, revision);
    service.setReviewSummary(
        poi,
        revision,
        new edu.sadp.checkcollect.poi.ReviewSummary(
            summary.progress().name(),
            summary.progress().name(),
            active,
            summary.note(),
            Instant.now()));
  }

  private PoiRevision view(RevisionView v) {
    return new PoiRevision(
        v.id().toString(),
        v.poiId().toString(),
        v.revisionNo(),
        RevisionPurpose.valueOf(v.purpose().name()),
        v.submittedBy(),
        v.category().name(),
        v.name(),
        v.description(),
        v.lat(),
        v.lon(),
        v.accuracyMeters(),
        v.crs(),
        v.locatedAt(),
        v.photos().isEmpty() ? null : v.photos().get(0).id().toString(),
        v.submittedAt(),
        v.photos().stream().map(photo -> photo.id().toString()).toList());
  }
}
