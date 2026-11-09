package edu.sadp.checkcollect.poi;

import java.util.UUID;

/**
 * 文字识别适配接口。识别结果只作为分类建议，必须由采集者确认后才写入业务数据；
 * OCR 不可用时采集者仍可人工填写分类与描述。
 */
public interface OcrAdapter {
    OcrSuggestion recognizeText(UUID photoId, byte[] image, String contentType);

    String provider();

    record OcrSuggestion(String text, PoiCategory suggestedCategory, boolean simulated) {}
}
