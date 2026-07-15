package com.codingshuttle.youtube.hospitalManagement.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

// Inbound credentials shape for AuthService.login() — deliberately has no relation to the
// User entity; it only exists to carry raw username/password into the AuthenticationManager.
@Data
@AllArgsConstructor
@NoArgsConstructor
public class LoginRequestDto {
    private String username;
    private String password;
}
