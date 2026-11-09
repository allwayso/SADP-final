package edu.sadp.checkcollect.review;

/** M3 发给 M4 的通知类型。M4 只负责投递，不据此决定业务状态。 */
public final class ReviewNotifications {
    private ReviewNotifications() {}

    /** 点发采集者：核验者标识了数据质量问题 */
    public static final String ISSUE_REPORTED = "REVIEW_ISSUE_REPORTED";
    /** 点发核验者：采集者已整改，待复核 */
    public static final String ISSUE_CORRECTED = "REVIEW_ISSUE_CORRECTED";
    /** 点发核验者：采集者驳回错误标识 */
    public static final String ISSUE_REJECTED = "REVIEW_ISSUE_REJECTED";
    /** 点发核验者：采集者撤回驳回，回到待处理 */
    public static final String ISSUE_RESUMED = "REVIEW_ISSUE_RESUMED";
    /** 点发采集者：复核未通过，需继续整改 */
    public static final String ISSUE_CHANGES_REQUESTED = "REVIEW_ISSUE_CHANGES_REQUESTED";
    /** 点发采集者：整改通过，错误单已解决 */
    public static final String ISSUE_RESOLVED = "REVIEW_ISSUE_RESOLVED";
    /** 点发采集者：核验者撤回错误标识 */
    public static final String ISSUE_WITHDRAWN = "REVIEW_ISSUE_WITHDRAWN";
    /** 点发对方当事人：另一方提交了异议 */
    public static final String DISPUTE_SUBMITTED = "REVIEW_DISPUTE_SUBMITTED";
    /** 点发第三方工作人员：分配了现场复核任务 */
    public static final String FIELD_TASK_ASSIGNED = "REVIEW_FIELD_TASK_ASSIGNED";
    /** 群发管理员：没有可用的第三方工作人员，任务待分配 */
    public static final String FIELD_TASK_UNASSIGNED = "REVIEW_FIELD_TASK_UNASSIGNED";
    /** 点发争议双方：第三方作出最终裁定 */
    public static final String DISPUTE_DECIDED = "REVIEW_DISPUTE_DECIDED";
}
