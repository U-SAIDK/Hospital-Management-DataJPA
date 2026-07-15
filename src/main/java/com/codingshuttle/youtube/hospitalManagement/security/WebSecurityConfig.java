package com.codingshuttle.youtube.hospitalManagement.security;

import com.codingshuttle.youtube.hospitalManagement.entity.type.PermissionType;
import com.codingshuttle.youtube.hospitalManagement.entity.type.RoleType;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.ExceptionHandlingConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.io.IOException;

import static com.codingshuttle.youtube.hospitalManagement.entity.type.PermissionType.*;
import static com.codingshuttle.youtube.hospitalManagement.entity.type.RoleType.*;

@Configuration
@RequiredArgsConstructor
@Slf4j
@EnableMethodSecurity
public class WebSecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final OAuth2SuccessHandler oAuth2SuccessHandler;
    private final HandlerExceptionResolver handlerExceptionResolver;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity httpSecurity) throws Exception {
        httpSecurity
                // CSRF protection defends against forged requests riding on an authenticated
                // browser SESSION COOKIE. There is no session cookie here (JWT in an Authorization
                // header isn't automatically attached by the browser), so there's nothing for CSRF to forge.
                .csrf(csrfConfig -> csrfConfig.disable())
                // JWT carries all identity info on every request, so the server keeps no session
                // state between requests - required for the token-based model, and necessary for this to scale horizontally.
                .sessionManagement(sessionConfig ->
                        sessionConfig.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Signup/login must be reachable without a token (chicken-and-egg), and
                        // /public/** is the anonymous read-only hospital directory (see HospitalController).
                        .requestMatchers("/public/**", "/auth/**").permitAll()
                        // Deleting under /admin/** needs a finer-grained authority check than plain
                        // ROLE_ADMIN - an ADMIN role always has these authorities (see RolePermissionMapping),
                        // but expressing it as authorities here documents the intent at the URL layer too.
                        .requestMatchers(HttpMethod.DELETE, "/admin/**")
                            .hasAnyAuthority(APPOINTMENT_DELETE.name(),
                                USER_MANAGE.name())
                        // Non-DELETE /admin/** endpoints just need the coarse ROLE_ADMIN authority
                        // (added alongside fine-grained permissions in User.getAuthorities()).
                        .requestMatchers("/admin/**").hasRole(ADMIN.name())
                        .requestMatchers("/doctors/**").hasAnyRole(DOCTOR.name(), ADMIN.name())
                        // Everything else just needs to be logged in; per-endpoint business rules
                        // (e.g. self-service ownership checks) live as @PreAuthorize/@Secured on service methods.
                        .anyRequest().authenticated()
                )
                // Must run BEFORE UsernamePasswordAuthenticationFilter so a valid JWT populates
                // SecurityContextHolder ahead of any form-login-style authentication attempt -
                // this is what makes stateless, header-based auth take effect on every request.
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .oauth2Login(oAuth2 -> oAuth2
                        // Same reasoning as JwtAuthFilter's catch block: this runs inside Spring
                        // Security's filter chain, so failures must be handed to HandlerExceptionResolver
                        // rather than written ad hoc, to land on the same GlobalExceptionHandler error shape.
                        .failureHandler((request, response, exception) -> {
                            log.error("OAuth2 error: {}", exception.getMessage());
                            handlerExceptionResolver.resolveException(request, response, null, exception);
                        })
                        // Converges OAuth2 login onto the same JWT contract as normal login (see OAuth2SuccessHandler).
                        .successHandler(oAuth2SuccessHandler)
                )
                // 403s (authenticated but insufficient authority) also need to funnel into
                // GlobalExceptionHandler for a consistent ApiError body instead of the container's default response.
                .exceptionHandling(exceptionHandlingConfigurer ->
                        exceptionHandlingConfigurer.accessDeniedHandler((request, response, accessDeniedException) -> {
                            handlerExceptionResolver.resolveException(request, response, null, accessDeniedException);
                        }));

        // Left commented out deliberately - a historical artifact from before JWT/OAuth2 were
        // wired in, when the app used Spring Security's default form-login page. Kept as a
        // reference point for "what this used to look like," not dead code to delete.
//                .formLogin();
        return httpSecurity.build();
    }

}
