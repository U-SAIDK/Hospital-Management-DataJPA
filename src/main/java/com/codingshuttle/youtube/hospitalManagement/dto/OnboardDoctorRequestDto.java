package com.codingshuttle.youtube.hospitalManagement.dto;

import lombok.Data;

// Admin-only inbound shape: userId references an EXISTING User (created via normal signup)
// that AdminController.onBoardNewDoctor promotes into a Doctor — reflects the shared-PK
// (@MapsId) design where a Doctor row can only be attached to an already-existing User id.
@Data
public class OnboardDoctorRequestDto {
    private Long userId;
    private String specialization;
    private String name;
}
