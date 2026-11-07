package edu.sadp.checkcollect.poi;

import java.util.List;

/** 分页结果，参数沿用表二约定的 page/size。 */
public record PoiPage(List<PoiSummary> items, int page, int size, long total) {}
