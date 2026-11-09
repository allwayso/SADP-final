package edu.sadp.checkcollect.messaging;

/** 通知类型。由业务模块在提交采集、核验、异议等动作时选择，M4 只负责投递。 */
public enum NotificationKind {
    /** 新采集或整改版本进入待核验，发给核验者。 */
    NEW_DATA,
    /** 系统公告，例如因交通管制暂停工作。 */
    ANNOUNCEMENT,
    /** 核验者标识错误，点发原采集者。 */
    ISSUE_RAISED,
    /** 采集者重新采集、更新或驳回，点发核验者。 */
    ISSUE_RESPONSE,
    /** 异议任务，点发第三方工作人员。 */
    DISPUTE_OPENED,
    /** 第三方现场任务，点发指定人员。 */
    FIELD_TASK,
    /** 最终裁定结果，点发当事双方。 */
    FINAL_RESULT
}
