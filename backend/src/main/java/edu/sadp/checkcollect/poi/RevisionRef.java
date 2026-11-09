package edu.sadp.checkcollect.poi;

import java.util.UUID;

/** 提交采集后的返回值：本次形成的 POI 与版本标识。 */
public record RevisionRef(UUID poiId, UUID revisionId, int revisionNo) {}
