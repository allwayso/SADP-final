package edu.sadp.checkcollect.identity;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;
@Component
public class MiniWechatClient {
 final String appId; private final String secret, version;
 private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
 private final JsonMapper json=JsonMapper.builder().build();
 private String token; private Instant expires=Instant.EPOCH;
 public MiniWechatClient(@Value("${WECHAT_MINI_APP_ID:}") String appId,@Value("${WECHAT_MINI_APP_SECRET:}") String secret,@Value("${WECHAT_MINI_ENV_VERSION:trial}") String version){this.appId=appId;this.secret=secret;this.version=version;}
 public boolean configured(){return !appId.isBlank()&&!secret.isBlank();}
 private String enc(String s){return URLEncoder.encode(s,StandardCharsets.UTF_8);}
 private ResponseStatusException fail(String s){return new ResponseStatusException(HttpStatus.BAD_GATEWAY,s);}
 private byte[] send(HttpRequest request){try{var r=http.send(request,HttpResponse.BodyHandlers.ofByteArray());if(r.statusCode()!=200)throw fail("微信服务暂不可用");return r.body();}catch(InterruptedException e){Thread.currentThread().interrupt();throw fail("微信请求已中断");}catch(java.io.IOException e){throw fail("无法连接微信服务");}}
 private tools.jackson.databind.JsonNode get(String url){return json.readTree(send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).GET().build()));}
 private synchronized String token(){if(token!=null&&Instant.now().isBefore(expires))return token;var d=get("https://api.weixin.qq.com/cgi-bin/token?grant_type=client_credential&appid="+enc(appId)+"&secret="+enc(secret));if(!d.hasNonNull("access_token"))throw fail("微信凭据或服务器 IP 配置未通过");token=d.path("access_token").asText();expires=Instant.now().plusSeconds(Math.max(0,d.path("expires_in").asInt(7200)-120));return token;}
 public byte[] qr(String ticket){if(!configured())throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"小程序未配置");var request=HttpRequest.newBuilder(URI.create("https://api.weixin.qq.com/wxa/getwxacodeunlimit?access_token="+enc(token()))).timeout(Duration.ofSeconds(20)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("scene",ticket,"page","pages/confirm/confirm","check_path",false,"env_version",version,"width",280)))).build();byte[] b=send(request);if(b.length>=3&&((b[0]==(byte)0x89&&b[1]==0x50)||(b[0]==(byte)0xff&&b[1]==(byte)0xd8)))return b;throw fail("微信未生成小程序码（"+error(b)+"），请检查主体资格、版本上传和接口权限");}
 /** 提取微信错误码与说明，便于在接口里保留真实原因。 */
 private String error(byte[] body){try{var node=json.readTree(body);String code=node.path("errcode").asText("");String message=node.path("errmsg").asText("");return (code+" "+message).trim();}catch(RuntimeException ignored){return "响应不是图片";}}
 public String openId(String code){var d=get("https://api.weixin.qq.com/sns/jscode2session?appid="+enc(appId)+"&secret="+enc(secret)+"&js_code="+enc(code)+"&grant_type=authorization_code");String id=d.path("openid").asText("");if(id.isBlank()||d.path("errcode").asInt(0)!=0)throw fail("微信登录凭证无效，请重新扫码");return id;}
}
