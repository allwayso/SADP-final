package edu.sadp.checkcollect.poi;

/** 采集版本的提交用途。普通采集与整改由采集者发起，现场复核由 M3 校验任务后调用。 */
public enum SubmitPurpose {
    NEW("新采集"),
    CORRECTION("更新或整改"),
    FIELD_CHECK("第三方现场核验");

    private final String label;

    SubmitPurpose(String label) { this.label = label; }

    public String label() { return label; }
}
