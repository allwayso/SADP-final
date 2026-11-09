package edu.sadp.checkcollect.review;

/**
 * 错误单状态，见 architecture.md 6.2–6.3：
 * 待处理 → 待复核 → 已解决；驳回后为有异议，可经沟通返回待处理或关闭，也可进入第三方处理中。
 */
public enum IssueStatus {
    /** 待处理：等待采集者重新采集、更新或驳回 */
    OPEN,
    /** 待复核：采集者已整改，等待核验者确认 */
    PENDING_RECHECK,
    /** 已解决：核验者确认整改通过 */
    RESOLVED,
    /** 有异议：采集者驳回了错误标识 */
    DISPUTED,
    /** 第三方处理中：已提交异议并生成现场任务 */
    IN_ARBITRATION,
    /** 已关闭：核验者撤回错误标识，或第三方作出最终裁定 */
    CLOSED;

    public boolean active() {
        return this != RESOLVED && this != CLOSED;
    }
}
