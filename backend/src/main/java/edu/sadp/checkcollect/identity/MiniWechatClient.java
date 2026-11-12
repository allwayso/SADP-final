package edu.sadp.checkcollect.identity;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

/**
 * 微信小程序身份接口的最小封装：只做 {@code jscode2session}。
 *
 * <p>小程序直接在微信里登录，不再需要服务器生成小程序码，因此这里不包含
 * `getwxacodeunlimit` 等需要在后端持有 access_token 的接口。
 */
@Component
public class MiniWechatClient {
    final String appId;
    private final String secret;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final JsonMapper json = JsonMapper.builder().build();

    public MiniWechatClient(@Value("${WECHAT_MINI_APP_ID:}") String appId,
                            @Value("${WECHAT_MINI_APP_SECRET:}") String secret) {
        this.appId = appId;
        this.secret = secret;
    }

    public boolean configured() {
        return !appId.isBlank() && !secret.isBlank();
    }

    /** 用 wx.login 的一次性 code 换取 openid；code 只能使用一次且很快过期。 */
    public String openId(String code) {
        if (!configured()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "小程序微信登录尚未配置");
        String url = "https://api.weixin.qq.com/sns/jscode2session?appid=" + enc(appId)
                + "&secret=" + enc(secret) + "&js_code=" + enc(code) + "&grant_type=authorization_code";
        var response = get(url);
        String openId = response.path("openid").asText("");
        if (openId.isBlank() || response.path("errcode").asInt(0) != 0) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "微信登录凭证无效或已过期，请在小程序里重试");
        }
        return openId;
    }

    private String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private tools.jackson.databind.JsonNode get(String url) {
        try {
            var request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).GET().build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "微信服务暂不可用");
            }
            return json.readTree(response.body());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "微信请求已中断");
        } catch (java.io.IOException error) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "无法连接微信服务");
        }
    }
}
