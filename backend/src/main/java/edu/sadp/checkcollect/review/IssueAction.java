package edu.sadp.checkcollect.review;

/** 错误单处理历史中的动作，也用于告诉前端当前用户可以执行哪些操作。 */
public enum IssueAction {
    /** 核验者建立错误单 */
    CREATED,
    /** 当事双方补充说明，状态不变 */
    NOTE,
    /** 采集者重新采集或更新，形成新版本 */
    CORRECTED,
    /** 采集者驳回错误标识 */
    REJECTED,
    /** 采集者经沟通后撤回驳回，回到待处理 */
    RESUMED,
    /** 核验者确认整改通过 */
    CONFIRMED,
    /** 核验者复核未通过，退回待处理 */
    CHANGES_REQUESTED,
    /** 核验者撤回错误标识并关闭错误单 */
    WITHDRAWN,
    /** 当事一方提交异议 */
    DISPUTED,
    /** 现场任务分配或改派第三方工作人员 */
    ASSIGNED,
    /** 第三方工作人员提交现场采集版本 */
    FIELD_COLLECTED,
    /** 第三方工作人员提交最终裁定 */
    DECIDED
}
