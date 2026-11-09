package edu.sadp.checkcollect.poi;

import jakarta.servlet.http.HttpServletRequest;
import java.net.http.*;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

/** 固定目标及允许的 API 路径，高德安全密钥仅在服务端附加。 */
@RestController
public class AmapProxyController {
  private final String key, securityCode;
  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  public AmapProxyController(
      @Value("${AMAP_JS_KEY:}") String key,
      @Value("${AMAP_JS_SECURITY_CODE:}") String securityCode) {
    this.key = key;
    this.securityCode = securityCode;
  }

  @GetMapping("/api/_AMapService/**")
  public ResponseEntity<byte[]> proxy(
      HttpServletRequest request, @RequestParam MultiValueMap<String, String> query) {
    String path = request.getRequestURI().substring("/api/_AMapService".length());
    if (!path.matches("/v[34]/[A-Za-z0-9/_-]+"))
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    if (key.isBlank() || securityCode.isBlank())
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "地图尚未配置");
    String callback = query.getFirst("callback");
    if (callback != null && !callback.matches("[A-Za-z_$][A-Za-z0-9_$]*"))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
    MultiValueMap<String, String> params = new LinkedMultiValueMap<>(query);
    params.set("key", key);
    params.set("jscode", securityCode);
    String host =
        path.equals("/v4/map/styles") ? "https://webapi.amap.com" : "https://restapi.amap.com";
    var uri =
        UriComponentsBuilder.fromUriString(host + path)
            .queryParams(params)
            .build()
            .encode()
            .toUri();
    try {
      var response =
          client.send(
              HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15)).GET().build(),
              HttpResponse.BodyHandlers.ofByteArray());
      return ResponseEntity.status(response.statusCode())
          .header(
              "Content-Type",
              callback != null
                  ? "application/javascript;charset=UTF-8"
                  : response.headers().firstValue("Content-Type").orElse("application/json"))
          .header("Cache-Control", "no-store")
          .body(response.body());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "地图请求中断");
    } catch (java.io.IOException e) {
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "地图连接失败");
    }
  }
}
