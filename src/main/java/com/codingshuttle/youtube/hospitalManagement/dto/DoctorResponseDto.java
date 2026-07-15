package com.codingshuttle.youtube.hospitalManagement.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

// API-boundary shape for Doctor: flattens the shared-PK User+Doctor pair and departments/
// appointments associations down to just what a client needs — avoids exposing the User
// entity (password, roles) or triggering LAZY-association serialization issues.
@Data
@AllArgsConstructor
@NoArgsConstructor
public class DoctorResponseDto {
    private Long id;
    private String name;
    private String specialization;
    private String email;
}
