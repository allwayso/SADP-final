package edu.sadp.checkcollect.poi;

import java.util.UUID;

/** 照片元数据视图。文件本体由 PhotoStorage 保存，数据库只记录标识与元数据。 */
public record PhotoView(UUID id, String contentType, long byteSize, String ocrText) {}
