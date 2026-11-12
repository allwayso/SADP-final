package edu.sadp.checkcollect.identity;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;

@Configuration
public class SecurityConfig {
    @Bean
    PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }

    @Bean
    SecurityFilterChain security(HttpSecurity http, IdentityRepository users) throws Exception {
        http.csrf(csrf -> csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .ignoringRequestMatchers("/api/auth/wechat/mini/confirm", "/api/auth/wechat/mini/register"))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.GET, "/", "/index.html", "/login", "/password", "/account", "/admin/users", "/poi/**", "/review", "/messages", "/assets/**", "/favicon.ico").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/auth/health", "/api/auth/csrf", "/api/auth/wechat/status", "/api/auth/wechat/start", "/api/auth/wechat/callback").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/auth/wechat/mini/status").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/auth/login", "/api/auth/wechat/mini/create", "/api/auth/wechat/mini/confirm", "/api/auth/wechat/mini/consume",
                        "/api/auth/wechat/mini/register").permitAll()
                .requestMatchers("/error").permitAll()
                .anyRequest().authenticated())
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((request, response, error) -> response.sendError(HttpServletResponse.SC_UNAUTHORIZED))
                .accessDeniedHandler((request, response, error) -> response.sendError(HttpServletResponse.SC_FORBIDDEN)))
            .formLogin(form -> form.disable())
            .httpBasic(basic -> basic.disable());
        http.addFilterAfter(new AccountStatusFilter(users), SecurityContextHolderFilter.class);
        return http.build();
    }
}
