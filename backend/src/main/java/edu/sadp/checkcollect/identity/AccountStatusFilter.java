package edu.sadp.checkcollect.identity;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class AccountStatusFilter extends OncePerRequestFilter {
    private final IdentityRepository users;
    public AccountStatusFilter(IdentityRepository users) { this.users = users; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof UUID id) {
            UserAccount account = users.find(id).orElse(null);
            if (account == null || !account.enabled()) {
                SecurityContextHolder.clearContext();
                if (request.getSession(false) != null) request.getSession(false).invalidate();
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
                return;
            }
            String path = request.getRequestURI();
            boolean passwordEndpoint = path.equals("/api/me/password") && request.getMethod().equals("POST");
            boolean profileEndpoint = path.equals("/api/me") && request.getMethod().equals("GET");
            if (account.mustChangePassword() && path.startsWith("/api/") && !passwordEndpoint && !profileEndpoint
                    && !path.equals("/api/auth/logout") && !path.equals("/api/auth/csrf")
                    && !path.equals("/api/auth/wechat/mini/logout")
                    && !path.equals("/api/auth/wechat/mini/pairings/confirm")) {
                response.sendError(HttpServletResponse.SC_FORBIDDEN, "请先修改初始密码");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
