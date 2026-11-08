package edu.sadp.checkcollect.review.port;

/** 地图上展示的核验进展。 */
public enum ReviewProgress {
    /** 待核验 */
    PENDING_REVIEW,
    /** 核验通过（含整改确认、撤回错误标识） */
    PASSED,
    /** 已标识错误，待采集者处理 */
    ISSUE_OPEN,
    /** 已整改，待核验者复核 */
    PENDING_RECHECK,
    /** 采集者驳回，有异议 */
    DISPUTED,
    /** 已提交异议，第三方处理中 */
    IN_ARBITRATION,
    /** 第三方最终裁定 */
    FINAL
}
