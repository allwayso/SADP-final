package edu.sadp.checkcollect.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.server.ResponseStatusException;

/** 微信小程序 ticket/配对码的一次性确认与浏览器会话绑定。 */
class MiniWechatControllerTest {
    final MiniWechatClient client = mock(MiniWechatClient.class);
    final IdentityService identity = mock(IdentityService.class);
    final SessionLogin login = mock(SessionLogin.class);
    final MiniWechatController controller = new MiniWechatController(client, identity, login, 300);
    final MockHttpServletRequest browser = new MockHttpServletRequest();

    Map<String, String> create() {
        return create(browser);
    }

    Map<String, String> create(MockHttpServletRequest request) {
        when(client.configured()).thenReturn(true);
        when(client.qr(anyString())).thenReturn(new byte[] { 1 });
        return controller.create(new MiniWechatController.CreateRequest("LOGIN"), request);
    }

    Map<String, String> createBind() {
        when(client.configured()).thenReturn(true);
        when(client.qr(anyString())).thenReturn(new byte[] { 1 });
        return controller.create(new MiniWechatController.CreateRequest("BIND"), browser);
    }

    @Test
    void unconfirmedTicketStaysPendingAndCannotCreateSession() {
        var qr = create();
        var result = controller.consume(new MiniWechatController.ConsumeRequest(qr.get("ticket")),
                new MockHttpServletRequest(), new MockHttpServletResponse());
        assertThat(result.get("status")).isEqualTo("pending");
        verifyNoInteractions(login);
    }

    @Test
    void bindTicketRequiresTheOwningAccount() {
        var owner = new UserAccount(UUID.randomUUID(), "member-a", "A", "hash", true, false,
                Set.of(Role.COLLECTOR), false);
        var other = new UserAccount(UUID.randomUUID(), "member-b", "B", "hash", true, false,
                Set.of(Role.COLLECTOR), false);
        when(identity.currentUser()).thenReturn(owner, other);
        var qr = createBind();
        when(client.openId("code")).thenReturn("verified-id");
        controller.confirm(new MiniWechatController.ConfirmRequest(qr.get("ticket"), null, "code"));

        // 另一个已登录账号不能消费这张绑定请求
        assertThatThrownBy(() -> controller.consume(new MiniWechatController.ConsumeRequest(qr.get("ticket")),
                browser, new MockHttpServletResponse()))
                .isInstanceOf(ResponseStatusException.class);
        verify(identity, never()).bindWechat(any(), anyString(), anyString());
    }

    @Test
    void verifiedLoginConsumesTicketOnce() {
        var qr = create();
        when(client.openId("code")).thenReturn("verified-id");
        var user = new UserAccount(UUID.randomUUID(), "member-a", "A", "hash", true, false,
                Set.of(Role.COLLECTOR), true);
        when(identity.userForWechat(any(), eq("verified-id"))).thenReturn(user);
        controller.confirm(new MiniWechatController.ConfirmRequest(qr.get("ticket"), null, "code"));
        assertThat(controller.consume(new MiniWechatController.ConsumeRequest(qr.get("ticket")), browser,
                new MockHttpServletResponse()).get("status")).isEqualTo("complete");
        verify(login).establish(eq(user.id()), eq(browser), any());
        assertThatThrownBy(() -> controller.consume(new MiniWechatController.ConsumeRequest(qr.get("ticket")),
                browser, new MockHttpServletResponse()))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void refreshingInvalidatesOldCode() {
        var first = create();
        create();
        assertThatThrownBy(() -> controller.confirm(new MiniWechatController.ConfirmRequest(first.get("ticket"), null, "code")))
                .isInstanceOf(ResponseStatusException.class);
        verify(client, never()).openId(anyString());
    }

    @Test
    void pairingCodeConfirmsOnlyItsOwnWaitingRequest() {
        var qr = create();
        assertThat(qr.get("pairing")).matches("[A-F0-9]{12}");

        // 错误的配对码不会消耗微信凭证
        assertThatThrownBy(() -> controller.confirm(new MiniWechatController.ConfirmRequest(null, "invalid-pairing", "code")))
                .isInstanceOf(ResponseStatusException.class);
        verify(client, never()).openId(anyString());

        // 正确配对码等价于 ticket：仍需通过微信换取 openid 才能完成
        when(client.openId("code")).thenReturn("verified-id");
        var user = new UserAccount(UUID.randomUUID(), "member-a", "A", "hash", true, false,
                Set.of(Role.COLLECTOR), true);
        when(identity.userForWechat(any(), eq("verified-id"))).thenReturn(user);
        controller.confirm(new MiniWechatController.ConfirmRequest(null, qr.get("pairing"), "code"));
        assertThat(controller.consume(new MiniWechatController.ConsumeRequest(qr.get("ticket")), browser,
                new MockHttpServletResponse()).get("status")).isEqualTo("complete");
    }

    @Test
    void secondBrowserGetsItsOwnPairingCode() {
        var first = create();
        var second = create(new MockHttpServletRequest());
        assertThat(first.get("pairing")).isNotEqualTo(second.get("pairing"));
        // 新请求不会让同一个浏览器的旧请求失效，但每个配对码只能定位自己的 ticket
        assertThatThrownBy(() -> controller.confirm(new MiniWechatController.ConfirmRequest(null, "invalid-pairing", "code")))
                .isInstanceOf(ResponseStatusException.class);
    }
}
