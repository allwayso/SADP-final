package edu.sadp.checkcollect.review.port;

/** 采集版本的提交用途，见 architecture.md 第 7 节。 */
public enum RevisionPurpose {
    /** 新采集或无活动错误单时的普通更新，由 M2 直接接收，进入待检。 */
    NEW,
    /** 针对错误单的整改，由 M3 协调创建，进入该错误单的待复核。 */
    CORRECTION,
    /** 第三方工作人员按已分配任务进行的现场采集。 */
    FIELD_CHECK
}
