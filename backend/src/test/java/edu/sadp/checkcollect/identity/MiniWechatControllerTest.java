package edu.sadp.checkcollect.identity;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import org.springframework.web.server.ResponseStatusException;
class MiniWechatControllerTest {
 final MiniWechatClient client=mock(MiniWechatClient.class);
 final IdentityService identity=mock(IdentityService.class);
 final SessionLogin login=mock(SessionLogin.class);
 final MiniWechatController controller=new MiniWechatController(client,identity,login);
 final MockHttpServletRequest browser=new MockHttpServletRequest();
 Map<String,String> create(){when(client.configured()).thenReturn(true);when(client.qr(anyString())).thenReturn(new byte[]{1});return controller.create(new MiniWechatController.CreateRequest("LOGIN"),browser);}
 @Test void otherBrowserCannotConsumeTicket(){var qr=create();assertThatThrownBy(()->controller.consume(new MiniWechatController.ConsumeRequest(qr.get("ticket")),new MockHttpServletRequest(),new MockHttpServletResponse())).isInstanceOf(ResponseStatusException.class);verifyNoInteractions(login);}
 @Test void browserCannotInventConfirmedIdentity(){var qr=create();var result=controller.consume(new MiniWechatController.ConsumeRequest(qr.get("ticket")),browser,new MockHttpServletResponse());assertThat(result.get("status")).isEqualTo("pending");verifyNoInteractions(identity,login);}
 @Test void verifiedLoginConsumesTicketOnce(){var qr=create();when(client.openId("code")).thenReturn("verified-id");var user=new UserAccount(UUID.randomUUID(),"member-a","A","hash",true,false,Set.of(Role.COLLECTOR),true);when(identity.userForWechat(any(),eq("verified-id"))).thenReturn(user);controller.confirm(new MiniWechatController.ConfirmRequest(qr.get("ticket"),"code"));assertThat(controller.consume(new MiniWechatController.ConsumeRequest(qr.get("ticket")),browser,new MockHttpServletResponse()).get("status")).isEqualTo("complete");verify(login).establish(eq(user.id()),eq(browser),any());assertThatThrownBy(()->controller.consume(new MiniWechatController.ConsumeRequest(qr.get("ticket")),browser,new MockHttpServletResponse())).isInstanceOf(ResponseStatusException.class);}
 @Test void refreshingInvalidatesOldCode(){var first=create();create();assertThatThrownBy(()->controller.confirm(new MiniWechatController.ConfirmRequest(first.get("ticket"),"code"))).isInstanceOf(ResponseStatusException.class);verify(client,never()).openId(anyString());}
}
