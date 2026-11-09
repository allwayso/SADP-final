package edu.sadp.checkcollect.review;

import edu.sadp.checkcollect.identity.IdentityService;
import edu.sadp.checkcollect.identity.Role;
import edu.sadp.checkcollect.identity.UserAccount;
import edu.sadp.checkcollect.identity.UserSummary;
import edu.sadp.checkcollect.review.ReviewModels.Dispute;
import edu.sadp.checkcollect.review.ReviewModels.FieldTask;
import edu.sadp.checkcollect.review.ReviewModels.Outcome;
import edu.sadp.checkcollect.review.ReviewModels.ReviewIssue;
import edu.sadp.checkcollect.review.ReviewViews.AssignRequest;
import edu.sadp.checkcollect.review.ReviewViews.CollectionInput;
import edu.sadp.checkcollect.review.ReviewViews.CorrectionRequest;
import edu.sadp.checkcollect.review.ReviewViews.DisputeRequest;
import edu.sadp.checkcollect.review.ReviewViews.DisputeView;
import edu.sadp.checkcollect.review.ReviewViews.EventView;
import edu.sadp.checkcollect.review.ReviewViews.FieldResultRequest;
import edu.sadp.checkcollect.review.ReviewViews.IssueActionRequest;
import edu.sadp.checkcollect.review.ReviewViews.IssueDetail;
import edu.sadp.checkcollect.review.ReviewViews.IssueView;
import edu.sadp.checkcollect.review.ReviewViews.Page;
import edu.sadp.checkcollect.review.ReviewViews.PendingReview;
import edu.sadp.checkcollect.review.ReviewViews.ReviewHistory;
import edu.sadp.checkcollect.review.ReviewViews.ReviewRecordView;
import edu.sadp.checkcollect.review.ReviewViews.ReviewRequest;
import edu.sadp.checkcollect.review.ReviewViews.TaskView;
import edu.sadp.checkcollect.review.ReviewViews.UserRef;
import edu.sadp.checkcollect.review.port.NotificationPort;
import edu.sadp.checkcollect.review.port.NotificationReference;
import edu.sadp.checkcollect.review.port.NotificationTarget;
import edu.sadp.checkcollect.review.port.PoiRevision;
import edu.sadp.checkcollect.review.port.PoiRevisionPort;
import edu.sadp.checkcollect.review.port.ReviewProgress;
import edu.sadp.checkcollect.review.port.ReviewSummary;
import edu.sadp.checkcollect.review.port.RevisionDraft;
import edu.sadp.checkcollect.review.port.RevisionPurpose;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

/**
 * M3 核验与异议处理的业务服务，负责核验结论、错误单状态机、异议提交、第三方派单与最终裁定。
 *
 * <p>状态变更、M3 记录与 M2 核验摘要在同一事务内完成；通知在事务提交后发出，
 * 投递失败只记日志，不撤销已提交的核验结果。
 */
@Service
public class ReviewService {
    private static final Logger log = LoggerFactory.getLogger(ReviewService.class);
    private static final int TEXT_LIMIT = 2000;
    private static final Set<String> COORDINATE_SYSTEMS = Set.of("WGS84", "GCJ02", "BD09");

    private final ReviewRepository repository;
    private final IdentityService identity;
    private final PoiRevisionPort pois;
    private final NotificationPort notifications;

    public ReviewService(ReviewRepository repository, IdentityService identity, PoiRevisionPort pois, NotificationPort notifications) {
        this.repository = repository;
        this.identity = identity;
        this.pois = pois;
        this.notifications = notifications;
    }

    // =====================================================================
    // 核验
    // =====================================================================

    /** 待检列表：每个 POI 的最新版本中，尚未核验、没有未关闭错误单、且不是本人提交的记录。 */
    public Page<PendingReview> pending(int page, int size) {
        UserAccount reviewer = identity.requireRole(Role.REVIEWER);
        Names names = new Names(identity);
        List<PoiRevision> latest = pois.latestRevisions();
        Set<String> reviewed = repository.reviewedRevisionIds(latest.stream().map(PoiRevision::id).toList());
        Set<String> busy = repository.poisWithActiveIssue(latest.stream().map(PoiRevision::poiId).toList());
        List<PendingReview> pending = latest.stream()
                .filter(revision -> !reviewed.contains(revision.id()))
                .filter(revision -> !busy.contains(revision.poiId()))
                .filter(revision -> revision.purpose() != RevisionPurpose.FIELD_CHECK)
                .filter(revision -> !reviewer.id().equals(revision.submittedBy()))
                .sorted(Comparator.comparing(PoiRevision::createdAt))
                .map(revision -> new PendingReview(revision, names.ref(revision.submittedBy())))
                .toList();
        int from = Math.min(page * size, pending.size());
        return new Page<>(pending.subList(from, Math.min(from + size, pending.size())), page, size);
    }

    /** 核验指定版本：通过，或建立错误单并点发原采集者。 */
    @Transactional
    public ReviewRecordView review(ReviewRequest request) {
        UserAccount reviewer = identity.requireRole(Role.REVIEWER);
        if (request == null || blank(request.revisionId()) || request.outcome() == null) {
            throw bad("请指定核验的采集版本和结论");
        }
        PoiRevision revision = pois.getRevision(request.revisionId()).orElseThrow(() -> notFound("采集版本不存在"));
        PoiRevision latest = pois.latestRevision(revision.poiId()).orElse(revision);
        if (!latest.id().equals(revision.id())) throw conflict("该 POI 已有更新的采集版本，请核验最新版本");
        if (revision.purpose() == RevisionPurpose.FIELD_CHECK) throw conflict("第三方现场版本为最终结果，不再进入普通核验");
        if (reviewer.id().equals(revision.submittedBy())) throw forbidden("不能核验本人提交的数据");
        if (repository.findRecordByRevision(revision.id()).isPresent()) throw conflict("该版本已核验");
        if (repository.findActiveIssueForPoi(revision.poiId()).isPresent()) throw conflict("该 POI 已有未关闭的错误单，请在错误单中继续处理");

        UUID recordId = UUID.randomUUID();
        try {
            if (request.outcome() == Outcome.PASSED) {
                repository.insertRecord(recordId, revision.poiId(), revision.id(), reviewer.id(), Outcome.PASSED, null);
                pois.setReviewSummary(revision.poiId(), revision.id(), new ReviewSummary(ReviewProgress.PASSED, null, "核验通过"));
            } else {
                String description = requiredText(request.description(), "请填写错误说明");
                UUID issueId = UUID.randomUUID();
                repository.insertIssue(issueId, revision.poiId(), revision.id(), revision.submittedBy(), reviewer.id(), description);
                repository.insertRecord(recordId, revision.poiId(), revision.id(), reviewer.id(), Outcome.ISSUE, issueId);
                repository.insertEvent(issueId, reviewer.id(), IssueAction.CREATED, null, IssueStatus.OPEN, revision.id(), description);
                pois.setReviewSummary(revision.poiId(), revision.id(), summary(IssueStatus.OPEN, issueId));
                afterCommit(ReviewNotifications.ISSUE_REPORTED, NotificationTarget.user(revision.submittedBy()), NotificationReference.issue(issueId),
                        "你采集的「" + label(revision) + "」被标识为错误：" + abbreviate(description));
            }
        } catch (DataIntegrityViolationException error) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该版本已被其他核验者处理", error);
        }
        return repository.findRecordByRevision(revision.id()).map(record -> new ReviewRecordView(record.id(), record.poiId(),
                record.revisionId(), new Names(identity).ref(record.reviewerId()), record.outcome(), record.issueId(), record.createdAt()))
                .orElseThrow();
    }

    /** 某个 POI 的核验历史与错误单，供地图详情和采集者查看。 */
    public ReviewHistory history(String poiId) {
        identity.currentUser();
        Names names = new Names(identity);
        List<ReviewRecordView> reviews = repository.recordsForPoi(poiId).stream().map(record -> new ReviewRecordView(record.id(),
                record.poiId(), record.revisionId(), names.ref(record.reviewerId()), record.outcome(), record.issueId(),
                record.createdAt())).toList();
        List<IssueView> issues = repository.listIssues(null, null, poiId, 100, 0).stream().map(issue -> view(issue, names)).toList();
        return new ReviewHistory(poiId, reviews, issues);
    }

    // =====================================================================
    // 错误单
    // =====================================================================

    /**
     * 当前用户参与的错误单（采集者、核验者或被分配的第三方）；管理员可查看全部。
     * {@code mine=false} 仅对管理员有效。
     */
    public Page<IssueView> issues(Set<IssueStatus> statuses, boolean mine, int page, int size) {
        UserAccount user = identity.currentUser();
        boolean all = !mine && user.roles().contains(Role.ADMIN);
        Names names = new Names(identity);
        List<IssueView> items = repository.listIssues(all ? null : user.id(), statuses, null, size, page * size).stream()
                .map(issue -> view(issue, names)).toList();
        return new Page<>(items, page, size);
    }

    public IssueDetail issue(UUID issueId) {
        UserAccount user = identity.currentUser();
        ReviewIssue issue = repository.findIssue(issueId).orElseThrow(() -> notFound("错误单不存在"));
        FieldTask task = repository.findTaskByIssue(issueId).orElse(null);
        requireViewer(issue, task, user);
        return detail(issue, task, user);
    }

    /** 当事双方或被分配的第三方补充说明，不改变状态。 */
    @Transactional
    public IssueDetail addNote(UUID issueId, IssueActionRequest request) {
        UserAccount user = identity.currentUser();
        ReviewIssue issue = lockIssue(issueId);
        FieldTask task = repository.findTaskByIssue(issueId).orElse(null);
        boolean assignee = task != null && user.id().equals(task.assigneeId());
        if (!issue.isParty(user.id()) && !assignee) throw forbidden("只有当事双方或处理该异议的第三方可以补充说明");
        if (!issue.status().active()) throw conflict("错误单已结束，如有新问题请另行核验");
        String note = requiredText(request == null ? null : request.note(), "请填写说明内容");
        repository.insertEvent(issueId, user.id(), IssueAction.NOTE, issue.status(), issue.status(), issue.currentRevisionId(), note);
        repository.touchIssue(issueId);
        return detail(repository.findIssue(issueId).orElseThrow(), task, user);
    }

    /** 采集者重新采集或更新：经 M2 生成整改版本，错误单进入待复核。 */
    @Transactional
    public IssueDetail correct(UUID issueId, CorrectionRequest request) {
        UserAccount collector = identity.requireRole(Role.COLLECTOR);
        ReviewIssue issue = lockIssue(issueId);
        requireCollector(issue, collector);
        requireStatus(issue, EnumSet.of(IssueStatus.OPEN, IssueStatus.DISPUTED), "当前状态不能提交整改");
        requireCurrentRevision(issue, request == null ? null : request.revisionId());
        CollectionInput input = validCollection(request.collection(), true);
        PoiRevision revision = createRevision(issue.poiId(), RevisionPurpose.CORRECTION, collector.id(), null, input);
        String note = optionalText(request.note());
        repository.updateIssueStatus(issueId, IssueStatus.PENDING_RECHECK, revision.id());
        repository.insertEvent(issueId, collector.id(), IssueAction.CORRECTED, issue.status(), IssueStatus.PENDING_RECHECK, revision.id(), note);
        pois.setReviewSummary(issue.poiId(), revision.id(), summary(IssueStatus.PENDING_RECHECK, issueId));
        afterCommit(ReviewNotifications.ISSUE_CORRECTED, NotificationTarget.user(issue.reviewerId()), NotificationReference.issue(issueId),
                "采集者已提交「" + label(revision) + "」的整改版本，请复核");
        return detailFor(issueId, collector);
    }

    /** 采集者驳回错误标识，必须说明理由；错误单进入有异议，不会直接变成已解决。 */
    @Transactional
    public IssueDetail reject(UUID issueId, IssueActionRequest request) {
        UserAccount collector = identity.requireRole(Role.COLLECTOR);
        ReviewIssue issue = lockIssue(issueId);
        requireCollector(issue, collector);
        requireStatus(issue, EnumSet.of(IssueStatus.OPEN), "只有待处理的错误单可以驳回");
        requireCurrentRevision(issue, request == null ? null : request.revisionId());
        String reason = requiredText(request.note(), "驳回必须说明理由");
        transition(issue, collector, IssueAction.REJECTED, IssueStatus.DISPUTED, reason);
        afterCommit(ReviewNotifications.ISSUE_REJECTED, NotificationTarget.user(issue.reviewerId()), NotificationReference.issue(issueId),
                "采集者驳回了你的错误标识：" + abbreviate(reason) + "。可进入错误单私聊沟通");
        return detailFor(issueId, collector);
    }

    /** 采集者经沟通接受错误标识，撤回驳回，回到待处理后再整改。 */
    @Transactional
    public IssueDetail resume(UUID issueId, IssueActionRequest request) {
        UserAccount collector = identity.requireRole(Role.COLLECTOR);
        ReviewIssue issue = lockIssue(issueId);
        requireCollector(issue, collector);
        requireStatus(issue, EnumSet.of(IssueStatus.DISPUTED), "只有有异议的错误单可以撤回驳回");
        requireCurrentRevision(issue, request == null ? null : request.revisionId());
        transition(issue, collector, IssueAction.RESUMED, IssueStatus.OPEN, optionalText(request.note()));
        afterCommit(ReviewNotifications.ISSUE_RESUMED, NotificationTarget.user(issue.reviewerId()), NotificationReference.issue(issueId),
                "采集者接受了错误标识，将进行整改");
        return detailFor(issueId, collector);
    }

    /** 核验者确认整改通过，错误单已解决。 */
    @Transactional
    public IssueDetail confirm(UUID issueId, IssueActionRequest request) {
        UserAccount reviewer = identity.requireRole(Role.REVIEWER);
        ReviewIssue issue = lockIssue(issueId);
        requireReviewer(issue, reviewer);
        requireStatus(issue, EnumSet.of(IssueStatus.PENDING_RECHECK), "只有待复核的错误单可以确认整改");
        requireCurrentRevision(issue, request == null ? null : request.revisionId());
        transition(issue, reviewer, IssueAction.CONFIRMED, IssueStatus.RESOLVED, optionalText(request.note()));
        afterCommit(ReviewNotifications.ISSUE_RESOLVED, NotificationTarget.user(issue.collectorId()), NotificationReference.issue(issueId),
                "你的整改已通过复核，错误单已解决");
        return detailFor(issueId, reviewer);
    }

    /** 核验者复核未通过，退回待处理，必须说明原因。 */
    @Transactional
    public IssueDetail requestChanges(UUID issueId, IssueActionRequest request) {
        UserAccount reviewer = identity.requireRole(Role.REVIEWER);
        ReviewIssue issue = lockIssue(issueId);
        requireReviewer(issue, reviewer);
        requireStatus(issue, EnumSet.of(IssueStatus.PENDING_RECHECK), "只有待复核的错误单可以退回");
        requireCurrentRevision(issue, request == null ? null : request.revisionId());
        String reason = requiredText(request.note(), "退回整改必须说明原因");
        transition(issue, reviewer, IssueAction.CHANGES_REQUESTED, IssueStatus.OPEN, reason);
        afterCommit(ReviewNotifications.ISSUE_CHANGES_REQUESTED, NotificationTarget.user(issue.collectorId()), NotificationReference.issue(issueId),
                "整改未通过复核：" + abbreviate(reason));
        return detailFor(issueId, reviewer);
    }

    /** 核验者撤回错误标识（例如经私聊认可采集者的驳回），关闭错误单，当前版本即有效版本。 */
    @Transactional
    public IssueDetail withdraw(UUID issueId, IssueActionRequest request) {
        UserAccount reviewer = identity.requireRole(Role.REVIEWER);
        ReviewIssue issue = lockIssue(issueId);
        requireReviewer(issue, reviewer);
        requireStatus(issue, EnumSet.of(IssueStatus.OPEN, IssueStatus.PENDING_RECHECK, IssueStatus.DISPUTED), "当前状态不能撤回错误标识");
        requireCurrentRevision(issue, request == null ? null : request.revisionId());
        String note = optionalText(request.note());
        repository.closeIssueWithFinal(issueId, issue.currentRevisionId());
        repository.insertEvent(issueId, reviewer.id(), IssueAction.WITHDRAWN, issue.status(), IssueStatus.CLOSED, issue.currentRevisionId(), note);
        pois.setReviewSummary(issue.poiId(), issue.currentRevisionId(), new ReviewSummary(ReviewProgress.PASSED, issueId.toString(), "错误标识已撤回"));
        afterCommit(ReviewNotifications.ISSUE_WITHDRAWN, NotificationTarget.user(issue.collectorId()), NotificationReference.issue(issueId),
                "核验者撤回了错误标识，错误单已关闭");
        return detailFor(issueId, reviewer);
    }

    // =====================================================================
    // 异议与第三方现场任务
    // =====================================================================

    /** 驳回并沟通后仍无共识，任一当事人提交异议；系统分配一名非当事人的第三方工作人员。 */
    @Transactional
    public DisputeView submitDispute(DisputeRequest request) {
        UserAccount user = identity.currentUser();
        if (request == null || request.issueId() == null) throw bad("请指定错误单");
        ReviewIssue issue = lockIssue(request.issueId());
        if (!issue.isParty(user.id())) throw forbidden("只有错误单的当事双方可以提交异议");
        UserAccount actor = identity.requireRole(issue.collectorId().equals(user.id()) ? Role.COLLECTOR : Role.REVIEWER);
        requireStatus(issue, EnumSet.of(IssueStatus.DISPUTED), "只有驳回后仍有分歧的错误单可以提交异议");
        requireCurrentRevision(issue, request.revisionId());
        if (repository.findDisputeByIssue(issue.id()).isPresent()) throw conflict("该错误单已提交过异议");
        String reason = requiredText(request.reason(), "请说明无法达成共识的原因");

        UUID disputeId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID assignee = pickFieldWorker(issue, null);
        repository.insertDispute(disputeId, issue.id(), actor.id(), reason);
        repository.insertTask(taskId, disputeId, issue.id(), assignee);
        transition(issue, actor, IssueAction.DISPUTED, IssueStatus.IN_ARBITRATION, reason);
        if (assignee != null) {
            repository.insertEvent(issue.id(), actor.id(), IssueAction.ASSIGNED, IssueStatus.IN_ARBITRATION, IssueStatus.IN_ARBITRATION,
                    issue.currentRevisionId(), "系统分配第三方：" + new Names(identity).name(assignee));
            notifyAssignee(assignee, taskId, issue);
        } else {
            afterCommit(ReviewNotifications.FIELD_TASK_UNASSIGNED, NotificationTarget.role(Role.ADMIN), NotificationReference.fieldTask(taskId),
                    "有异议待现场复核，但暂无可用的第三方工作人员，请在后台分配");
        }
        UUID other = issue.collectorId().equals(actor.id()) ? issue.reviewerId() : issue.collectorId();
        afterCommit(ReviewNotifications.DISPUTE_SUBMITTED, NotificationTarget.user(other), NotificationReference.issue(issue.id()),
                "对方已就错误单提交异议，将由第三方工作人员到现场复核");
        return disputeView(repository.findDispute(disputeId).orElseThrow(), new Names(identity));
    }

    public Page<DisputeView> disputes(boolean mine, int page, int size) {
        UserAccount user = identity.currentUser();
        boolean all = !mine && user.roles().contains(Role.ADMIN);
        Names names = new Names(identity);
        return new Page<>(repository.listDisputes(all ? null : user.id(), size, page * size).stream()
                .map(dispute -> disputeView(dispute, names)).toList(), page, size);
    }

    /** 第三方工作人员查看分配给自己的任务；管理员查看全部（含待分配）。 */
    public Page<TaskView> tasks(Set<FieldTaskStatus> statuses, boolean mine, int page, int size) {
        UserAccount user = identity.currentUser();
        boolean admin = user.roles().contains(Role.ADMIN);
        if (!admin && !user.roles().contains(Role.FIELD_WORKER)) throw forbidden("权限不足");
        boolean all = admin && !mine;
        Names names = new Names(identity);
        return new Page<>(repository.listTasks(all ? null : user.id(), statuses, size, page * size).stream()
                .map(task -> taskView(task, names)).toList(), page, size);
    }

    public TaskView task(UUID taskId) {
        UserAccount user = identity.currentUser();
        FieldTask task = repository.findTask(taskId).orElseThrow(() -> notFound("现场任务不存在"));
        ReviewIssue issue = repository.findIssue(task.issueId()).orElseThrow();
        requireViewer(issue, task, user);
        return taskView(task, new Names(identity));
    }

    /** 管理员为待分配任务指定处理人或改派；未指定时按当前负载自动选择。 */
    @Transactional
    public TaskView assign(UUID taskId, AssignRequest request) {
        UserAccount admin = identity.requireRole(Role.ADMIN);
        FieldTask task = repository.lockTask(taskId).orElseThrow(() -> notFound("现场任务不存在"));
        if (task.status() == FieldTaskStatus.COMPLETED) throw conflict("任务已完成裁定，不能改派");
        ReviewIssue issue = lockIssue(task.issueId());
        UUID requested = request == null ? null : request.assigneeId();
        UUID assignee;
        if (requested != null) {
            if (issue.isParty(requested)) throw conflict("第三方处理人不能是异议当事人");
            boolean eligible = identity.listUsers(Role.FIELD_WORKER).stream().anyMatch(user -> user.id().equals(requested));
            if (!eligible) throw bad("指定用户不是启用的第三方工作人员");
            assignee = requested;
        } else {
            assignee = pickFieldWorker(issue, task.assigneeId());
            if (assignee == null) throw conflict("暂无可用的第三方工作人员");
        }
        if (assignee.equals(task.assigneeId())) throw conflict("任务已分配给该人员");
        repository.assignTask(taskId, assignee);
        repository.insertEvent(issue.id(), admin.id(), IssueAction.ASSIGNED, issue.status(), issue.status(), issue.currentRevisionId(),
                (task.assigneeId() == null ? "分配第三方：" : "改派第三方：") + new Names(identity).name(assignee));
        repository.touchIssue(issue.id());
        notifyAssignee(assignee, taskId, issue);
        return taskView(repository.findTask(taskId).orElseThrow(), new Names(identity));
    }

    /**
     * 第三方工作人员提交现场结果与最终裁定。现场采集版本经 M2 以 {@code FIELD_CHECK} 用途保存；
     * 裁定后异议单与错误单关闭，最终结果不可再由当事双方修改。
     */
    @Transactional
    public TaskView submitFieldResult(UUID taskId, FieldResultRequest request) {
        UserAccount worker = identity.requireRole(Role.FIELD_WORKER);
        FieldTask task = repository.lockTask(taskId).orElseThrow(() -> notFound("现场任务不存在"));
        if (!worker.id().equals(task.assigneeId())) throw forbidden("只有被指定的第三方工作人员可以提交结论");
        if (task.status() != FieldTaskStatus.ASSIGNED) throw conflict("任务已完成裁定，结果不可修改");
        ReviewIssue issue = lockIssue(task.issueId());
        if (issue.isParty(worker.id())) throw forbidden("第三方处理人不能是异议当事人");
        if (issue.status() != IssueStatus.IN_ARBITRATION) throw conflict("错误单不在第三方处理中");
        if (request == null || request.verdict() == null) throw bad("请选择裁定结论");
        String reason = requiredText(request.reason(), "请填写裁定依据");

        String fieldRevisionId = null;
        if (request.verdict() == Verdict.REPLACED || request.collection() != null) {
            CollectionInput input = validCollection(request.collection(), request.verdict() == Verdict.REPLACED);
            PoiRevision fieldRevision = createRevision(issue.poiId(), RevisionPurpose.FIELD_CHECK, worker.id(), taskId.toString(), input);
            fieldRevisionId = fieldRevision.id();
            repository.setFieldRevision(taskId, fieldRevisionId);
            repository.insertEvent(issue.id(), worker.id(), IssueAction.FIELD_COLLECTED, issue.status(), issue.status(), fieldRevisionId, null);
        }
        String finalRevisionId = request.verdict() == Verdict.REPLACED ? fieldRevisionId : issue.currentRevisionId();
        repository.completeTask(taskId, request.verdict(), finalRevisionId, reason);
        repository.markDisputeDecided(task.disputeId());
        repository.closeIssueWithFinal(issue.id(), finalRevisionId);
        repository.insertEvent(issue.id(), worker.id(), IssueAction.DECIDED, issue.status(), IssueStatus.CLOSED, finalRevisionId,
                verdictLabel(request.verdict()) + "：" + reason);
        pois.setReviewSummary(issue.poiId(), finalRevisionId, new ReviewSummary(ReviewProgress.FINAL, issue.id().toString(),
                "第三方裁定：" + verdictLabel(request.verdict())));
        String text = "第三方现场复核已完成，裁定为「" + verdictLabel(request.verdict()) + "」：" + abbreviate(reason);
        afterCommit(ReviewNotifications.DISPUTE_DECIDED, NotificationTarget.user(issue.collectorId()), NotificationReference.issue(issue.id()), text);
        afterCommit(ReviewNotifications.DISPUTE_DECIDED, NotificationTarget.user(issue.reviewerId()), NotificationReference.issue(issue.id()), text);
        return taskView(repository.findTask(taskId).orElseThrow(), new Names(identity));
    }

    // =====================================================================
    // 内部规则
    // =====================================================================

    /** 当前用户在该错误单上可以执行的动作，供前端显示按钮；后端各动作仍独立校验。 */
    Set<IssueAction> allowedActions(ReviewIssue issue, FieldTask task, UserAccount user) {
        Set<IssueAction> actions = EnumSet.noneOf(IssueAction.class);
        boolean collector = issue.collectorId().equals(user.id()) && user.roles().contains(Role.COLLECTOR);
        boolean reviewer = issue.reviewerId().equals(user.id()) && user.roles().contains(Role.REVIEWER);
        boolean assignee = task != null && user.id().equals(task.assigneeId()) && task.status() == FieldTaskStatus.ASSIGNED
                && user.roles().contains(Role.FIELD_WORKER);
        if (issue.status().active() && (issue.isParty(user.id()) || assignee)) actions.add(IssueAction.NOTE);
        switch (issue.status()) {
            case OPEN -> {
                if (collector) actions.addAll(EnumSet.of(IssueAction.CORRECTED, IssueAction.REJECTED));
                if (reviewer) actions.add(IssueAction.WITHDRAWN);
            }
            case PENDING_RECHECK -> {
                if (reviewer) actions.addAll(EnumSet.of(IssueAction.CONFIRMED, IssueAction.CHANGES_REQUESTED, IssueAction.WITHDRAWN));
            }
            case DISPUTED -> {
                if (collector) actions.addAll(EnumSet.of(IssueAction.CORRECTED, IssueAction.RESUMED, IssueAction.DISPUTED));
                if (reviewer) actions.addAll(EnumSet.of(IssueAction.WITHDRAWN, IssueAction.DISPUTED));
            }
            case IN_ARBITRATION -> {
                if (assignee) actions.add(IssueAction.DECIDED);
                if (task != null && task.status() != FieldTaskStatus.COMPLETED && user.roles().contains(Role.ADMIN)) actions.add(IssueAction.ASSIGNED);
            }
            default -> { }
        }
        return actions;
    }

    /** 选取负载最少、且不是当事双方的启用第三方人员；{@code exclude} 用于改派时排除原处理人。 */
    private UUID pickFieldWorker(ReviewIssue issue, UUID exclude) {
        return identity.listUsers(Role.FIELD_WORKER).stream()
                .map(UserSummary::id)
                .filter(id -> !issue.isParty(id) && !id.equals(exclude))
                .min(Comparator.comparingLong(repository::openTaskCount).thenComparing(UUID::toString))
                .orElse(null);
    }

    private void notifyAssignee(UUID assignee, UUID taskId, ReviewIssue issue) {
        afterCommit(ReviewNotifications.FIELD_TASK_ASSIGNED, NotificationTarget.user(assignee), NotificationReference.fieldTask(taskId),
                "你被指派处理一项 POI 异议，请到现场重新采集、核验并提交最终结论");
    }

    private void transition(ReviewIssue issue, UserAccount actor, IssueAction action, IssueStatus to, String note) {
        repository.updateIssueStatus(issue.id(), to, issue.currentRevisionId());
        repository.insertEvent(issue.id(), actor.id(), action, issue.status(), to, issue.currentRevisionId(), note);
        pois.setReviewSummary(issue.poiId(), issue.currentRevisionId(), summary(to, issue.id()));
    }

    private PoiRevision createRevision(String poiId, RevisionPurpose purpose, UUID submittedBy, String taskId, CollectionInput input) {
        try {
            return pois.createRevision(new RevisionDraft(poiId, purpose, submittedBy, taskId, input.category().trim(),
                    trimOrNull(input.name()), trimOrNull(input.description()), input.latitude(), input.longitude(),
                    input.accuracyMeters(), input.coordinateSystem(), input.locatedAt(), input.photoId()));
        } catch (IllegalArgumentException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, error.getMessage(), error);
        }
    }

    /**
     * 校验采集内容：须有照片、分类及设备定位（坐标、定位时间、坐标系），不接受缺少定位的提交。
     * {@code required=false} 时允许为空（第三方认定原数据无误时可不附现场版本）。
     */
    private CollectionInput validCollection(CollectionInput input, boolean required) {
        if (input == null) {
            if (required) throw bad("请提交现场采集内容");
            return null;
        }
        if (blank(input.photoId())) throw bad("请拍摄或选择照片");
        if (blank(input.category()) || input.category().length() > 64) throw bad("请选择 POI 分类");
        if (input.latitude() == null || input.longitude() == null || input.locatedAt() == null) {
            throw bad("未取得设备定位，请授权定位后重试");
        }
        if (input.latitude() < -90 || input.latitude() > 90 || input.longitude() < -180 || input.longitude() > 180) throw bad("定位坐标无效");
        if (input.accuracyMeters() != null && input.accuracyMeters() < 0) throw bad("定位精度无效");
        if (input.coordinateSystem() == null || !COORDINATE_SYSTEMS.contains(input.coordinateSystem())) throw bad("坐标系标识无效");
        if (input.name() != null && input.name().length() > 200) throw bad("名称过长");
        if (input.description() != null && input.description().length() > TEXT_LIMIT) throw bad("描述过长");
        return input;
    }

    private ReviewIssue lockIssue(UUID issueId) {
        return repository.lockIssue(issueId).orElseThrow(() -> notFound("错误单不存在"));
    }

    private void requireViewer(ReviewIssue issue, FieldTask task, UserAccount user) {
        boolean assignee = task != null && user.id().equals(task.assigneeId());
        if (!issue.isParty(user.id()) && !assignee && !user.roles().contains(Role.ADMIN)) throw forbidden("无权查看该错误单");
    }

    private static void requireCollector(ReviewIssue issue, UserAccount user) {
        if (!issue.collectorId().equals(user.id())) throw forbidden("只有原采集者可以处理该错误单");
    }

    private static void requireReviewer(ReviewIssue issue, UserAccount user) {
        if (!issue.reviewerId().equals(user.id())) throw forbidden("只有标识该错误的核验者可以执行此操作");
    }

    private static void requireStatus(ReviewIssue issue, Set<IssueStatus> allowed, String message) {
        if (!allowed.contains(issue.status())) throw conflict(message);
    }

    /** 状态变更必须带所针对的采集版本，防止对已更新的内容作出过期操作。 */
    private static void requireCurrentRevision(ReviewIssue issue, String revisionId) {
        if (blank(revisionId)) throw bad("请指定当前处理的采集版本");
        if (!issue.currentRevisionId().equals(revisionId)) throw conflict("错误单已有更新的版本，请刷新后再操作");
    }

    private static ReviewSummary summary(IssueStatus status, UUID issueId) {
        return switch (status) {
            case OPEN -> new ReviewSummary(ReviewProgress.ISSUE_OPEN, issueId.toString(), "已标识错误，待采集者处理");
            case PENDING_RECHECK -> new ReviewSummary(ReviewProgress.PENDING_RECHECK, issueId.toString(), "已整改，待复核");
            case RESOLVED -> new ReviewSummary(ReviewProgress.PASSED, issueId.toString(), "整改已通过复核");
            case DISPUTED -> new ReviewSummary(ReviewProgress.DISPUTED, issueId.toString(), "采集者驳回，有异议");
            case IN_ARBITRATION -> new ReviewSummary(ReviewProgress.IN_ARBITRATION, issueId.toString(), "第三方现场复核中");
            case CLOSED -> new ReviewSummary(ReviewProgress.PASSED, issueId.toString(), "错误单已关闭");
        };
    }

    /** 在事务提交后发送通知；通知失败只记日志，不撤销业务结果。 */
    private void afterCommit(String type, NotificationTarget target, NotificationReference reference, String text) {
        Runnable send = () -> {
            try {
                notifications.notify(type, target, reference, text);
            } catch (RuntimeException error) {
                log.warn("发送通知失败 type={} reference={}", type, reference, error);
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() { send.run(); }
            });
        } else {
            send.run();
        }
    }

    private IssueDetail detailFor(UUID issueId, UserAccount user) {
        return detail(repository.findIssue(issueId).orElseThrow(), repository.findTaskByIssue(issueId).orElse(null), user);
    }

    private IssueDetail detail(ReviewIssue issue, FieldTask task, UserAccount user) {
        Names names = new Names(identity);
        List<EventView> events = repository.events(issue.id()).stream().map(event -> new EventView(event.action(),
                names.ref(event.actorId()), event.fromStatus(), event.toStatus(), event.revisionId(), event.note(),
                event.createdAt())).toList();
        DisputeView dispute = repository.findDisputeByIssue(issue.id()).map(item -> disputeView(item, names)).orElse(null);
        String viewerRole = issue.collectorId().equals(user.id()) ? "COLLECTOR"
                : issue.reviewerId().equals(user.id()) ? "REVIEWER"
                : task != null && user.id().equals(task.assigneeId()) ? "FIELD_WORKER" : "ADMIN";
        return new IssueDetail(view(issue, names), pois.getRevision(issue.flaggedRevisionId()).orElse(null),
                pois.getRevision(issue.currentRevisionId()).orElse(null),
                issue.finalRevisionId() == null ? null : pois.getRevision(issue.finalRevisionId()).orElse(null),
                events, dispute, task == null ? null : taskView(task, names), viewerRole, allowedActions(issue, task, user));
    }

    private static IssueView view(ReviewIssue issue, Names names) {
        return new IssueView(issue.id(), issue.poiId(), issue.flaggedRevisionId(), issue.currentRevisionId(), issue.finalRevisionId(),
                issue.status(), issue.description(), names.ref(issue.collectorId()), names.ref(issue.reviewerId()),
                issue.createdAt(), issue.updatedAt(), issue.closedAt());
    }

    private static DisputeView disputeView(Dispute dispute, Names names) {
        return new DisputeView(dispute.id(), dispute.issueId(), names.ref(dispute.submittedBy()), dispute.reason(), dispute.status(),
                dispute.createdAt(), dispute.decidedAt());
    }

    private TaskView taskView(FieldTask task, Names names) {
        ReviewIssue issue = repository.findIssue(task.issueId()).orElseThrow();
        String disputeReason = repository.findDispute(task.disputeId()).map(Dispute::reason).orElse(null);
        return new TaskView(task.id(), task.disputeId(), task.issueId(), issue.poiId(), task.status(),
                task.assigneeId() == null ? null : names.ref(task.assigneeId()), task.verdict(), task.fieldRevisionId(),
                task.finalRevisionId(), task.reason(), disputeReason, task.createdAt(), task.assignedAt(), task.decidedAt());
    }

    private static String verdictLabel(Verdict verdict) {
        return verdict == Verdict.REPLACED ? "以现场采集版本替换" : "原数据无误";
    }

    private static String label(PoiRevision revision) {
        return revision.name() == null || revision.name().isBlank() ? revision.category() : revision.name();
    }

    private static String requiredText(String value, String message) {
        if (blank(value)) throw bad(message);
        String text = value.trim();
        if (text.length() > TEXT_LIMIT) throw bad("内容不能超过 " + TEXT_LIMIT + " 个字符");
        return text;
    }

    private static String optionalText(String value) {
        if (blank(value)) return null;
        return requiredText(value, "");
    }

    private static String trimOrNull(String value) {
        return blank(value) ? null : value.trim();
    }

    private static String abbreviate(String text) {
        return text.length() <= 60 ? text : text.substring(0, 60) + "…";
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private static ResponseStatusException forbidden(String message) { return new ResponseStatusException(HttpStatus.FORBIDDEN, message); }
    private static ResponseStatusException notFound(String message) { return new ResponseStatusException(HttpStatus.NOT_FOUND, message); }
    private static ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }

    /**
     * 只通过 M1 公开的 {@code listUsers(role)} 解析显示名，不读取 M1 的数据表。
     * 停用账号不在列表中，显示为「已停用用户」。
     */
    private static final class Names {
        private final Map<UUID, String> names = new HashMap<>();

        Names(IdentityService identity) {
            for (Role role : Role.values()) {
                for (UserSummary user : identity.listUsers(role)) names.putIfAbsent(user.id(), user.displayName());
            }
        }

        String name(UUID id) {
            return names.getOrDefault(id, "已停用用户");
        }

        UserRef ref(UUID id) {
            return id == null ? null : new UserRef(id, name(id));
        }
    }
}
