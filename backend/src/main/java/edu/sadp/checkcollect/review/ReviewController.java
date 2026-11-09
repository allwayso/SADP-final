package edu.sadp.checkcollect.review;

import edu.sadp.checkcollect.review.ReviewViews.AssignRequest;
import edu.sadp.checkcollect.review.ReviewViews.CorrectionRequest;
import edu.sadp.checkcollect.review.ReviewViews.DisputeRequest;
import edu.sadp.checkcollect.review.ReviewViews.DisputeView;
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
import java.util.Set;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * M3 核验与异议处理的 HTTP 接口，与 {@link ReviewService} 的进程内方法一一对应。
 *
 * <p>控制器只做参数约束（分页范围、状态筛选解析）和委托；身份、角色与业务规则由服务层校验，
 * 因此其他模块在进程内调用服务时同样受到保护。错误以 {@code ApiErrors} 约定的
 * {@code {"message": ...}} 返回，状态码由服务层给出。
 */
@RestController
@RequestMapping("/api")
public class ReviewController {
    private static final int MAX_PAGE_SIZE = 100;

    private final ReviewService reviews;

    public ReviewController(ReviewService reviews) {
        this.reviews = reviews;
    }

    // ---- 核验 ----

    /** 待检列表：只返回当前核验者可以核验的最新版本。 */
    @GetMapping("/reviews/pending")
    public Page<PendingReview> pending(@RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "20") int size) {
        return reviews.pending(page(page), size(size));
    }

    /** 核验指定版本：{@code PASSED} 通过，{@code ISSUE} 建立错误单并点发采集者。 */
    @PostMapping("/reviews")
    public ReviewRecordView review(@RequestBody(required = false) ReviewRequest request) {
        return reviews.review(request);
    }

    /** 某个 POI 的核验记录与错误单，供地图详情和采集者自查。 */
    @GetMapping("/reviews/pois/{poiId}")
    public ReviewHistory history(@PathVariable String poiId) {
        return reviews.history(poiId);
    }

    // ---- 错误单 ----

    /**
     * 错误单列表。默认只看自己参与的单据；管理员可用 {@code mine=false} 查看全部。
     *
     * @param status 可选状态筛选，如 {@code status=OPEN,PENDING_RECHECK}
     */
    @GetMapping("/issues")
    public Page<IssueView> issues(@RequestParam(required = false) Set<IssueStatus> status,
                                  @RequestParam(defaultValue = "true") boolean mine,
                                  @RequestParam(defaultValue = "0") int page,
                                  @RequestParam(defaultValue = "20") int size) {
        return reviews.issues(status, mine, page(page), size(size));
    }

    @GetMapping("/issues/{id}")
    public IssueDetail issue(@PathVariable UUID id) {
        return reviews.issue(id);
    }

    /** 当事双方或被分配的第三方补充说明，不改变状态。 */
    @PostMapping("/issues/{id}/notes")
    public IssueDetail note(@PathVariable UUID id, @RequestBody(required = false) IssueActionRequest request) {
        return reviews.addNote(id, request);
    }

    /** 采集者重新采集或更新（整改），由 M3 协调新版本与状态。 */
    @PostMapping(value="/issues/{id}/revisions", consumes="application/json")
    public IssueDetail correct(@PathVariable UUID id, @RequestBody(required = false) CorrectionRequest request) {
        return reviews.correct(id, request);
    }

    /** 采集者驳回错误标识，必须说明理由。 */
    @PostMapping("/issues/{id}/rejections")
    public IssueDetail reject(@PathVariable UUID id, @RequestBody(required = false) IssueActionRequest request) {
        return reviews.reject(id, request);
    }

    /** 采集者经沟通后撤回驳回，回到待处理。 */
    @PostMapping("/issues/{id}/resumptions")
    public IssueDetail resume(@PathVariable UUID id, @RequestBody(required = false) IssueActionRequest request) {
        return reviews.resume(id, request);
    }

    /** 核验者确认整改通过，错误单已解决。 */
    @PostMapping("/issues/{id}/confirmations")
    public IssueDetail confirm(@PathVariable UUID id, @RequestBody(required = false) IssueActionRequest request) {
        return reviews.confirm(id, request);
    }

    /** 核验者复核未通过，退回待处理并说明原因。 */
    @PostMapping("/issues/{id}/change-requests")
    public IssueDetail requestChanges(@PathVariable UUID id, @RequestBody(required = false) IssueActionRequest request) {
        return reviews.requestChanges(id, request);
    }

    /** 核验者撤回错误标识并关闭错误单。 */
    @PostMapping("/issues/{id}/withdrawals")
    public IssueDetail withdraw(@PathVariable UUID id, @RequestBody(required = false) IssueActionRequest request) {
        return reviews.withdraw(id, request);
    }

    // ---- 异议与第三方现场任务 ----

    /** 提交异议：当事一方在沟通无果后发起，系统分配非当事人的第三方工作人员。 */
    @PostMapping("/disputes")
    public DisputeView submitDispute(@RequestBody(required = false) DisputeRequest request) {
        return reviews.submitDispute(request);
    }

    @GetMapping("/disputes")
    public Page<DisputeView> disputes(@RequestParam(defaultValue = "true") boolean mine,
                                      @RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "20") int size) {
        return reviews.disputes(mine, page(page), size(size));
    }

    /**
     * 现场任务列表。第三方工作人员默认只看分配给自己的任务；
     * 管理员用 {@code mine=false} 查看全部（含待分配）。
     */
    @GetMapping("/field-tasks")
    public Page<TaskView> tasks(@RequestParam(required = false) Set<FieldTaskStatus> status,
                                @RequestParam(defaultValue = "true") boolean mine,
                                @RequestParam(defaultValue = "0") int page,
                                @RequestParam(defaultValue = "20") int size) {
        return reviews.tasks(status, mine, page(page), size(size));
    }

    @GetMapping("/field-tasks/{id}")
    public TaskView task(@PathVariable UUID id) {
        return reviews.task(id);
    }

    /** 管理员分配或改派现场任务处理人；未指定人员时按当前负载自动选择。 */
    @PatchMapping("/field-tasks/{id}")
    public TaskView assign(@PathVariable UUID id, @RequestBody(required = false) AssignRequest request) {
        return reviews.assign(id, request);
    }

    /** 第三方工作人员提交现场采集版本与最终裁定，裁定后双方不可再修改。 */
    @PostMapping(value="/field-tasks/{id}/result", consumes="application/json")
    public TaskView submitResult(@PathVariable UUID id, @RequestBody(required = false) FieldResultRequest request) {
        return reviews.submitFieldResult(id, request);
    }

    @PostMapping(value="/issues/{id}/revisions", consumes="multipart/form-data")
    public IssueDetail correctFiles(@PathVariable UUID id,
            @org.springframework.web.bind.annotation.RequestPart("request") CorrectionRequest request,
            @org.springframework.web.bind.annotation.RequestPart("photos") java.util.List<org.springframework.web.multipart.MultipartFile> photos) {
        if (request.collection()==null) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,"缺少采集内容");
        return reviews.correct(id,new CorrectionRequest(request.revisionId(),request.collection().withPhotos(photos),request.note()));
    }

    @PostMapping(value="/field-tasks/{id}/result", consumes="multipart/form-data")
    public TaskView resultFiles(@PathVariable UUID id,
            @org.springframework.web.bind.annotation.RequestPart("request") FieldResultRequest request,
            @org.springframework.web.bind.annotation.RequestPart("photos") java.util.List<org.springframework.web.multipart.MultipartFile> photos) {
        if (request.collection()==null) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,"缺少现场采集内容");
        return reviews.submitFieldResult(id,new FieldResultRequest(request.verdict(),request.reason(),request.collection().withPhotos(photos)));
    }

    /** 分页参数做范围约束，避免负数或超大页长直接进入 SQL。 */
    private static int page(int page) {
        if (page < 0) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "页码不能为负数");
        return page;
    }

    private static int size(int size) {
        if (size < 1) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "每页条数必须大于0");
        return Math.min(size, MAX_PAGE_SIZE);
    }
}
