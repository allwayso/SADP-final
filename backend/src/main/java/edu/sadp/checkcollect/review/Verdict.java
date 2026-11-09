package edu.sadp.checkcollect.review;

/** 第三方最终裁定。两种结论都明确指定最终有效版本。 */
public enum Verdict {
    /** 原数据无误：以采集者的当前版本为最终版本 */
    ORIGINAL_CORRECT,
    /** 原数据有误：以第三方现场采集的版本替换 */
    REPLACED
}
