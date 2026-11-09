package edu.sadp.checkcollect.review;

/** 第三方现场任务状态。 */
public enum FieldTaskStatus {
    /** 待分配：暂无可用的第三方工作人员 */
    UNASSIGNED,
    /** 已分配，等待现场复核与裁定 */
    ASSIGNED,
    /** 已作出最终裁定 */
    COMPLETED
}
