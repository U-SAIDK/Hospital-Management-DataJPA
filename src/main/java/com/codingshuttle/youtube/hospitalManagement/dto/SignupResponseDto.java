package com.codingshuttle.youtube.hospitalManagement.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

// Deliberately minimal outbound shape after signup: only id + username, no JWT — signup and
// login are kept as separate steps/contracts here (contrast with LoginResponseDto, which
// carries the jwt) rather than auto-logging-in the user on signup.
@Data
@AllArgsConstructor
@NoArgsConstructor
public class SignupResponseDto {
    private Long id;
    private String username;
}
