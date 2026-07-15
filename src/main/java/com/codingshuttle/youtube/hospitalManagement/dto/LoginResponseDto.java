package com.codingshuttle.youtube.hospitalManagement.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

// Wire contract returned by BOTH plain login and OAuth2 login (OAuth2SuccessHandler builds
// one of these too) — the shared shape is what lets clients treat both auth flows identically
// after the fact, regardless of which path issued the JWT.
@Data
@AllArgsConstructor
@NoArgsConstructor
public class LoginResponseDto {
    String jwt;
    Long userId;
}
