package edu.sadp.checkcollect.review.port;

/**
 * 写回 M2 的核验摘要，供地图按进展展示错误标识。
 *
 * @param progress 处理进展
 * @param issueId  关联错误单标识，无错误单时为 {@code null}
 * @param note     面向地图弹窗的简短说明
 */
public record ReviewSummary(ReviewProgress progress, String issueId, String note) {
}
