package com.codingshuttle.youtube.hospitalManagement.repository;

import com.codingshuttle.youtube.hospitalManagement.entity.User;
import com.codingshuttle.youtube.hospitalManagement.entity.type.AuthProviderType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;


public interface UserRepository extends JpaRepository<User, Long> {

    // Derived query method: Spring Data parses the method name at startup into
    // "WHERE username = ?" — no @Query needed since the intent maps 1:1 onto a field.
    // Called by JwtAuthFilter on every request to reload the principal from the token's
    // username, and by CustomUserDetailsService during password login.
    Optional<User> findByUsername(String username);

    // Derived query over two fields (implicit AND) — backs OAuth2 login, where the only
    // stable identifier is the provider's own id (no username exists yet for a new OAuth2 user).
    Optional<User> findByProviderIdAndProviderType(String providerId, AuthProviderType providerType);
}