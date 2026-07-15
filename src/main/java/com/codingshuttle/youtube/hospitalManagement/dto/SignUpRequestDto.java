package com.codingshuttle.youtube.hospitalManagement.dto;

import com.codingshuttle.youtube.hospitalManagement.entity.type.RoleType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashSet;
import java.util.Set;

// Inbound signup shape shared by AuthService.signUpInternal() for both plain signup and
// OAuth2 signup — carries roles as a request field even though, per the briefing, every
// self-service signup in practice becomes a Patient (Doctor accounts are only created via
// AdminController's admin-only onboarding flow), a gap worth noticing rather than "fixing" here.
@Data
@AllArgsConstructor
@NoArgsConstructor
public class SignUpRequestDto {
    private String username;
    private String password;
    private String name;

    private Set<RoleType> roles = new HashSet<>();
}
