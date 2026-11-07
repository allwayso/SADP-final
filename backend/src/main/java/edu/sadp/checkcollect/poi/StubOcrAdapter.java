package edu.sadp.checkcollect.poi;

import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 未配置文字识别平台时的桩实现：不产生文字，也不推荐分类，页面照常允许人工填写。 */
@Component
@ConditionalOnProperty(name = "app.ocr.provider", havingValue = "stub", matchIfMissing = true)
public class StubOcrAdapter implements OcrAdapter {

    @Override
    public OcrSuggestion recognizeText(UUID photoId, byte[] image, String contentType) {
        return new OcrSuggestion("", null, true);
    }

    @Override
    public String provider() { return "stub"; }
}
