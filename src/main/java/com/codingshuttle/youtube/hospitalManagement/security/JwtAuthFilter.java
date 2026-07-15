package com.codingshuttle.youtube.hospitalManagement.security;

import com.codingshuttle.youtube.hospitalManagement.entity.User;
import com.codingshuttle.youtube.hospitalManagement.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.io.IOException;

@Component
@Slf4j
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final UserRepository userRepository;
    private final AuthUtil authUtil;

    private final HandlerExceptionResolver handlerExceptionResolver;

    // OncePerRequestFilter guarantees this runs exactly once per request even if the request
    // is forwarded/included internally - important since it mutates the SecurityContext, and
    // running it twice could redundantly re-authenticate or race with an already-set context.
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        try {
            log.info("incoming request: {}", request.getRequestURI());

            final String requestTokenHeader = request.getHeader("Authorization");
            if (requestTokenHeader == null || !requestTokenHeader.startsWith("Bearer")) {
                filterChain.doFilter(request, response);
                return;
            }

            String token = requestTokenHeader.split("Bearer ")[1];
            String username = authUtil.getUsernameFromToken(token);

            // Guards against clobbering an authentication already established earlier in the
            // chain (e.g. by Spring Security's OAuth2 login filter on the same request) - this
            // filter should only fill in the principal when nothing has claimed it yet.
            if (username != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                User user = userRepository.findByUsername(username).orElseThrow();
                // principal = the User entity itself, credentials = null (already proven by the JWT
                // signature, no password re-check needed), authorities computed from user.getAuthorities().
                UsernamePasswordAuthenticationToken usernamePasswordAuthenticationToken
                        = new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
                SecurityContextHolder.getContext().setAuthentication(usernamePasswordAuthenticationToken);
            }
            filterChain.doFilter(request, response);
        } catch (Exception ex) {
            // Filters run outside Spring MVC's DispatcherServlet exception handling, so a thrown
            // exception here would otherwise bypass @RestControllerAdvice entirely and produce a raw
            // container error page. Delegating to HandlerExceptionResolver routes it into the same
            // GlobalExceptionHandler used by controller-thrown exceptions, keeping the error response shape consistent.
            handlerExceptionResolver.resolveException(request, response, null, ex);
        }
    }
}
