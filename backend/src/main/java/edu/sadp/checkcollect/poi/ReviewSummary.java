package edu.sadp.checkcollect.poi;

import java.time.Instant;

/**
 * M2 侧保存的核验摘要，内容由 M3 决定（setReviewSummary）。
 * state、progress 原样保留，M2 不据此推导核验状态。
 */
public record ReviewSummary(String state, String progress, boolean errorFlag, String text, Instant updatedAt) {}
