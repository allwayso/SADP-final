package edu.sadp.checkcollect.poi;

import java.util.List;

/** POI 详情：当前摘要加快照式的历史版本列表，供 M3 选择核验版本及页面展示。 */
public record PoiDetail(PoiSummary poi, List<RevisionView> revisions) {}
