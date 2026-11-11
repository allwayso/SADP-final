package edu.sadp.checkcollect.identity;
import jakarta.servlet.http.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
@RestController
@RequestMapping("/api/auth/wechat/mini")
public class MiniWechatController {
 private final MiniWechatClient client; private final IdentityService identity; private final SessionLogin login;
 private final Map<String,Ticket> tickets=new ConcurrentHashMap<>();
 private record Ticket(String sessionId,UUID userId,Instant expires,String openId){}
 public record CreateRequest(String purpose){}
 public record ConfirmRequest(String ticket,String code){}
 public record ConsumeRequest(String ticket){}
 public MiniWechatController(MiniWechatClient c,IdentityService i,SessionLogin l){client=c;identity=i;login=l;}
 @GetMapping("/status") public Map<String,Boolean> status(){return Map.of("configured",client.configured());}
 @PostMapping("/create") public synchronized Map<String,String> create(@RequestBody CreateRequest body,HttpServletRequest request){
 if(!client.configured())throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"小程序登录尚未配置");
 if(body==null||body.purpose()==null||!Set.of("LOGIN","BIND").contains(body.purpose()))throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
 UUID uid=null;if(body.purpose().equals("BIND")){var user=identity.currentUser();if(user.wechatBound())throw new ResponseStatusException(HttpStatus.CONFLICT,"已绑定微信");uid=user.id();}
 String session=request.getSession(true).getId();tickets.entrySet().removeIf(e->e.getValue().expires().isBefore(Instant.now())||e.getValue().sessionId().equals(session));
 if(tickets.size()>=1000)throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
 String id=UUID.randomUUID().toString().replace("-","");byte[] qr=client.qr(id);tickets.put(id,new Ticket(session,uid,Instant.now().plusSeconds(300),null));return Map.of("ticket",id,"image","data:image/png;base64,"+Base64.getEncoder().encodeToString(qr));}
 @PostMapping("/confirm") public void confirm(@RequestBody ConfirmRequest body){if(body==null||body.code()==null||body.code().isBlank())throw new ResponseStatusException(HttpStatus.BAD_REQUEST);Ticket initial=valid(body.ticket());if(initial.openId()!=null)throw new ResponseStatusException(HttpStatus.CONFLICT);String openId=client.openId(body.code());synchronized(this){Ticket current=valid(body.ticket());if(current!=initial)throw new ResponseStatusException(HttpStatus.CONFLICT);tickets.put(body.ticket(),new Ticket(current.sessionId(),current.userId(),current.expires(),openId));}}
 @PostMapping("/consume") public synchronized Map<String,Object> consume(@RequestBody ConsumeRequest body,HttpServletRequest request,HttpServletResponse response){Ticket t=valid(body.ticket());if(request.getSession(false)==null||!t.sessionId().equals(request.getSession(false).getId()))throw new ResponseStatusException(HttpStatus.FORBIDDEN);if(t.openId()==null)return Map.of("status","pending");tickets.remove(body.ticket());UserAccount account;if(t.userId()!=null){account=identity.currentUser();if(!account.id().equals(t.userId()))throw new ResponseStatusException(HttpStatus.FORBIDDEN);identity.bindWechat(account.id(),client.appId,t.openId());account=identity.currentUser();}else{account=identity.userForWechat(client.appId,t.openId());login.establish(account.id(),request,response);}return Map.of("status","complete","user",account.summary());}
 private Ticket valid(String id){Ticket t=id==null?null:tickets.get(id);if(t==null||!t.expires().isAfter(Instant.now())){if(id!=null)tickets.remove(id);throw new ResponseStatusException(HttpStatus.GONE,"二维码已过期，请重新获取");}return t;}
}
