package com.codingshuttle.youtube.hospitalManagement.entity;

import com.codingshuttle.youtube.hospitalManagement.entity.type.AuthProviderType;
import com.codingshuttle.youtube.hospitalManagement.entity.type.RoleType;
import com.codingshuttle.youtube.hospitalManagement.security.RolePermissionMapping;
import jakarta.persistence.*;
import lombok.*;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Entity
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
// Index supports the OAuth2 login path, where a user is looked up by (providerId, providerType)
// instead of by username/password.
@Table(name = "app_user", indexes = {
        @Index(name = "idx_provider_id_provider_type", columnList = "providerId, providerType")
})
// User doubles as the Spring Security principal: JwtAuthFilter puts this very entity into the
// SecurityContext, so there is no separate UserDetails adapter/mapping layer to keep in sync.
public class User implements UserDetails {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JoinColumn(unique = true, nullable = false)
    private String username;
    // Nullable: OAuth2-only accounts never set a local password, only email/password signups do.
    private String password;

    private String providerId;

    @Enumerated(EnumType.STRING)
    private AuthProviderType providerType;

    // EAGER is required here: getAuthorities() reads roles synchronously during authentication
    // (building the SecurityContext), which can happen outside the original persistence context —
    // a LAZY collection would throw LazyInitializationException at that point.
    @ElementCollection(fetch = FetchType.EAGER)
    @Enumerated(EnumType.STRING)
    Set<RoleType> roles = new HashSet<>();

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
//        return roles.stream()
//                .map(role -> new SimpleGrantedAuthority("ROLE_"+role.name()))
//                .collect(Collectors.toSet());
        Set<SimpleGrantedAuthority> authorities = new HashSet<>();
        roles.forEach(
                role -> {
                    // Expands each coarse RoleType into fine-grained PermissionType authorities via
                    // RolePermissionMapping, PLUS keeps the coarse ROLE_x authority — this dual output
                    // is what lets WebSecurityConfig mix hasRole() and hasAuthority()/@PreAuthorize checks.
                    Set<SimpleGrantedAuthority> permissions = RolePermissionMapping.getAuthoritiesForRole(role);
                    authorities.addAll(permissions);
                    authorities.add(new SimpleGrantedAuthority("ROLE_"+role.name()));
                }
        );
        return authorities;
    }
}



















