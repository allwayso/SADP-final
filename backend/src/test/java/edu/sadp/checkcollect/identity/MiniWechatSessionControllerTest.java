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

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.server.ResponseStatusException;

/** 小程序内微信登录：已绑定直接登录，未绑定可绑定已有账号或注册采集者。 */
class MiniWechatSessionControllerTest {
    final MiniWechatClient client = mock(MiniWechatClient.class);
    final IdentityService identity = mock(IdentityService.class);
    final SessionLogin login = mock(SessionLogin.class);
    final MiniWechatSessionController controller = new MiniWechatSessionController(client, identity, login);
    final MockHttpServletRequest request = new MockHttpServletRequest();
    final MockHttpServletResponse response = new MockHttpServletResponse();

    private UserAccount account(String username, Role role) {
        return new UserAccount(UUID.randomUUID(), username, username, "hash", true, false, Set.of(role), true);
    }

    private void configured() {
        when(client.configured()).thenReturn(true);
    }

    @Test
    void boundWechatLogsIn() {
        configured();
        var user = account("member-c", Role.REVIEWER);
        when(client.openId("code")).thenReturn("openid-1");
        when(identity.findByWechat(any(), eq("openid-1"))).thenReturn(Optional.of(user.summary()));
        when(identity.userForWechat(any(), eq("openid-1"))).thenReturn(user);

        var result = controller.session(new MiniWechatSessionController.SessionRequest("code", null, null, null, null),
                request, response);

        assertThat(result.get("status")).isEqualTo("logged-in");
        verify(login).establish(eq(user.id()), eq(request), eq(response));
    }

    @Test
    void unboundWechatAsksUserToChoose() {
        configured();
        when(client.openId("code")).thenReturn("openid-2");
        when(identity.findByWechat(any(), eq("openid-2"))).thenReturn(Optional.empty());

        var result = controller.session(new MiniWechatSessionController.SessionRequest("code", null, null, null, null),
                request, response);

        assertThat(result.get("status")).isEqualTo("need-binding");
        verifyNoInteractions(login);
    }

    @Test
    void bindAttachesWechatToExistingAccount() {
        configured();
        var user = account("member-b", Role.COLLECTOR);
        when(client.openId("code")).thenReturn("openid-3");
        when(identity.findByWechat(any(), eq("openid-3"))).thenReturn(Optional.empty());
        when(identity.verifyPassword("member-b", "password-123")).thenReturn(user);
        when(identity.currentUser()).thenReturn(user);

        var result = controller.session(new MiniWechatSessionController.SessionRequest(
                "code", "bind", "member-b", "password-123", null), request, response);

        assertThat(result.get("status")).isEqualTo("bound");
        verify(identity).bindWechat(eq(user.id()), any(), eq("openid-3"));
        verify(login).establish(eq(user.id()), eq(request), eq(response));
    }

    @Test
    void registerCreatesCollectorAccount() {
        configured();
        var created = account("wxcollector", Role.COLLECTOR);
        when(client.openId("code")).thenReturn("openid-4");
        when(identity.findByWechat(any(), eq("openid-4"))).thenReturn(Optional.empty());
        when(identity.registerWithWechat(any(), eq("openid-4"), eq("wxcollector"), eq("password-123"), eq("采集者")))
                .thenReturn(created);

        var result = controller.session(new MiniWechatSessionController.SessionRequest(
                "code", "register", "wxcollector", "password-123", "采集者"), request, response);

        assertThat(result.get("status")).isEqualTo("registered");
        verify(login).establish(eq(created.id()), eq(request), eq(response));
    }

    @Test
    void unknownActionIsRejectedBeforeExchangingCode() {
        configured();
        assertThatThrownBy(() -> controller.session(new MiniWechatSessionController.SessionRequest(
                "code", "promote", null, null, null), request, response))
                .isInstanceOf(ResponseStatusException.class);
        verify(client, never()).openId(anyString());
    }

    @Test
    void missingCodeIsRejected() {
        configured();
        assertThatThrownBy(() -> controller.session(new MiniWechatSessionController.SessionRequest(
                null, null, null, null, null), request, response))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void unconfiguredMiniProgramIsReported() {
        when(client.configured()).thenReturn(false);
        assertThatThrownBy(() -> controller.session(new MiniWechatSessionController.SessionRequest(
                "code", null, null, null, null), request, response))
                .isInstanceOf(ResponseStatusException.class);
        verify(identity, never()).findByWechat(any(), anyString());
    }
}
