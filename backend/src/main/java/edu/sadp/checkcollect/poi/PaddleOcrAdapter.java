package edu.sadp.checkcollect.poi;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

@Component
@ConditionalOnProperty(name = "app.ocr.provider", havingValue = "paddle")
public class PaddleOcrAdapter implements OcrAdapter {
  private final String endpoint;
  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  private final JsonMapper json = JsonMapper.builder().build();

  public PaddleOcrAdapter(@Value("${PADDLE_OCR_URL:http://127.0.0.1:8000/ocr}") String endpoint) {
    this.endpoint = endpoint;
  }

  public String provider() {
    return "paddle";
  }

  public OcrSuggestion recognizeText(UUID photoId, byte[] image, String contentType) {
    if (!Set.of("image/jpeg", "image/png", "image/webp").contains(contentType))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文字识别请使用 JPEG、PNG 或 WebP 图片");
    if (image.length > 8_000_000)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文字识别图片需小于 8MB");
    try {
      var body = Map.of("image", Base64.getEncoder().encodeToString(image));
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(endpoint))
              .timeout(Duration.ofSeconds(90))
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
              .build();
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200)
        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "OCR 服务暂不可用，可人工填写");
      String text = json.readTree(response.body()).path("text").asText();
      return new OcrSuggestion(text, null, false);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "文字识别已中断");
    } catch (java.io.IOException e) {
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "文字识别连接失败，可人工填写");
    }
  }
}
